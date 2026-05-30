# do/complete/cpp — 还原后的可编译源码

本目录是把 `do/complete/*.c`（IDA Hex-Rays 反编译 so 得到的伪代码）还原成
**可编译的 C++ 源码**。所有文件都已用项目自带的 Android NDK clang
（`ndk/27.0.12077973`，`aarch64-linux-android24` / `armv7a-linux-androideabi24`）
通过编译与链接验证。

## 文件清单

| 还原源码 | 对应反编译文件 | 产物类型 | 说明 |
| --- | --- | --- | --- |
| `inject64.cpp` | `inject64.c` | 可执行 (`kail_inject`) | 64 位 ptrace 注入器 |
| `inject.cpp` | `inject.c` | 可执行 (`kail_inject_32`) | 32 位 ptrace 注入器 |
| `fakeloc_common.h` | （三个 loader 共用部分） | 头文件 | MD5、APK 校验、签名校验、JNI 辅助 |
| `libfakeloc_apphook.cpp` | `libfakeloc_apphook.c(64)` | `.so`，导出 `doRun` | app 进程 hook 入口 |
| `libfakeloc_init.cpp` | `libfakeloc_init..c` | `.so`，导出 `doRun` | 普通 app init 入口 |
| `libfakeloc_initzygote.cpp` | `libfakeloc_initzygote.c(64)` | `.so`，导出 `doRun` | zygote init 入口 |
| `elf_hooker.h` | `ElfReader`/`ElfHooker` 类 | 头文件 | PLT/GOT inline hook 引擎（arm64 + arm 双 ABI） |
| `liblhooker.cpp` | `liblhooker64.c` + `liblhooker.c` | `.so`，导出 `Java_..._LHooker_*` | ART Method Hook 引擎（双 ABI） |
| `libStepSensor.cpp` | `libStepSensor.c` | `.so`，导出 `Java_..._LStepSensor_*` | 计步/传感器伪造 |
| `libantidetect.cpp` | `libantidetect64.c` + `libantidetect.c` | `.so`，导出 `Java_..._LAntiDetect_*` | libc 文件调用反检测（双 ABI） |

## 验证命令

```bash
NDK=$ANDROID_SDK/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
CXX64=$NDK/aarch64-linux-android24-clang++
CXX32=$NDK/armv7a-linux-androideabi24-clang++

# 注入器（可执行）
$CXX64 -std=c++17 -Wall inject64.cpp -o kail_inject   -ldl -llog
$CXX32 -std=c++17 -Wall inject.cpp   -o kail_inject32 -ldl -llog

# loader（双 ABI）
for f in libfakeloc_apphook libfakeloc_init libfakeloc_initzygote; do
  $CXX64 -std=c++17 -Wall -shared -fPIC $f.cpp -o $f.arm64.so -llog
  $CXX32 -std=c++17 -Wall -shared -fPIC $f.cpp -o $f.arm32.so -llog
done

# hook 引擎（双 ABI）
for f in liblhooker libStepSensor libantidetect; do
  $CXX64 -std=c++17 -Wall -shared -fPIC $f.cpp -o $f.arm64.so -llog
  $CXX32 -std=c++17 -Wall -shared -fPIC $f.cpp -o $f.arm32.so -llog
done
```

## 还原说明与注意事项

1. **JNI 调用方式**：反编译里大量出现 `(*(...)(*(_DWORD *)a1 + 偏移))(a1, ...)`
   这种按 vtable 偏移直接调用 `JNIEnv` 的写法，已全部还原成标准 JNI API
   （`FindClass` / `GetMethodID` / `CallObjectMethod` 等），语义等价、更可读。
2. **MD5**：反编译里是手写 NEON 向量化版本，已还原为标准 RFC 1321 算法，
   产生的摘要一致。
3. **`elf_hooker.h`**：用标准 `<elf.h>` 的 `Elf32_*` / `Elf64_*` 结构替代了反编译里的
   裸偏移运算，算法（ELF/GNU hash 查表、重定位改写、`mprotect` + 清指令缓存）保持一致。
   该头文件是 **ABI 中立** 的：编译期按位宽自动选择 `Elf64`/RELA/`R_AARCH64_*`
   （arm64）或 `Elf32`/REL/`R_ARM_*`（arm），同一份源码可编出两个 ABI。
   `libStepSensor` / `liblhooker` / `libantidetect` 现已 **arm64 + armeabi-v7a 双 ABI**
   全部还原，与 `app/src/main/cpp/CMakeLists.txt` 的 `abiFilters` 一致。
4. **`liblhooker.cpp`**：从 64 位 `liblhooker64.c` 与 32 位 `liblhooker.c` 合并还原。
   ART `ArtMethod` 的字段偏移按 Android API 21–33、两个 ABI 分别配置，trampoline
   为对应架构的原始机器码模板（arm64：`ldr x0/ldr x16/br x16`；arm：`ldr r0/ldr pc`）。
   **ART 内部布局对版本极其敏感**，跨版本使用前需对照目标 ROM 校验偏移，否则可能导致
   system_server / app 崩溃。
5. **`签名 / MD5 常量`**：`fakeloc_common.h` 中的发布签名 DER 串与
   `do/complete/*.c` 中的字面量一致（仅做了换行整理）。
6. 反编译产物中那两个超大文件（`libantidetect64.c` 3.3 万行、`libantidetect.c`
   1.5 万行）里约 99% 是静态链接进去的 **libc++abi / C++ 运行时**（name demangler、
   ARM/AArch64 栈展开、异常处理）。这些由工具链自动提供，**不属于原始业务源码**，
   因此未纳入还原范围；本目录只还原了项目自身的业务逻辑。
