/**
 * Ptrace utility functions for remote process injection on Android ARM64/ARM32
 * Based on techniques from FakeLocation (com.lerist.fakelocation)
 */

#pragma once

#include <sys/types.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <dlfcn.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>

#define LOG_TAG "KailInject"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef __aarch64__
    #define KAIL_ARCH_64
    struct kail_regs_t {
        uint64_t regs[31];
        uint64_t sp;
        uint64_t pc;
        uint64_t pstate;
    };
    #define KAIL_REG_PC(_kail_r_) ((_kail_r_).pc)
    #define KAIL_REG_SP(_kail_r_) ((_kail_r_).sp)
    #define KAIL_REG_X0(_kail_r_) ((_kail_r_).regs[0])
    #define KAIL_REG_X1(_kail_r_) ((_kail_r_).regs[1])
    #define KAIL_REG_X2(_kail_r_) ((_kail_r_).regs[2])
    #define KAIL_REG_X3(_kail_r_) ((_kail_r_).regs[3])
    #define KAIL_REG_X4(_kail_r_) ((_kail_r_).regs[4])
    #define KAIL_REG_X5(_kail_r_) ((_kail_r_).regs[5])
    #define KAIL_REG_X6(_kail_r_) ((_kail_r_).regs[6])
    #define KAIL_REG_X7(_kail_r_) ((_kail_r_).regs[7])
    #define KAIL_REG_X8(_kail_r_) ((_kail_r_).regs[8])
    #define KAIL_REG_LR(_kail_r_) ((_kail_r_).regs[30])
#else
    #define KAIL_ARCH_32
    struct kail_regs_t {
        uint32_t r[18];
    };
    #define KAIL_REG_PC(_kail_r_) ((_kail_r_).r[15])
    #define KAIL_REG_SP(_kail_r_) ((_kail_r_).r[13])
    #define KAIL_REG_X0(_kail_r_) ((_kail_r_).r[0])
    #define KAIL_REG_X1(_kail_r_) ((_kail_r_).r[1])
    #define KAIL_REG_X2(_kail_r_) ((_kail_r_).r[2])
    #define KAIL_REG_X3(_kail_r_) ((_kail_r_).r[3])
    #define KAIL_REG_X4(_kail_r_) ((_kail_r_).r[4])
    #define KAIL_REG_X5(_kail_r_) ((_kail_r_).r[5])
    #define KAIL_REG_LR(_kail_r_) ((_kail_r_).r[14])
#endif

namespace kail {

struct ModuleInfo {
    char name[256];
    uintptr_t base;
    size_t size;
};

// Find module base address in target process via /proc/<pid>/maps
bool find_module_base(pid_t pid, const char* module_name, ModuleInfo* info);

// Find local module base address
uintptr_t find_local_module_base(const char* module_name);

// Ptrace wrapper with retry on EINTR
long kail_ptrace(int request, pid_t pid, void* addr, void* data);

// Attach and wait for process
bool ptrace_attach(pid_t pid);

// Detach from process
bool ptrace_detach(pid_t pid);

// Get registers
bool ptrace_getregs(pid_t pid, kail_regs_t* regs);

// Set registers
bool ptrace_setregs(pid_t pid, const kail_regs_t* regs);

// Read memory from remote process
bool ptrace_read(pid_t pid, uintptr_t addr, void* buf, size_t size);

// Write memory to remote process
bool ptrace_write(pid_t pid, uintptr_t addr, const void* buf, size_t size);

// Find symbol address in local process
uintptr_t find_symbol(const char* module_name, const char* symbol_name);

// Get process ID by process name
pid_t find_pid_by_name(const char* process_name);

} // namespace kail
