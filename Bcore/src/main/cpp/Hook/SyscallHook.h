#pragma once

class SyscallHook {
public:
    // init() installs the seccomp BPF filter + SIGSYS handler.
    // Must be called before any guest app code runs.
    static void init();
};
