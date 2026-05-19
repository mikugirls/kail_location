/**
 * KailLocation Ptrace Injector
 * Injects libkail_root_hook.so into remote Android processes via ptrace.
 * Only requires root access, no LSPosed/Xposed needed.
 *
 * Usage: kail_inject -p <pid> -l <lib_path>
 *        kail_inject -P <process_name> -l <lib_path>
 */

#include "ptrace_utils.h"
#include <getopt.h>
#include <stdarg.h>
#include <string>
#include <vector>

using namespace kail;

struct RemoteContext {
    pid_t pid;
    uintptr_t libc_base;
    uintptr_t linker_base;
    uintptr_t dlopen_addr;
    uintptr_t dlerror_addr;
    uintptr_t dlsym_addr;
    uintptr_t mmap_addr;
    uintptr_t munmap_addr;
};

static bool resolve_remote_functions(RemoteContext& ctx) {
    // Find libc.so base in remote process
    ModuleInfo libc_info = {};
    if (!find_module_base(ctx.pid, "libc.so", &libc_info)) {
        ALOGE("Failed to find libc.so in remote process %d", ctx.pid);
        return false;
    }
    ctx.libc_base = libc_info.base;
    ALOGI("Remote libc.so: base=0x%lx path=%s", (unsigned long)libc_info.base, libc_info.name);

    // Find linker base in remote process
    ModuleInfo linker_info = {};
    const char* linker_name =
#ifdef KAIL_ARCH_64
        "linker64";
#else
        "linker";
#endif
    if (!find_module_base(ctx.pid, linker_name, &linker_info)) {
        ALOGE("Failed to find %s in remote process %d", linker_name, ctx.pid);
        return false;
    }
    ctx.linker_base = linker_info.base;
    ALOGI("Remote %s: base=0x%lx path=%s", linker_name, (unsigned long)linker_info.base, linker_info.name);

    // Find local bases to compute offsets
    uintptr_t local_libc_base = find_local_module_base("libc.so");
    uintptr_t local_linker_base = find_local_module_base(linker_name);

    if (local_libc_base == 0 || local_linker_base == 0) {
        ALOGE("Failed to find local module bases");
        return false;
    }

    // Resolve local symbols
    // On Android 10+, dlopen/dlsym/dlerror are in libdl.so, not linker64.
    // Try libdl.so first, fall back to linker.
    ModuleInfo libdl_info = {};
    bool has_remote_libdl = find_module_base(ctx.pid, "libdl.so", &libdl_info);
    uintptr_t local_libdl_base = find_local_module_base("libdl.so");

    uintptr_t local_dlopen = 0, local_dlerror = 0, local_dlsym = 0;
    uintptr_t remote_dl_base = 0;
    uintptr_t local_dl_base = 0;

    if (has_remote_libdl && local_libdl_base != 0) {
        local_dlopen = find_symbol("libdl.so", "dlopen");
        local_dlerror = find_symbol("libdl.so", "dlerror");
        local_dlsym = find_symbol("libdl.so", "dlsym");
        if (local_dlopen != 0) {
            ALOGI("Using libdl.so for dlopen/dlsym/dlerror");
            remote_dl_base = libdl_info.base;
            local_dl_base = local_libdl_base;
        }
    }

    if (local_dlopen == 0) {
        ALOGI("Falling back to %s for dlopen/dlsym/dlerror", linker_name);
        local_dlopen = find_symbol(linker_name, "dlopen");
        local_dlerror = find_symbol(linker_name, "dlerror");
        local_dlsym = find_symbol(linker_name, "dlsym");
        remote_dl_base = ctx.linker_base;
        local_dl_base = local_linker_base;
    }

    uintptr_t local_mmap = find_symbol("libc.so", "mmap");
    uintptr_t local_munmap = find_symbol("libc.so", "munmap");

    if (local_dlopen == 0 || local_mmap == 0) {
        ALOGE("Failed to resolve dlopen or mmap");
        return false;
    }

    // Calculate remote addresses by offset
    ctx.dlopen_addr = remote_dl_base + (local_dlopen - local_dl_base);
    ctx.dlerror_addr = remote_dl_base + (local_dlerror - local_dl_base);
    ctx.dlsym_addr = remote_dl_base + (local_dlsym - local_dl_base);
    ctx.mmap_addr = ctx.libc_base + (local_mmap - local_libc_base);
    ctx.munmap_addr = ctx.libc_base + (local_munmap - local_libc_base);

    ALOGI("Remote dlopen=0x%lx dlerror=0x%lx dlsym=0x%lx mmap=0x%lx munmap=0x%lx",
          (unsigned long)ctx.dlopen_addr, (unsigned long)ctx.dlerror_addr, (unsigned long)ctx.dlsym_addr, (unsigned long)ctx.mmap_addr, (unsigned long)ctx.munmap_addr);
    return true;
}

// No setup_breakpoint needed: we use LR=0 strategy which triggers SIGSEGV on ret

