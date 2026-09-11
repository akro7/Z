#pragma once

// SyscallHook — intercepts raw ARM64 syscalls (svc #0) that bypass libc hooks.
// Helium SDK and libsamuraiengine use direct syscalls; this catches them via
// seccomp(SECCOMP_SET_MODE_FILTER) + SIGSYS signal handler.

void SyscallHook_init();
