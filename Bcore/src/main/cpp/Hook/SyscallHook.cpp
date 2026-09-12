/*
 * SyscallHook.cpp
 *
 * Helium SDK and the game's native anti-cheat issue raw "svc #0" syscalls that
 * bypass every libc symbol Dobby can hook (open, read, fopen, fgets).
 *
 * Architecture: ARM32 (armeabi-v7a) and ARM64 (arm64-v8a).
 *
 * Strategy:
 *   1. Install a seccomp BPF filter: SECCOMP_RET_TRAP on syscalls the game uses
 *      to scan the environment (openat, read, fstat, readlinkat, statx, getdents64).
 *   2. Install a SIGSYS handler. Every intercepted raw syscall delivers SIGSYS.
 *   3. The handler blocks blocked paths (ENOENT) or passes through via our own
 *      raw svc #0 which is NOT trapped by the filter.
 *
 * init_seccomp() is exported as a JNI symbol so NativeCore.init_seccomp() can
 * call it from Java (BActivityThread) before makeApplication().
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
#include <jni.h>

/* ── Architecture ────────────────────────────────────────────────────────────── */
#if defined(__aarch64__)
    #define IS_ARM64 1
    #define IS_ARM32 0
#elif defined(__arm__)
    #define IS_ARM64 0
    #define IS_ARM32 1
#else
    #error "Unsupported architecture"
#endif

/* ── ARM32 syscall number fallbacks ─────────────────────────────────────────── */
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

/* ── Blocked path fragments ──────────────────────────────────────────────────── */
static const char *BLOCKED[] = {
    "blackbox", "akroengine", "libakroengine", "samuraiengine",
    "com.fs4ip",  "top.niunaijun", "/engine/", "black_box",
    "XposedBridge", "xposed.prop",
    "/data/adb/magisk", "/sbin/magisk", "/system/bin/magisk",
    "/system/xbin/su",  "/system/bin/su", "/sbin/su",
    "/data/local/xbin/su",
    "io.va.exposed", "com.lody.virtual",
    nullptr
};

static bool path_blocked(const char *p) {
    if (!p) return false;
    for (int i = 0; BLOCKED[i]; ++i)
        if (strstr(p, BLOCKED[i])) return true;
    return false;
}

/* ── Raw syscall (not intercepted by our filter) ─────────────────────────────── */
static inline long raw(long nr,
                       long a0=0, long a1=0, long a2=0,
                       long a3=0, long a4=0, long a5=0)
{
#if IS_ARM64
    register long x8 asm("x8") = nr;
    register long x0 asm("x0") = a0;
    register long x1 asm("x1") = a1;
    register long x2 asm("x2") = a2;
    register long x3 asm("x3") = a3;
    register long x4 asm("x4") = a4;
    register long x5 asm("x5") = a5;
    asm volatile("svc #0"
        : "+r"(x0)
        : "r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5)
        : "memory","cc");
    return x0;
#elif IS_ARM32
    register long r7 asm("r7") = nr;
    register long r0 asm("r0") = a0;
    register long r1 asm("r1") = a1;
    register long r2 asm("r2") = a2;
    register long r3 asm("r3") = a3;
    register long r4 asm("r4") = a4;
    register long r5 asm("r5") = a5;
    asm volatile("svc #0"
        : "+r"(r0)
        : "r"(r7),"r"(r1),"r"(r2),"r"(r3),"r"(r4),"r"(r5)
        : "memory","cc");
    return r0;
#endif
}

/* ── SIGSYS handler ──────────────────────────────────────────────────────────── */
static void on_sigsys(int sig, siginfo_t *si, void *uctx) {
    (void)sig;
    ucontext_t *ctx = (ucontext_t *)uctx;
    long nr = (long)si->si_syscall;

#if IS_ARM64
    long *regs = (long *)ctx->uc_mcontext.regs;   // x0..x30
#elif IS_ARM32
    // ARM32 mcontext: arm_r0..arm_r10, arm_fp, arm_ip, arm_sp, arm_lr, arm_pc, arm_cpsr
    struct { unsigned long r[18]; } *mctx =
        (decltype(mctx))&ctx->uc_mcontext;
    long *regs = (long *)mctx->r;
#endif

    long ret = 0;

    if (nr == __NR_openat || nr == __NR_faccessat
#ifdef __NR_faccessat2
        || nr == __NR_faccessat2
#endif
    ) {
        if (path_blocked((const char *)regs[1])) { regs[0] = -ENOENT; return; }
        ret = raw(nr, regs[0], regs[1], regs[2], regs[3], regs[4]);
        regs[0] = ret; return;
    }
    if (nr == __NR_readlinkat) {
        if (path_blocked((const char *)regs[1])) { regs[0] = -ENOENT; return; }
        ret = raw(nr, regs[0], regs[1], regs[2], regs[3], regs[4]);
        regs[0] = ret; return;
    }
    if (nr == __NR_statx || nr == __NR_newfstatat) {
        if (path_blocked((const char *)regs[1])) { regs[0] = -ENOENT; return; }
        ret = raw(nr, regs[0], regs[1], regs[2], regs[3], regs[4]);
        regs[0] = ret; return;
    }
    if (nr == __NR_read) {
        ret = raw(nr, regs[0], regs[1], regs[2]);
        regs[0] = ret; return;
    }
    if (nr == __NR_getdents64) {
        ret = raw(nr, regs[0], regs[1], regs[2]);
        regs[0] = ret; return;
    }
    ret = raw(nr, regs[0], regs[1], regs[2], regs[3], regs[4], regs[5]);
    regs[0] = ret;
}

/* ── BPF filter ──────────────────────────────────────────────────────────────── */
static void install_filter() {
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat,     0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat,  0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

#ifdef __NR_faccessat2
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat2, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_read,       0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statx,      0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_newfstatat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getdents64, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),

        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = {
        .len    = (unsigned short)(sizeof(f) / sizeof(f[0])),
        .filter = f,
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        ALOGE("SyscallHook: PR_SET_NO_NEW_PRIVS: %s", strerror(errno));
        return;
    }
    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER,
                SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0) {
        // fallback: per-thread only
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
            ALOGE("SyscallHook: seccomp install failed: %s", strerror(errno));
            return;
        }
    }
    ALOGD("SyscallHook: filter installed (%zu instructions)",
          sizeof(f) / sizeof(f[0]));
}

/* ── Public C++ entry ────────────────────────────────────────────────────────── */
void SyscallHook::init() {
    struct sigaction sa{};
    sa.sa_flags     = SA_SIGINFO | SA_RESTART;
    sa.sa_sigaction = on_sigsys;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, nullptr) != 0) {
        ALOGE("SyscallHook: sigaction SIGSYS: %s", strerror(errno));
        return;
    }
    ALOGD("SyscallHook: SIGSYS handler installed");
    install_filter();
    ALOGD("SyscallHook: init complete");
}

/* ── JNI export: NativeCore.init_seccomp() ───────────────────────────────────── */
// Called from Java: top.niunaijun.blackbox.core.NativeCore.init_seccomp()
// This mirrors the working Samurai Engine: init_seccomp is declared native
// in NativeCore and wired here so BActivityThread.bindApplication() can
// trigger it at the right moment.
extern "C" JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_core_NativeCore_init_1seccomp(JNIEnv *, jclass) {
    SyscallHook::init();
}
