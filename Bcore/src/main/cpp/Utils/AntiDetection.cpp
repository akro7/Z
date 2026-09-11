#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <dirent.h>
#include <stdarg.h>
#include "../Dobby/dobby.h"
#include "../xdl.h"

#define LOG_TAG "AntiDetection"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ── Blocked paths ─────────────────────────────────────────────────────────── */
static const char* BLOCKED_FILES[] = {
    // Root binaries
    "/system/xbin/su", "/system/bin/su", "/sbin/su",
    "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
    "/system/etc/init.d/99SuperSUDaemon",
    "/system/xbin/daemonsu", "/system/xbin/sugote",
    "/data/local/xbin/su", "/data/local/bin/su", "/data/local/tmp/su",
    // Magisk
    "/system/bin/magisk", "/system/xbin/magisk", "/sbin/magisk", "/data/adb/magisk",
    // Virtual framework indicators
    "/data/virtual", "/blackbox", "/virtual",
    "/data/data/com.benny.openlauncher",
    "/data/data/io.va.exposed",
    "/data/data/com.lody.virtual",
    "/data/data/com.excelliance.dualaid",
    "/data/data/com.lbe.parallel",
    "/data/data/top.niunaijun.blackboxa",
    // Emulator indicators
    "/dev/vboxguest", "/dev/vboxuser", "/dev/qemu_pipe", "/dev/goldfish_pipe",
    "/dev/socket/qemud", "/dev/socket/baseband_genyd", "/dev/socket/genyd",
    "/sys/qemu_trace", "/sys/module/goldfish_audio", "/sys/module/goldfish_sync",
    "/proc/tty/drivers/goldfish", "/dev/goldfish_events",
    "/system/lib/libc_malloc_debug_qemu.so", "/system/bin/qemu-props",
    "/system/lib/libdroid4x.so", "/system/bin/windroyed",
    "/system/lib/libnoxspeedup.so", "/system/lib/libmemu.so", "/system/lib/libbluelog.so",
    // Xposed
    "/system/xposed.prop", "/system/framework/XposedBridge.jar",
    "/data/data/de.robv.android.xposed.installer",
    "/data/data/org.meowcat.edxposed.manager",
    nullptr
};

static const char* BLOCKED_PACKAGES[] = {
    "com.noshufou.android.su", "eu.chainfire.supersu", "com.koushikdutta.superuser",
    "com.devadvance.rootcloak", "de.robv.android.xposed.installer",
    "com.saurik.substrate", "com.amphoras.hidemyroot", "com.formyhm.hideroot",
    "me.phh.superuser", "com.topjohnwu.magisk", "com.lody.virtual",
    "io.va.exposed", "com.benny.openlauncher",
    nullptr
};

static bool is_blocked(const char* path) {
    if (!path) return false;
    for (int i = 0; BLOCKED_FILES[i]; ++i)
        if (strstr(path, BLOCKED_FILES[i])) return true;
    for (int i = 0; BLOCKED_PACKAGES[i]; ++i)
        if (strstr(path, BLOCKED_PACKAGES[i])) return true;
    return false;
}

static bool is_safe_exception(const char* path) {
    if (!path) return false;
    if (strstr(path, "/proc/net/")) return true;
    return false;
}

/* ── Original function pointers ─────────────────────────────────────────── */
static int   (*orig_access)(const char*, int)            = nullptr;
static int   (*orig_stat)(const char*, struct stat*)     = nullptr;
static int   (*orig_lstat)(const char*, struct stat*)    = nullptr;
static FILE* (*orig_fopen)(const char*, const char*)     = nullptr;
static int   (*orig_open)(const char*, int, ...)         = nullptr;
static ssize_t (*orig_readlink)(const char*, char*, size_t) = nullptr;
static DIR*  (*orig_opendir)(const char*)                = nullptr;

/* ── Hook implementations ──────────────────────────────────────────────── */
static int my_access(const char *path, int mode) {
    if (!is_safe_exception(path) && is_blocked(path)) { errno = ENOENT; return -1; }
    return orig_access ? orig_access(path, mode) : (errno=ENOENT, -1);
}

static int my_stat(const char *path, struct stat *buf) {
    if (!is_safe_exception(path) && is_blocked(path)) { errno = ENOENT; return -1; }
    return orig_stat ? orig_stat(path, buf) : (errno=ENOENT, -1);
}

static int my_lstat(const char *path, struct stat *buf) {
    if (!is_safe_exception(path) && is_blocked(path)) { errno = ENOENT; return -1; }
    return orig_lstat ? orig_lstat(path, buf) : (errno=ENOENT, -1);
}

static FILE* my_fopen(const char *path, const char *mode) {
    if (!is_safe_exception(path) && is_blocked(path)) { errno = ENOENT; return nullptr; }
    return orig_fopen ? orig_fopen(path, mode) : nullptr;
}

static int my_open(const char *path, int flags, ...) {
    if (!is_safe_exception(path) && is_blocked(path)) { errno = ENOENT; return -1; }
    if (!orig_open) return -1;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags);
        mode_t mode = va_arg(args, mode_t); va_end(args);
        return orig_open(path, flags, mode);
    }
    return orig_open(path, flags);
}

static ssize_t my_readlink(const char *path, char *buf, size_t bufsiz) {
    if (!is_safe_exception(path) && is_blocked(path)) { errno = ENOENT; return -1; }
    return orig_readlink ? orig_readlink(path, buf, bufsiz) : (errno=ENOENT, -1);
}

static DIR* my_opendir(const char *name) {
    if (!is_safe_exception(name) && is_blocked(name)) { errno = ENOENT; return nullptr; }
    return orig_opendir ? orig_opendir(name) : nullptr;
}

/* ── Installer ─────────────────────────────────────────────────────────── */
static void install_file_hooks() {
    void* libc = xdl_open("libc.so", XDL_DEFAULT);
    if (!libc) { LOGE("AntiDetection: xdl_open libc.so failed"); return; }

#define HOOK(sym, new_fn, orig_ptr)  do { \
    void* _t = xdl_dsym(libc, sym, nullptr); \
    if (!_t) _t = xdl_sym(libc, sym, nullptr); \
    if (_t) { \
        if (DobbyHook(_t, (void*)(new_fn), (void**)(orig_ptr)) == 0) \
            LOGD("AntiDetection: hooked %s", sym); \
        else \
            LOGE("AntiDetection: DobbyHook failed for %s", sym); \
    } else { LOGE("AntiDetection: symbol %s not found", sym); } \
} while(0)

    HOOK("access",    my_access,    &orig_access);
    HOOK("stat",      my_stat,      &orig_stat);
    HOOK("lstat",     my_lstat,     &orig_lstat);
    HOOK("fopen",     my_fopen,     &orig_fopen);
    HOOK("open",      my_open,      &orig_open);
    HOOK("readlink",  my_readlink,  &orig_readlink);
    HOOK("opendir",   my_opendir,   &orig_opendir);

    xdl_close(libc);
    LOGD("AntiDetection: all file-system presence hooks installed");
}

__attribute__((constructor)) void install_antidetection_hooks() {
    LOGD("AntiDetection: constructor running...");
    install_file_hooks();
    LOGD("AntiDetection: done");
}
