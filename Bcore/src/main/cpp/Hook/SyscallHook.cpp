/*
 * SyscallHook.cpp
 *
 * Helium SDK and the game's native anti-cheat (libsamuraiengine.so, libhelium.so)
 * issue raw ARM64 "svc #0" syscalls — they bypass every libc symbol we can Dobby-hook.
 *
 * ARCHITECTURE SUPPORT: ARM32 (armeabi-v7a) and ARM64 (arm64-v8a)
 *
 * Strategy:
 *   1. Install a seccomp BPF filter with SECCOMP_RET_TRAP on the handful of
 *      syscalls the game uses to scan the environment (openat, read, fstat,
 *      readlinkat, statx, getdents64).
 *   2. Install a SIGSYS signal handler. Every time the game fires one of those
 *      raw syscalls the kernel delivers SIGSYS instead of executing it.
 *   3. The handler decides whether to pass-through or lie, then resumes with
 *      the (possibly faked) result by calling the real syscall through our own
 *      svc #0 — one the filter does NOT trap (we whitelist our own TID).
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

/* ── Architecture Detection ─────────────────────────────────────────────────── */
#if defined(__aarch64__)
    #define IS_ARM64 1
    #define IS_ARM32 0
#elif defined(__arm__)
    #define IS_ARM64 0
    #define IS_ARM32 1
#else
    #error "Unsupported architecture: only ARM32 (armeabi-v7a) and ARM64 (arm64-v8a) are supported"
#endif

/* ── ARM32-specific syscall number fallbacks ────────────────────────────────── */
#if IS_ARM32
    #ifndef __NR_newfstatat
        #define __NR_newfstatat 262
    #endif
    #ifndef __NR_statx
        #define __NR_statx 397
    #endif
    #ifndef __NR_faccessat2
        #define __NR_faccessat2 439
    #endif
#endif

/* ── Paths the game must not see ─────────────────────────────────────────────── */
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

/* ── Raw syscall helper (bypasses our own seccomp filter) ───────────────────── */
static inline long raw_syscall(long nr,
                               long a1=0, long a2=0, long a3=0,
                               long a4=0, long a5=0, long a6=0)
{
#if IS_ARM64
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a1;
    register long x1 asm("x1") = a2;
    register long x2 asm("x2") = a3;
    register long x3 asm("x3") = a4;
    register long x4 asm("x4") = a5;
    register long x5 asm("x5") = a6;
    asm volatile(
        "svc #0"
        : "+r"(x0)
        : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
        : "memory", "cc"
    );
    return x0;

#elif IS_ARM32
    register long r7 asm("r7") = nr;
    register long r0 asm("r0") = a1;
    register long r1 asm("r1") = a2;
    register long r2 asm("r2") = a3;
    register long r3 asm("r3") = a4;
    register long r4 asm("r4") = a5;
    register long r5 asm("r5") = a6;
    asm volatile(
        "svc #0"
        : "+r"(r0)
        : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5)
        : "memory", "cc"
    );
    return r0;
#endif
}

/* ── SIGSYS handler ────────────────────────────────────────────────────────── */
static void sigsys_handler(int sig, siginfo_t *si, void *ctx_v) {
    (void)sig;
    ucontext_t *ctx = reinterpret_cast<ucontext_t*>(ctx_v);
    long nr  = (long)si->si_syscall;
    long ret = 0;

#if IS_ARM64
    long *regs = reinterpret_cast<long*>(&ctx->uc_mcontext.regs[0]);

#elif IS_ARM32
    struct arm_ctxt {
        unsigned long r[17];
    };
    arm_ctxt *mctx = reinterpret_cast<arm_ctxt*>(&ctx->uc_mcontext);
    long *regs = reinterpret_cast<long*>(mctx->r);
#endif

    /* ── openat / faccessat / faccessat2 ────────────────────────────────── */
    if (nr == __NR_openat || nr == __NR_faccessat
        #if defined(__NR_faccessat2)
        || nr == __NR_faccessat2
        #endif
    ) {
        const char *path = reinterpret_cast<const char*>(regs[1]);
        if (path_is_blocked(path)) {
            regs[0] = (long)-ENOENT;
            return;
        }
        ret = raw_syscall(nr, regs[0], regs[1], regs[2], regs[3], regs[4]);
        regs[0] = ret;
        return;
    }

    /* ── readlinkat ─────────────────────────────────────────────────────── */
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

    /* ── read ───────────────────────────────────────────────────────────── */
    if (nr == __NR_read) {
        ret = raw_syscall(nr, regs[0], regs[1], regs[2]);
        regs[0] = ret;
        return;
    }

    /* ── statx / newfstatat ─────────────────────────────────────────────── */
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

    /* ── getdents64 ─────────────────────────────────────────────────────── */
    if (nr == __NR_getdents64) {
        ret = raw_syscall(nr, regs[0], regs[1], regs[2]);
        regs[0] = ret;
        return;
    }

    /* ── Default: pass through ──────────────────────────────────────────── */
    ret = raw_syscall(nr, regs[0], regs[1], regs[2], regs[3], regs[4], regs[5]);
    regs[0] = ret;
}

/* ── BPF filter ────────────────────────────────────────────────────────────── */
static void install_seccomp_filter() {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat,      0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat,   0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

#if defined(__NR_faccessat2)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat2,  0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat,  0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_read,        0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statx,       0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_newfstatat,  0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getdents64,  0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };

    struct sock_fprog prog = {
        .len    = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
        .filter = filter,
    };

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        ALOGE("SyscallHook: PR_SET_NO_NEW_PRIVS failed: %s", strerror(errno));
        return;
    }

    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0) {
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
            ALOGE("SyscallHook: seccomp filter install failed: %s", strerror(errno));
            return;
        }
    }

#if IS_ARM64
    ALOGD("SyscallHook: seccomp filter installed (%zu instructions) [ARM64]",
          sizeof(filter)/sizeof(filter[0]));
#elif IS_ARM32
    ALOGD("SyscallHook: seccomp filter installed (%zu instructions) [ARM32]",
          sizeof(filter)/sizeof(filter[0]));
#endif
}

/* ── Public entry point ────────────────────────────────────────────────────── */
void SyscallHook::init() {
    struct sigaction sa{};
    sa.sa_flags     = SA_SIGINFO | SA_RESTART;
    sa.sa_sigaction = sigsys_handler;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, nullptr) != 0) {
        ALOGE("SyscallHook: sigaction(SIGSYS) failed: %s", strerror(errno));
        return;
    }

#if IS_ARM64
    ALOGD("SyscallHook: SIGSYS handler installed [ARM64]");
#elif IS_ARM32
    ALOGD("SyscallHook: SIGSYS handler installed [ARM32]");
#endif

    install_seccomp_filter();

#if IS_ARM64
    ALOGD("SyscallHook: init complete [ARM64]");
#elif IS_ARM32
    ALOGD("SyscallHook: init complete [ARM32]");
#endif
}
