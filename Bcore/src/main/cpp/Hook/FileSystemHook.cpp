#include "FileSystemHook.h"
#include "Log.h"
#include "xdl.h"
#include <sys/stat.h>
#include <fcntl.h>
#include <stdarg.h>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include "Dobby/dobby.h"

// ── Strings Helium / game anti-cheat looks for in /proc/self/maps ───────────
// Any line containing these strings is stripped from maps output.
static const char *MAPS_BLOCKLIST[] = {
    "com.fs4ip.delta",        // loader package
    "com.niunaijun",          // blackbox package path
    "blackbox",               // blackbox directory
    "akroengine",             // our renamed library
    "libakroengine",
    "libblackbox",
    "samuraiengine",
    "libsamurai",
    "/engine/",
    "black_box",
    nullptr
};

// ── Strings to hide from /proc/self/status (TracerPid lines) ────────────────
// TracerPid != 0 indicates the process is being traced → detected.

// ── Internal state ────────────────────────────────────────────────────────────
static int (*orig_open)(const char *pathname, int flags, ...)          = nullptr;
static int (*orig_open64)(const char *pathname, int flags, ...)        = nullptr;
static FILE *(*orig_fopen)(const char *path, const char *mode)         = nullptr;
static FILE *(*orig_fopen64)(const char *path, const char *mode)       = nullptr;
static ssize_t (*orig_read)(int fd, void *buf, size_t count)           = nullptr;
static char *(*orig_fgets)(char *s, int n, FILE *stream)               = nullptr;

// ── Per-fd filter state ──────────────────────────────────────────────────────
// We track which fds are open on /proc/self/maps or /proc/self/status
// so we can filter their read() output.
#define MAX_TRACKED_FDS 64
static struct {
    int  fd;
    int  type; // 1=maps, 2=status
} tracked_fds[MAX_TRACKED_FDS];
static pthread_mutex_t fd_mutex = PTHREAD_MUTEX_INITIALIZER;

static void track_fd(int fd, int type) {
    pthread_mutex_lock(&fd_mutex);
    for (int i = 0; i < MAX_TRACKED_FDS; i++) {
        if (tracked_fds[i].fd == 0) {
            tracked_fds[i].fd   = fd;
            tracked_fds[i].type = type;
            break;
        }
    }
    pthread_mutex_unlock(&fd_mutex);
}

static void untrack_fd(int fd) {
    pthread_mutex_lock(&fd_mutex);
    for (int i = 0; i < MAX_TRACKED_FDS; i++) {
        if (tracked_fds[i].fd == fd) {
            tracked_fds[i].fd   = 0;
            tracked_fds[i].type = 0;
            break;
        }
    }
    pthread_mutex_unlock(&fd_mutex);
}

static int get_fd_type(int fd) {
    pthread_mutex_lock(&fd_mutex);
    for (int i = 0; i < MAX_TRACKED_FDS; i++) {
        if (tracked_fds[i].fd == fd) {
            int t = tracked_fds[i].type;
            pthread_mutex_unlock(&fd_mutex);
            return t;
        }
    }
    pthread_mutex_unlock(&fd_mutex);
    return 0;
}

// ── Maps line filter ─────────────────────────────────────────────────────────
static bool should_block_maps_line(const char *line) {
    for (int i = 0; MAPS_BLOCKLIST[i] != nullptr; i++) {
        if (strstr(line, MAPS_BLOCKLIST[i])) return true;
    }
    return false;
}

// Filter a full maps/status buffer in-place.
// Returns new (shorter) content length.
static ssize_t filter_proc_buf(char *buf, ssize_t len, int type) {
    char *out   = buf;
    char *line  = buf;
    char *end   = buf + len;

    while (line < end) {
        // find end of this line
        char *nl = (char*)memchr(line, '\n', end - line);
        size_t line_len = nl ? (size_t)(nl - line + 1) : (size_t)(end - line);

        char saved = line[line_len];
        line[line_len] = '\0';

        bool block = false;
        if (type == 1) {
            // maps: drop any line containing loader/engine identifiers
            block = should_block_maps_line(line);
        } else if (type == 2) {
            // status: zero out TracerPid so debugger is not reported
            if (strncmp(line, "TracerPid:", 10) == 0) {
                // Replace "TracerPid:\t<n>" with "TracerPid:\t0"
                memcpy(out, "TracerPid:\t0\n", 13);
                out += 13;
                block = true;  // skip original
            }
        }

        line[line_len] = saved;

        if (!block) {
            memmove(out, line, line_len);
            out += line_len;
        }
        line += line_len;
    }
    return out - buf;
}

