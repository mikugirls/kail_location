#include "ptrace_utils.h"
#include <fcntl.h>
#include <dirent.h>

// NT_PRSTATUS is needed for PTRACE_GETREGSET on ARM64
#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

namespace kail {

bool find_module_base(pid_t pid, const char* module_name, ModuleInfo* info) {
    char maps_path[128];
    if (pid == -1) {
        snprintf(maps_path, sizeof(maps_path), "/proc/self/maps");
    } else {
        snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    }

    FILE* fp = fopen(maps_path, "r");
    if (!fp) {
        ALOGE("Failed to open %s", maps_path);
        return false;
    }

    char line[1024];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, module_name)) {
            uintptr_t start, end;
            char perm[5];
            char path[256] = {0};
            if (sscanf(line, "%zx-%zx %4s %*s %*s %*s %255s", &start, &end, perm, path) >= 3) {
                if (perm[0] == 'r' && perm[2] == 'x') { // readable and executable
                    if (path[0] == '/' && strstr(path, module_name)) {
                        info->base = start;
                        info->size = end - start;
                        strncpy(info->name, path, sizeof(info->name) - 1);
                        info->name[sizeof(info->name) - 1] = '\0';
                        fclose(fp);
                        return true;
                    }
                    if (info->base == 0) {
                        info->base = start;
                        info->size = end - start;
                        strncpy(info->name, path, sizeof(info->name) - 1);
                        info->name[sizeof(info->name) - 1] = '\0';
                    }
                }
            }
        }
    }

    fclose(fp);
    return info->base != 0;
}

uintptr_t find_local_module_base(const char* module_name) {
    ModuleInfo info = {};
    if (find_module_base(-1, module_name, &info)) {
        return info.base;
    }
    return 0;
}

long kail_ptrace(int request, pid_t pid, void* addr, void* data) {
    long ret;
    int retry = 10;
    while (retry-- > 0) {
        ret = ptrace(request, pid, addr, data);
        if (ret != -1 || errno != EINTR) {
            break;
        }
        usleep(1000);
    }
    return ret;
}

bool ptrace_attach(pid_t pid) {
    if (kail_ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) == -1) {
        ALOGE("PTRACE_ATTACH failed: %s", strerror(errno));
        return false;
    }

    int status;
    pid_t waited = waitpid(pid, &status, WUNTRACED);
    if (waited != pid) {
        ALOGE("waitpid failed after attach: %s", strerror(errno));
        return false;
    }

    if (!WIFSTOPPED(status)) {
        ALOGE("Process not stopped after attach, status=%d", status);
        return false;
    }

    ALOGI("Attached to pid %d", pid);
    return true;
}

bool ptrace_detach(pid_t pid) {
    if (kail_ptrace(PTRACE_DETACH, pid, nullptr, nullptr) == -1) {
        ALOGE("PTRACE_DETACH failed: %s", strerror(errno));
        return false;
    }
    ALOGI("Detached from pid %d", pid);
    return true;
}

bool ptrace_getregs(pid_t pid, kail_regs_t* regs) {
#ifdef KAIL_ARCH_64
    struct iovec ioVec;
    ioVec.iov_base = regs;
    ioVec.iov_len = sizeof(*regs);
    if (kail_ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &ioVec) == -1) {
        ALOGE("PTRACE_GETREGSET failed: %s", strerror(errno));
        return false;
    }
#else
    if (kail_ptrace(PTRACE_GETREGS, pid, nullptr, regs) == -1) {
        ALOGE("PTRACE_GETREGS failed: %s", strerror(errno));
        return false;
    }
#endif
    return true;
}

bool ptrace_setregs(pid_t pid, const kail_regs_t* regs) {
#ifdef KAIL_ARCH_64
    struct iovec ioVec;
    ioVec.iov_base = (void*)regs;
    ioVec.iov_len = sizeof(*regs);
    if (kail_ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &ioVec) == -1) {
        ALOGE("PTRACE_SETREGSET failed: %s", strerror(errno));
        return false;
    }
