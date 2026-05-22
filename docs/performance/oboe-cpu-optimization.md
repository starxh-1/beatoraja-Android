# libgdx-oboe CPU 占用优化方案

## Context

当前 beatoraja 在 Android 上的音频播放存在 CPU 占用过高的问题，特别是在低端设备上。`generate_audio()` 在 Oboe 音频回调线程（实时线程）上每帧都需要竞争 3 把 mutex（audio_player、music、executor），这是主要的性能瓶颈。

## 当前已有优化

- Low-Latency 模式已启用（oboe_engine.cpp）
- 根据设备架构调整缓冲区大小（32-bit ARM: 4x burst，64-bit: 2x burst）
- 使用 condition_variable 等待（非忙等待）
- LTO 编译优化已启用
- 音频后端自动选择（AAudio > OpenSLES）

## 优化方案

### 优化 1：移除 music::render() 中的冗余锁（高优先级）

**问题**：music.cpp:75-119 中 `render()` 获取 mutex 后读取 `frames_in_pcm`（第 83 行），但后续 `raw_render()`（第 56-73 行）本身只读 `m_main_pcm`、`m_current_frame`、`m_volume`、`m_pan`，不需要锁。多重加锁造成音频回调线程的锁竞争。

**方案**：将 `frames_in_pcm = m_main_pcm.size() / m_channels` 的读取移到锁外（`m_main_pcm.size()` 是无锁的）。只在 swap_buffers() 时加锁。

**文件**：`libgdx-oboe/library/src/cpp/audio/music.cpp`

**预期收益**：music 是主音频源，减少每帧锁竞争可显著降低 CPU。

**风险**：中。需要确保 audio_player 调用 music::render() 期间不会有其他线程调用 music::position()。当前调用链无此问题。

---

### 优化 2：设置 executor worker 线程优先级（中优先级）

**问题**：executor worker 线程（负责异步解码）使用默认优先级，低于音频回调线程。解码速度跟不上时会造成音频断续。

**方案**：在 executor 构造函数中，创建线程后调用 `setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_AUDIO)` 设置 Android 标准音频优先级。

**文件**：`libgdx-oboe/library/src/cpp/utility/executor.hpp`

**预期收益**：确保解码线程及时完成，提高 buffer 填充速度。

**风险**：低。Android 音频应用标准做法。

---

### 优化 3：track 清理延迟到 play_audio() 时（低优先级）

**问题**：audio_player::generate_audio() 每次调用都执行 `m_tracks.erase(remove_if(...))`（第 52-55 行），O(n) 复杂度，且 mutex 在音频回调线程竞争。

**方案**：将清理操作移到 play_audio() 时（仅 add 新 track 时清理）。

**文件**：`libgdx-oboe/library/src/cpp/audio/audio_player.cpp`

**预期收益**：降低每帧 generate_audio() 计算量。

**风险**：低。

---

### 优化 4：spectrum_analyzer FFT 计算节流（中优先级）

**问题**：每次 generate_audio() 都调用 `m_analyzer.feed()`（audio_player.cpp:63），FFT 计算量大（KissFFT 1024 点），但 UI 频谱更新可能不需要这么高频率。

**方案**：内部引入节流机制，每 2-3 帧实际计算一次 FFT。

**文件**：`libgdx-oboe/library/src/cpp/audio/spectrum_analyzer.hpp`

**预期收益**：减少 FFT 计算次数，降低 CPU 占用。

**风险**：低。

---

## 实现优先级

| 优先级 | 优化 | 预期收益 | 风险 |
|--------|------|----------|------|
| 1 | 移除 music 冗余锁 | 高 | 中 |
| 2 | executor 线程优先级 | 中 | 低 |
| 3 | track 清理延迟 | 低 | 低 |
| 4 | FFT 节流 | 中 | 低 |

---

## 关键文件

- `libgdx-oboe/library/src/cpp/audio/music.cpp` - 优化 1
- `libgdx-oboe/library/src/cpp/utility/executor.hpp` - 优化 2
- `libgdx-oboe/library/src/cpp/audio/audio_player.cpp` - 优化 3
- `libgdx-oboe/library/src/cpp/audio/spectrum_analyzer.hpp` - 优化 4

---

## 验证方法

1. 使用 systrace/perfetto 采集音频回调线程的 CPU 占用
2. 对比优化前后同样游戏场景的帧率稳定性和电池消耗
3. 在低端设备（如 32-bit ARM）上验证优化效果