static uintptr_t remote_function_call(RemoteContext& ctx, uintptr_t func_addr,
                                       int argc, ...) {
    kail_regs_t regs, saved_regs;
    if (!ptrace_getregs(ctx.pid, &saved_regs)) {
        return (uintptr_t)-1;
    }
    memcpy(&regs, &saved_regs, sizeof(regs));

    va_list args;
    va_start(args, argc);

#ifdef KAIL_ARCH_64
    if (argc > 0) KAIL_REG_X0(regs) = va_arg(args, uintptr_t);
    if (argc > 1) KAIL_REG_X1(regs) = va_arg(args, uintptr_t);
    if (argc > 2) KAIL_REG_X2(regs) = va_arg(args, uintptr_t);
    if (argc > 3) KAIL_REG_X3(regs) = va_arg(args, uintptr_t);
    if (argc > 4) KAIL_REG_X4(regs) = va_arg(args, uintptr_t);
    if (argc > 5) KAIL_REG_X5(regs) = va_arg(args, uintptr_t);
    if (argc > 6) KAIL_REG_X6(regs) = va_arg(args, uintptr_t);
    if (argc > 7) KAIL_REG_X7(regs) = va_arg(args, uintptr_t);
#else
    // ARM32: arguments are passed in r0-r3, rest on stack
    if (argc > 0) KAIL_REG_X0(regs) = va_arg(args, uintptr_t);
    if (argc > 1) KAIL_REG_X1(regs) = va_arg(args, uintptr_t);
    if (argc > 2) KAIL_REG_X2(regs) = va_arg(args, uintptr_t);
    if (argc > 3) KAIL_REG_X3(regs) = va_arg(args, uintptr_t);
#endif
    va_end(args);

    // FakeLocation strategy: set LR = 0, so ret triggers SIGSEGV
    // This avoids searching for brk/udf instructions
    KAIL_REG_PC(regs) = func_addr;
    KAIL_REG_LR(regs) = 0;

    if (!ptrace_setregs(ctx.pid, &regs)) {
        ptrace_setregs(ctx.pid, &saved_regs);
        return (uintptr_t)-1;
    }

    if (kail_ptrace(PTRACE_CONT, ctx.pid, nullptr, nullptr) == -1) {
        ALOGE("PTRACE_CONT failed: %s", strerror(errno));
        ptrace_setregs(ctx.pid, &saved_regs);
        return (uintptr_t)-1;
    }

    int status;
    pid_t waited = waitpid(ctx.pid, &status, WUNTRACED);
    if (waited != ctx.pid) {
        ALOGE("waitpid failed: %s", strerror(errno));
        ptrace_setregs(ctx.pid, &saved_regs);
        return (uintptr_t)-1;
    }

    if (!WIFSTOPPED(status)) {
        ALOGE("Process not stopped after call, status=%d sig=%d", status, WTERMSIG(status));
        ptrace_setregs(ctx.pid, &saved_regs);
        return (uintptr_t)-1;
    }

    int sig = WSTOPSIG(status);
    if (sig != SIGSEGV) {
        ALOGE("Unexpected signal after call: %d (expected SIGSEGV=11)", sig);
    }

    kail_regs_t result_regs;
    if (!ptrace_getregs(ctx.pid, &result_regs)) {
        ptrace_setregs(ctx.pid, &saved_regs);
        return (uintptr_t)-1;
    }

    uintptr_t result = KAIL_REG_X0(result_regs);

    // Restore registers
    ptrace_setregs(ctx.pid, &saved_regs);

    return result;
}