// ── Path classification ───────────────────────────────────────────────────────
static int classify_path(const char *path) {
    if (!path) return 0;
    if (strcmp(path, "/proc/self/maps")    == 0 ||
        strcmp(path, "/proc/thread-self/maps") == 0) return 1;
    // /proc/<pid>/maps — pid could be our own
    if (strstr(path, "/maps") && strstr(path, "/proc/")) return 1;
    if (strcmp(path, "/proc/self/status")  == 0 ||
        strstr(path, "/status") && strstr(path, "/proc/")) return 2;
    return 0;
}

// ── Hook: open ───────────────────────────────────────────────────────────────
int new_open(const char *pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);

    // Block known-bad paths (original behaviour)
    if (pathname &&
        (strstr(pathname, "resource-cache") ||
         strstr(pathname, "@idmap") ||
         strstr(pathname, ".frro") ||
         strstr(pathname, "data@resource-cache@"))) {
        errno = ENOENT;
        return -1;
    }

    int fd = orig_open(pathname, flags, mode);
    if (fd >= 0) {
        int type = classify_path(pathname);
        if (type) track_fd(fd, type);
    }
    return fd;
}

int new_open64(const char *pathname, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = va_arg(args, mode_t);
    va_end(args);

    if (pathname &&
        (strstr(pathname, "resource-cache") ||
         strstr(pathname, "@idmap") ||
         strstr(pathname, ".frro") ||
         strstr(pathname, "data@resource-cache@"))) {
        errno = ENOENT;
        return -1;
    }

    int fd = orig_open64(pathname, flags, mode);
    if (fd >= 0) {
        int type = classify_path(pathname);
        if (type) track_fd(fd, type);
    }
    return fd;
}

// ── Hook: read ───────────────────────────────────────────────────────────────
static ssize_t new_read(int fd, void *buf, size_t count) {
    ssize_t n = orig_read(fd, buf, count);
    if (n <= 0) {
        if (n == 0) untrack_fd(fd);  // EOF
        return n;
    }
    int type = get_fd_type(fd);
    if (type) {
        n = filter_proc_buf((char*)buf, n, type);
    }
    return n;
}

// ── Hook: fopen / fopen64 ─────────────────────────────────────────────────────
static FILE *new_fopen(const char *path, const char *mode) {
    FILE *f = orig_fopen(path, mode);
    if (f) {
        int type = classify_path(path);
        if (type) track_fd(fileno(f), type);
    }
    return f;
}

static FILE *new_fopen64(const char *path, const char *mode) {
    FILE *f = orig_fopen64(path, mode);
    if (f) {
        int type = classify_path(path);
        if (type) track_fd(fileno(f), type);
    }
    return f;
}

// ── Hook: fgets ──────────────────────────────────────────────────────────────
// fgets is used to read maps line-by-line. Filter each line.
static char *new_fgets(char *s, int n, FILE *stream) {
    char *result = orig_fgets(s, n, stream);
    if (!result) return result;

    int type = get_fd_type(fileno(stream));
    if (type == 1 && should_block_maps_line(s)) {
        // Skip this line — recurse to get the next non-blocked line
        return new_fgets(s, n, stream);
    }
    if (type == 2 && strncmp(s, "TracerPid:", 10) == 0) {
        snprintf(s, n, "TracerPid:\t0\n");
    }
    return result;
}

// ── Installer ────────────────────────────────────────────────────────────────
void FileSystemHook::init() {
    ALOGD("FileSystemHook: Initializing (Helium-aware)");

    void *libc = xdl_open("libc.so", XDL_DEFAULT);
    if (!libc) { ALOGE("FileSystemHook: libc.so not found"); return; }

#define HOOK_SYM(lib, name, new_fn, orig_ptr) do { \
    void *_sym = xdl_sym(lib, name, nullptr);       \
    if (_sym) DobbyHook(_sym, (void*)(new_fn), (void**)(orig_ptr)); \
    else ALOGE("FileSystemHook: symbol %s not found", name);        \
} while(0)

    HOOK_SYM(libc, "open",    new_open,    &orig_open);
    HOOK_SYM(libc, "open64",  new_open64,  &orig_open64);
    HOOK_SYM(libc, "read",    new_read,    &orig_read);
    HOOK_SYM(libc, "fopen",   new_fopen,   &orig_fopen);
    HOOK_SYM(libc, "fopen64", new_fopen64, &orig_fopen64);
    HOOK_SYM(libc, "fgets",   new_fgets,   &orig_fgets);

    xdl_close(libc);
    ALOGD("FileSystemHook: All hooks installed");

    // Zero out tracked_fds
    memset(tracked_fds, 0, sizeof(tracked_fds));
}
