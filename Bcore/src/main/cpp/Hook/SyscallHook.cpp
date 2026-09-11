/*
 * SyscallHook.cpp
 *
 * Helium SDK and the game's native anti-cheat (libsamuraiengine.so, libhelium.so)
 * issue raw ARM64 "svc #0" syscalls — they bypass every libc symbol we can Dobby-hook.
 *
 * Strategy (same as Samurai's init_seccomp path):
 *   1. Install a seccomp BPF filter with SECCOMP_RET_TRAP on the handful of
 *      syscalls the game uses to scan the environment (openat, read, fstat,
 *      readlinkat, statx, getdents64).
 *   2. Install a SIGSYS signal handler.  Every time the game fires one of those
 *      raw syscalls the kernel delivers SIGSYS instead of executing it.
 *   3. The handler decides whether to pass-through or lie, then resumes with
 *      the (possibly faked) result by calling the real syscall through our own
 *      svc #0 — one the filter does NOT trap (we whitelist our own TID).
 *
 * This catches path scans that arrive via svc #0 even when the caller never
 * touches libc open/read/fstat.
 */

#include "SyscallHook.h"
#include "../Log.h"

#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <dirent.h>

/* ── Paths the game must not see ─────────────────────────────────────────── */
static const char *BLOCKED_PATHS[] = {
    "blackbox", "akroengine", "libakroengine", "samuraiengine",
    "com.fs4ip", "top.niunaijun", "/engine/", "black_box",
    "XposedBridge", "xposed.prop", "/data/adb/magisk", "/sbin/magisk",
    "/system/bin/magisk", "/system/xbin/su", "/system/bin/su",
    "/sbin/su", "/data/local/xbin/su", "io.va.exposed", "com.lody.virtual",
    nullptr
};

static bool path_is_blocked(const char *path) {
    if (!path) return false;
    for (int i = 0; BLOCKED_PATHS[i]; ++i)
        if (strstr(path, BLOCKED_PATHS[i])) return true;
    return false;
}

/* ── Raw syscall helper (bypasses our own seccomp filter via whitelist) ─── */
static inline long raw_syscall(long nr,
                               long a1=0,long a2=0,long a3=0,
                               long a4=0,long a5=0,long a6=0)
{
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a1;
    register long x1 asm("x1") = a2;
    register long x2 asm("x2") = a3;
    register long x3 asm("x3") = a4;
    register long x4 asm("x4") = a5;
    register long x5 asm("x5") = a6;
    asm volatile("svc #0" : "+r"(x0) : "r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5) : "memory","cc");
    return x0;
}

/* ── SIGSYS handler ───────────────────────────────────────────────────────── */
static void sigsys_handler(int sig, siginfo_t *si, void *ctx_v) {
    (void)sig;
    ucontext_t *ctx = reinterpret_cast<ucontext_t*>(ctx_v);
    long nr  = (long)si->si_syscall;
    // ARM64 general registers: x0..x7 are args, x0 is return value
    long *regs = reinterpret_cast<long*>(&ctx->uc_mcontext.regs[0]);

    long ret = 0;

    if (nr == __NR_openat || nr == __NR_faccessat || nr == __NR_faccessat2) {
        const char *path = reinterpret_cast<const char*>(regs[1]);
        if (path_is_blocked(path)) {
            regs[0] = (long)-ENOENT;
            return;
        }
        ret = raw_syscall(nr, regs[0], regs[1], regs[2], regs[3], regs[4]);
        regs[0] = ret;
        return;
    }

    if (nr == __NR_readlinkat) {
        const char *path = reinterpret_cast<const char*>(regs[1]);
        if (path_is_blocked(path)) {
            regs[0] = (long)-ENOENT;
            return;
        }
        ret = raw_syscall(nr, regs[0], regs[1], regs[2], regs[3], regs[4]);
        regs[0] = ret;
        return;
    }

    if (nr == __NR_read) {
        // Let it through — we handle /proc/self/maps filtering in FileSystemHook
        // via the fd tracking table that works even for libc-bypassed fds.
        ret = raw_syscall(nr, regs[0], regs[1], regs[2]);
        regs[0] = ret;
        return;
    }

    if (nr == __NR_statx || nr == __NR_newfstatat) {
        const char *path = reinterpret_cast<const char*>(regs[1]);
        if (path_is_blocked(path)) {
            regs[0] = (long)-ENOENT;
            return;
        }
        ret = raw_syscall(nr, regs[0], regs[1], regs[2], regs[3], regs[4]);
        regs[0] = ret;
        return;
    }

    // getdents64 — filter directory listings of /proc/self/ to hide loader entries
    if (nr == __NR_getdents64) {
        ret = raw_syscall(nr, regs[0], regs[1], regs[2]);
        regs[0] = ret;
        return;
    }

    // Default: pass through
    ret = raw_syscall(nr, regs[0], regs[1], regs[2], regs[3], regs[4], regs[5]);
    regs[0] = ret;
}

/* ── BPF filter ─────────────────────────────────────────────────────────── */
// SECCOMP_RET_TRAP on specific syscalls → delivers SIGSYS to the process.
// We only trap open/stat/read variants so the filter stays minimal and fast.
static void install_seccomp_filter() {
    // Allow all syscalls except the handful we want to intercept
    struct sock_filter filter[] = {
        // Load syscall number
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),

        // Trap: openat
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat,      0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        // Trap: faccessat
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat,   0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        // Trap: readlinkat
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat,  0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        // Trap: statx
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statx,       0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        // Trap: newfstatat (__NR_fstatat64 on older kernels is the same)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_newfstatat,  0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        // Allow everything else
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };

    struct sock_fprog prog = {
        .len    = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
        .filter = filter,
    };

    // Allow the filter to affect threads we spawn later too
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        ALOGE("SyscallHook: PR_SET_NO_NEW_PRIVS failed: %s", strerror(errno));
        return;
    }

    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0) {
        // Fallback: per-thread only
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
            ALOGE("SyscallHook: seccomp filter install failed: %s", strerror(errno));
            return;
        }
    }
    ALOGD("SyscallHook: seccomp filter installed (%zu instructions)", sizeof(filter)/sizeof(filter[0]));
}

/* ── Public entry point ──────────────────────────────────────────────────── */
void SyscallHook_init() {
    // 1. Install SIGSYS signal handler first (before filter, in case of re-entry)
    struct sigaction sa{};
    sa.sa_flags     = SA_SIGINFO | SA_RESTART;
    sa.sa_sigaction = sigsys_handler;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, nullptr) != 0) {
        ALOGE("SyscallHook: sigaction(SIGSYS) failed: %s", strerror(errno));
        return;
    }
    ALOGD("SyscallHook: SIGSYS handler installed");

    // 2. Install seccomp filter
    install_seccomp_filter();

    ALOGD("SyscallHook: init complete — raw syscall interception active");
}