static bool inject_library(RemoteContext& ctx, const char* lib_path) {
    // Allocate remote memory for library path string
    size_t path_len = strlen(lib_path) + 1;
    size_t alloc_size = (path_len + 0xFFF) & ~0xFFF; // page align

    // mmap(addr=0, length=alloc_size, prot=PROT_READ|PROT_WRITE,
    //      flags=MAP_ANONYMOUS|MAP_PRIVATE, fd=-1, offset=0)
    uintptr_t path_addr = remote_function_call(ctx, ctx.mmap_addr, 6,
                                                (uintptr_t)0, (uintptr_t)alloc_size,
                                                (uintptr_t)(PROT_READ | PROT_WRITE),
                                                (uintptr_t)(MAP_ANONYMOUS | MAP_PRIVATE),
                                                (uintptr_t)-1, (uintptr_t)0);
    if (path_addr == 0 || path_addr == (uintptr_t)-1) {
        ALOGE("Remote mmap for path failed: 0x%lx", (unsigned long)path_addr);
        return false;
    }
    ALOGI("Remote path buffer: 0x%lx", (unsigned long)path_addr);

    // Write path string
    if (!ptrace_write(ctx.pid, path_addr, lib_path, path_len)) {
        ALOGE("Failed to write library path to remote process");
        remote_function_call(ctx, ctx.munmap_addr, 2, path_addr, alloc_size);
        return false;
    }

    // Call dlopen(path, RTLD_NOW)
    uintptr_t handle = remote_function_call(ctx, ctx.dlopen_addr, 2,
                                             path_addr, (uintptr_t)RTLD_NOW);
    ALOGI("dlopen returned: 0x%lx", (unsigned long)handle);

    if (handle == 0) {
        // Get error
        uintptr_t err_str = remote_function_call(ctx, ctx.dlerror_addr, 0);
        if (err_str != 0) {
            char err_buf[256];
            ptrace_read(ctx.pid, err_str, err_buf, sizeof(err_buf));
            ALOGE("dlopen failed: %s", err_buf);
        } else {
            ALOGE("dlopen failed with unknown error");
        }
    } else {
        // Explicitly call kail_reinit() in case constructor already fired (so reloaded)
        const char* reinit_sym = "kail_reinit";
        size_t sym_len = strlen(reinit_sym) + 1;
        uintptr_t sym_addr = remote_function_call(ctx, ctx.mmap_addr, 6,
                                                    (uintptr_t)0, (uintptr_t)((sym_len + 0xFFF) & ~0xFFF),
                                                    (uintptr_t)(PROT_READ | PROT_WRITE),
                                                    (uintptr_t)(MAP_ANONYMOUS | MAP_PRIVATE),
                                                    (uintptr_t)-1, (uintptr_t)0);
        if (sym_addr != 0 && sym_addr != (uintptr_t)-1) {
            ptrace_write(ctx.pid, sym_addr, reinit_sym, sym_len);
            uintptr_t reinit_func = remote_function_call(ctx, ctx.dlsym_addr, 2, handle, sym_addr);
            ALOGI("dlsym(kail_reinit) returned: 0x%lx", (unsigned long)reinit_func);
            if (reinit_func != 0) {
                uintptr_t reinit_result = remote_function_call(ctx, reinit_func, 0);
                ALOGI("kail_reinit() called, result=0x%lx", (unsigned long)reinit_result);
            }
            remote_function_call(ctx, ctx.munmap_addr, 2, sym_addr, (sym_len + 0xFFF) & ~0xFFF);
        }
    }

    // Cleanup path buffer
    remote_function_call(ctx, ctx.munmap_addr, 2, path_addr, alloc_size);

    return handle != 0;
}

static void print_usage(const char* prog) {
    fprintf(stderr, "Usage: %s -p <pid> -l <lib_path>\n", prog);
    fprintf(stderr, "       %s -P <process_name> -l <lib_path>\n", prog);
    fprintf(stderr, "Options:\n");
    fprintf(stderr, "  -p PID            Target process ID\n");
    fprintf(stderr, "  -P PROCESS_NAME   Target process name\n");
    fprintf(stderr, "  -l LIB_PATH       Path to library to inject\n");
    fprintf(stderr, "  -h                Show this help\n");
}

int main(int argc, char* argv[]) {
    pid_t target_pid = -1;
    const char* target_name = nullptr;
    const char* lib_path = nullptr;

    int opt;
    while ((opt = getopt(argc, argv, "p:P:l:h")) != -1) {
        switch (opt) {
            case 'p':
                target_pid = atoi(optarg);
                break;
            case 'P':
                target_name = optarg;
                break;
            case 'l':
                lib_path = optarg;
                break;
            case 'h':
            default:
                print_usage(argv[0]);
                return opt == 'h' ? 0 : 1;
        }
    }

    if (!lib_path) {
        ALOGE("Library path not specified");
        print_usage(argv[0]);
        return 1;
    }

    if (target_name) {
        target_pid = find_pid_by_name(target_name);
        if (target_pid < 0) {
            ALOGE("Process '%s' not found", target_name);
            return 1;
        }
        ALOGI("Found process '%s' with pid %d", target_name, target_pid);
    }

    if (target_pid <= 0) {
        ALOGE("Invalid target PID");
        print_usage(argv[0]);
        return 1;
    }

    // Check if we are root
    if (getuid() != 0) {
        ALOGE("This tool requires root privileges");
        return 1;
    }

    ALOGI("Injecting %s into pid %d", lib_path, target_pid);
    printf("Injecting %s into pid %d\n", lib_path, target_pid);
    fflush(stdout);

    RemoteContext ctx;
    ctx.pid = target_pid;

    if (!resolve_remote_functions(ctx)) {
        ALOGE("Failed to resolve remote functions");
        printf("Failed to resolve remote functions\n");
        fflush(stdout);
        return 1;
    }

    if (!ptrace_attach(target_pid)) {
        printf("Failed to attach to pid %d\n", target_pid);
        fflush(stdout);
        return 1;
    }

    bool success = inject_library(ctx, lib_path);

    ptrace_detach(target_pid);

    if (success) {
        ALOGI("Injection successful!");
        printf("Injection successful!\n");
        fflush(stdout);
        return 0;
    } else {
        ALOGE("Injection failed!");
        printf("Injection failed!\n");
        fflush(stdout);
        return 1;
    }
}