#else
    if (kail_ptrace(PTRACE_SETREGS, pid, nullptr, (void*)regs) == -1) {
        ALOGE("PTRACE_SETREGS failed: %s", strerror(errno));
        return false;
    }
#endif
    return true;
}

bool ptrace_read(pid_t pid, uintptr_t addr, void* buf, size_t size) {
    uint8_t* dst = (uint8_t*)buf;
    size_t read = 0;
    while (read < size) {
        size_t chunk = sizeof(long);
        if (read + chunk > size) chunk = size - read;

        long data = kail_ptrace(PTRACE_PEEKDATA, pid, (void*)(addr + read), nullptr);
        if (data == -1 && errno != 0) {
            ALOGE("PTRACE_PEEKDATA failed at 0x%lx: %s", (unsigned long)(addr + read), strerror(errno));
            return false;
        }

        memcpy(dst + read, &data, chunk);
        read += chunk;
    }
    return true;
}

bool ptrace_write(pid_t pid, uintptr_t addr, const void* buf, size_t size) {
    const uint8_t* src = (const uint8_t*)buf;
    size_t written = 0;
    while (written < size) {
        size_t chunk = sizeof(long);
        if (written + chunk > size) {
            long existing = kail_ptrace(PTRACE_PEEKDATA, pid, (void*)(addr + written), nullptr);
            if (existing == -1 && errno != 0) {
                ALOGE("PTRACE_PEEKDATA failed at 0x%lx: %s", (unsigned long)(addr + written), strerror(errno));
                return false;
            }
            memcpy(&existing, src + written, size - written);
            long data = kail_ptrace(PTRACE_POKEDATA, pid, (void*)(addr + written), (void*)existing);
            if (data == -1 && errno != 0) {
                ALOGE("PTRACE_POKEDATA failed at 0x%lx: %s", (unsigned long)(addr + written), strerror(errno));
                return false;
            }
            break;
        }

        long val;
        memcpy(&val, src + written, chunk);
        if (kail_ptrace(PTRACE_POKEDATA, pid, (void*)(addr + written), (void*)val) == -1) {
            ALOGE("PTRACE_POKEDATA failed at 0x%lx: %s", (unsigned long)(addr + written), strerror(errno));
            return false;
        }
        written += chunk;
    }
    return true;
}

uintptr_t find_symbol(const char* module_name, const char* symbol_name) {
    void* handle = dlopen(module_name, RTLD_NOW);
    if (!handle) {
        ModuleInfo info;
        if (find_module_base(-1, module_name, &info)) {
            handle = dlopen(info.name, RTLD_NOW);
        }
    }
    if (!handle) {
        // Android 10+ namespace isolation may block dlopen(linker64).
        // Fallback: dlsym from RTLD_DEFAULT works for symbols already
        // exported by libc.so / linker in our own process.
        void* sym = dlsym(RTLD_DEFAULT, symbol_name);
        if (sym) {
            ALOGI("find_symbol(%s, %s) via RTLD_DEFAULT = %p", module_name, symbol_name, sym);
            return (uintptr_t)sym;
        }
        ALOGE("dlopen(%s) failed and dlsym(RTLD_DEFAULT,%s) also failed: %s", module_name, symbol_name, dlerror());
        return 0;
    }
    void* sym = dlsym(handle, symbol_name);
    dlclose(handle);
    return (uintptr_t)sym;
}

pid_t find_pid_by_name(const char* process_name) {
    DIR* dir = opendir("/proc");
    if (!dir) {
        return -1;
    }

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_type != DT_DIR) continue;
        pid_t pid = atoi(entry->d_name);
        if (pid <= 0) continue;

        char cmdline_path[128];
        snprintf(cmdline_path, sizeof(cmdline_path), "/proc/%d/cmdline", pid);
        int fd = open(cmdline_path, O_RDONLY);
        if (fd < 0) continue;

        char cmdline[256];
        ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
        close(fd);
        if (n <= 0) continue;
        cmdline[n] = '\0';

        if (strcmp(cmdline, process_name) == 0) {
            closedir(dir);
            return pid;
        }
    }

    closedir(dir);
    return -1;
}

} // namespace kail
