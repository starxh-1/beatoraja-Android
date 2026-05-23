# Fix `__register_atfork` Symbol Not Found on Android 5.1 (API 21/22)

## Problem

在 Android 5.1 设备上启动应用时，`liblibgdx-oboe.so` 加载失败：

```
dlopen("/data/app/.../lib/arm/liblibgdx-oboe.so", RTLD_LAZY) failed:
  dlopen failed: cannot locate symbol "__register_atfork" referenced by "libavutil.so"...
```

## Root Cause

`__register_atfork` 是 glibc 的符号，Android 的 Bionic libc 在 API 23+ 才引入。`libavutil.so`（预编译 FFmpeg 库之一）引用了此符号，但 Android 5.1 (API 21) 的 `libc.so` 中没有该符号，导致 `dlopen` 失败。

## Solution

### 方案：atfork_shim 作为 libgdx-oboe 的 NEEDED 依赖（已实施）

将 `__register_atfork` stub 编译为独立的 `libatfork_shim.so`，并将其设为 `liblibgdx-oboe.so` 的第一个 NEEDED 依赖（排在 FFmpeg 库之前）。当 `dlopen("libgdx-oboe")` 时，加载器先加载 `libatfork_shim.so`，然后 FFmpeg 的库在解析 `__register_atfork` 时就能找到它。

#### 关键原理

- `atfork_shim` 作为 NEEDED 依赖，在同一个 `dlopen` 调用内加载
- 同一加载组（local group）内的所有库共享符号解析空间
- 之前方案失败的原因：`System.loadLibrary` 使用 `RTLD_LOCAL`，独立的加载调用看不到彼此的符号

#### 改动文件

| 文件 | 改动 |
|------|------|
| `libgdx-oboe/library/CMakeLists.txt` | 添加独立的 `atfork_shim` 库目标；在 `target_link_libraries` 中作为第一个依赖 |
| `libgdx-oboe/library/src/cpp/utility/atfork_shim.cpp` | 纯 stub，仅导出 `__register_atfork` 返回 0 |
| `libgdx-oboe/library/src/cpp/native/init.cpp` | 添加 `__force_atfork_shim_ref()` 空引用来防止 `--as-needed` 丢弃依赖 |
| `libgdx-oboe/library/src/kotlin/.../OboeAudio.kt` | 仅 `loadLibrary("libgdx-oboe")`，不单独加载 shim |

#### 验证

```
# libgdx-oboe 的 NEEDED 列表（atfork_shim 在第一位）
$ llvm-readelf -d liblibgdx-oboe.so | grep NEEDED
  NEEDED  libatfork_shim.so     ← 第一个！
  NEEDED  libavformat.so
  NEEDED  libavcodec.so
  NEEDED  libavutil.so          ← 这个需要 __register_atfork
  ...

# libgdx-oboe 引用了 __register_atfork（UND）
$ llvm-readelf -s liblibgdx-oboe.so | grep register_atfork
  60: 0000000000000000     0 FUNC    GLOBAL DEFAULT   UND __register_atfork

# atfork_shim 定义了 __register_atfork
$ llvm-readelf -s libatfork_shim.so | grep register_atfork
   3: 0000000000000588     8 FUNC    GLOBAL DEFAULT    13 __register_atfork
```

### 备选：重建 FFmpeg

如果以上方案仍不行，需要在有 autoconf/make 的 Linux/macOS 环境上重建 FFmpeg：

```bash
cd libgdx-oboe/library
NDK_DIR=/path/to/android-ndk-29.0.13599879 ./build_ffmpeg.sh
```

`build_ffmpeg.sh` 已修改为：
- `TOOLCHAIN_VERSION=${MIN_SDK_VERSION}`（21），编译器直接目标 API 21
- `-D__ANDROID_API__=21` 传递给 CFLAGS
