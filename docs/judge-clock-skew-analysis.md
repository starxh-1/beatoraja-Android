# Judge / Timer Clock Skew 综合分析

> **目的**：彻查 beatoraja-Android 判定时钟偏移问题的所有根因，从时钟源、数据流、线程模型、音频同步、GC 影响五个层面拆解。
>
> **范围**：`TimerManager.java`、`BMSPlayer.java`、`JudgeManager.java`、`KeyInputProccessor.java`、`MainController.java`、`KeyBoardInputProcesseor.java`、`BMSPlayerInputProcessor.java`、`AndroidLauncher.java`。

---

## 0. 结论先行

存在 **四个独立且可叠加** 的根因导致长时间游玩后判定偏移：

| # | 根因 | 严重程度 | 1 小时后典型误差 | 触发条件 |
|---|------|----------|-----------------|----------|
| A | 音频时钟与游戏时钟物理独立，无同步 | **高** | 100–540ms | 所有设备，随时间累积 |
| B | `TIMER_PLAY` 由主线程 60Hz 累加，输入线程 1000Hz 读取（量化噪声） | **高** | 0–16ms 抖动 | 所有设备，帧率越低越严重 |
| C | `timer[]` 数组无 volatile/synchronized（JMM 不可见） | **中** | 不确定（0 到数帧延迟） | ARM 弱内存模型设备 |
| D | GC STW 暂停阻塞所有线程，造成判定断层 | **中** | 随泄漏程度增长 | 内存压力大时 |

问题 A 解释了"为什么玩一小时后偏"，问题 B/C 解释了"为什么帧率波动时偏"，问题 D 解释了"内存泄漏如何加剧偏"。

---

## 1. 时钟架构全景

### 1.1 三个时间源，角色迥异

```
                          System.nanoTime()  (CLOCK_MONOTONIC)
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
         TimerManager      InputPolling        FramePacing
         (游戏时间基准)    (1000Hz 线程)       (MainController)
              │                  │                  │
     getNowMicroTime()      now = nano/1000    nextFrameTimeNanos
     = (nano-starttime)     - starttime         (绝对时间调度)
     / 1000                       │
              │            kbinput.poll(now)
              │               │
         TIMER_PLAY        keytime[] = now
         (帧累加器)         (按键时间戳)
              │               │
         JudgeThread ─────────┘
         (独立 1000Hz 线程)
              │
     mtime = getNowMicroTime(TIMER_PLAY)
              │
     judge.update(mtime)
```

### 1.2 关键组件

| 组件 | 时钟源 | 刷新频率 | 用途 |
|------|--------|----------|------|
| `TimerManager.getNowMicroTime()` | `System.nanoTime()` | 每次调用 | 实时墙钟 (μs) |
| `TIMER_PLAY` | 主线程帧累加 | 60–120Hz | 歌曲播放位置 (μs) |
| `InputPollingThread` | `nanoTime()/1000` | 1000Hz | 按键状态轮询 |
| `JudgeThread` | `getNowMicroTime(TIMER_PLAY)` | 1000Hz | 判定计算 |
| `MainController.render()` | `System.nanoTime()` + VSync | 60–120Hz | 渲染帧调度 |
| OboeAudio (AAudio) | 音频 Codec 晶振 | 硬件驱动 | 音频播放 |

### 1.3 TIMER_PLAY 累加公式

```java
// BMSPlayer.java:767-771
final long deltatime = micronow - prevtime;                   // 帧间隔 (μs)
final long deltaplay = deltatime * (100 - playspeed) / 100;   // 变速调整
timer.setMicroTimer(TIMER_PLAY, timer.getMicroTimer(TIMER_PLAY) + deltaplay);
```

**理论正确性（playspeed=0，正常速度）**：`deltaplay = deltatime`，这是一个望远镜和——所有中间值相消，`TIMER_PLAY = 总流逝墙钟`。帧率变化不产生累积误差。

**playspeed≠0 时**：整数除法每次截断 <1μs，1 小时累积 <1ms，**不是实际问题**。

---

## 2. 根因 A：音频时钟与游戏时钟物理独立

### 2.1 两个独立晶振

| 时钟 | 物理来源 | 典型偏差 |
|------|----------|----------|
| `System.nanoTime()` | SoC 主晶振 (19.2/24MHz) | ±20–50 ppm |
| AAudio 采样时钟 | 音频 Codec 晶振 (12.288/24.576MHz) | ±20–100 ppm |
| **相对偏差（最坏情况）** | — | **150 ppm = 540ms/h** |

这两个时钟**物理上完全独立**，代码中没有任何同步机制：

```java
// BMSPlayer.java:771 — 纯软件累加，从不参考音频实际位置
timer.setMicroTimer(TIMER_PLAY, timer.getMicroTimer(TIMER_PLAY) + deltaplay);
```

### 2.2 为什么"一小时"后才明显

人的 BMS 判定感知阈值约 15–20ms。假设偏差 100ppm：

| 时间 | 累积偏差 | 体感 |
|------|---------|------|
| 10 分钟 | 60ms | 开始可感知 |
| 30 分钟 | 180ms | 明显偏移 |
| 60 分钟 | 360ms | **严重偏移，无法正常游戏** |

### 2.3 为什么不同用户体验不同

- 不同手机使用不同音频 Codec（Qualcomm WCD9385 / Cirrus Logic / 等）
- Codec 晶振精度因供应商、温度、老化程度而异
- 部分厂商在 BSP 层做音频时钟补偿，部分没有
- 环境温度变化 10°C → 晶振频率变化约 1–5 ppm

### 2.4 代码层面的证据

整个代码库中：
- `TIMER_PLAY` 仅通过帧增量累加（`BMSPlayer.java:771`）
- 没有从 OboeAudio / AAudio 读取实际播放位置的代码
- 没有音频时钟偏差检测 / 补偿逻辑
- OboeAudio 初始化时指定 44100Hz，但游戏不查询其实际输出采样率

---

## 3. 根因 B：TIMER_PLAY 跨线程读写造成的量化噪声

### 3.1 量化机制

```
时间(ms)    0    16.67  33.33  50.00  66.67  83.33  100.00
──────────────────────────────────────────────────────
主线程 (60Hz)
micronow     0   16670  33330  50000  66670  83330  100000
TIMER_PLAY   0       0      0      0      0      0       0  (playspeed=100)

输入线程 (1000Hz)
mtime #1     0
mtime #2     0       ← 跟上次一样
...
mtime #16    0       ← 整整 16 次都读到 0
mtime #17   16670     ← 主线程刚跑完 update
mtime #18   16670
...
```

输入线程每 1ms 轮询一次，但实际读到的 mtime 是 **16ms 步进的离散值**。直到主线程跑完一次 `update()` 并写入 `TIMER_PLAY`，输入线程才能看到新值。

### 3.2 误差量化

| 帧率 | 帧时长 | mtime 误差范围 | 平均误差 |
|------|--------|---------------|----------|
| 120fps | 8.3ms | 0–8.3ms | 4.2ms |
| 60fps | 16.7ms | 0–16.7ms | 8.3ms |
| 30fps | 33.3ms | 0–33.3ms | 16.7ms |

**帧率越低，误差越大**。这就是"帧率波动 → 判定偏移"的物理来源。

### 3.3 对判定的影响

PGREAT 窗口（默认 ±15ms）：

```
                 ┌─ PGREAT: ±15ms ─┐
  ───────────────┼─────────────────┼───────────────
            -15ms│      note       │+15ms
                 └─────────────────┘
```

如果按键真实偏差为 14.5ms（应在 PGREAT 窗口内）：
- mtime 比墙钟晚 1ms → 报告偏差 15.5ms → **被判为 GREAT**
- mtime 比墙钟晚 0ms → 报告偏差 14.5ms → **PGREAT**

**同一次按键，因 mtime 的报告时差，可能被判成不同等级**。

### 3.4 帧率不稳时的复合效应

```
主线程 update 周期    输入线程读到的 mtime
────────────────     ────────────────────
正常 16ms:           0  0  0 ... 0  16  16  16 ... 16  33  33 ...  (16ms步进)
GC 卡顿 200ms:       ... 16 16 16 16 16 16 16 216 216 216 ...     (跳变200ms)
帧率下降到30fps:      10 10 10 10 10 30 30 30 30 30 30 50 50 ...   (步进不稳)
```

卡顿时，`TIMER_PLAY` 停止累加，输入线程读到停滞的旧值；卡顿结束后一次性跳变，造成判定断层。

---

## 4. 根因 C：timer[] 数组的 JMM 不可见性

### 4.1 问题所在

```java
// TimerManager.java:18-19
private final long[] timer = new long[timerCount];  // 普通 long[]，无任何同步

// TimerManager.java:44-46 — 写
public long getNowMicroTime() {
    return (System.nanoTime() - starttime) / 1000;
}

// TimerManager.java:79-85 — 写 timer
public void setMicroTimer(int id, long microtime) {
    timer[id] = microtime;  // 无 volatile/synchronized
}

// TimerManager.java:59-64 — 读 timer
public long getMicroTimer(int id) {
    return timer[id];  // 无 volatile/synchronized
}
```

**写线程** (主线程 60Hz) 对 `timer[]` 的写入，**输入线程** (1000Hz) 读取时：
- ARM 弱内存模型下：可能读到**陈旧值**（CPU 缓存未失效）
- 32-bit ART 上：可能读到**撕裂值**（高 32 位旧 + 低 32 位新）
- 没有任何 happens-before 关系保证可见性

### 4.2 实际影响

```
时刻    主线程 (60Hz)              输入线程 (1000Hz)
t=0     [写 timer[PLAY] = 0]       [读 timer[PLAY]] → 拿到 0 ✅
t=16ms  [写 timer[PLAY] = 16670]   
t=17ms                             [读 timer[PLAY]] → 可能还是 0（陈旧）
t=18ms                             [读 timer[PLAY]] → 可能还是 0
...
t=33ms                             [读 timer[PLAY]] → 终于看到 16670
```

在 ARM 设备上，跨线程无 happens-before 时，读线程**可任意延迟才看到新值**。这会使根因 B 的误差进一步扩大（从 ≤16ms 变成可能延迟数帧才更新）。

### 4.3 撕裂风险（32-bit 设备）

```java
// TimerManager.java:48-53 — 典型的读-算-用模式
public long getNowMicroTime(int id) {
    if(isTimerOn(id)) {
        return getNowMicroTime() - getMicroTimer(id);  // 若 getMicroTimer 返回撕裂值
    }
    return 0;
}
```

撕裂值 = `0x0000000011111111`（高 32 旧，低 32 新）→ 减墙钟得出一个极大或极小的错乱值 → 上层所有依赖 mtime 的逻辑（判定、回放、计时）全部受影响。

---

## 5. 根因 D：GC STW 暂停阻断所有线程

### 5.1 机制

Android ART 的 GC 触发 STW (Stop-The-World) 暂停：
- Minor GC：通常 <10ms，频率高
- Major GC：可达 50–200ms，频率随堆增长而增加

如果存在内存泄漏，GC 频率随运行时间递增：
- 刚启动：每 30 秒一次 minor GC
- 1 小时后：可能每 5–10 秒一次 major GC（堆接近上限时）

### 5.2 STW 期间的影响

```
GC STW 开始（所有线程暂停）：
  ├── JudgeThread 暂停 → 无法更新判定 → 暂停期间的 note 可能被漏判
  ├── InputPolling 暂停 → 按键事件积压 → 恢复后集中处理
  ├── RenderThread 暂停 → 帧渲染停滞 → 视觉卡顿
  └── 音频硬件**不停止**（独立 DMA）→ 音乐继续播放

GC STW 结束（所有线程恢复）：
  ├── TIMER_PLAY 恢复正常累加（基于实时墙钟，自动补偿）
  ├── 但音频在暂停期间已播放了 200ms → "现在"对应的歌曲位置错了
  └── 判定窗口相对于音频偏移了 200ms（直到下一次音频同步）
```

### 5.3 关键观察指标

从 Logcat 可观察到：
1. `入力パフォーマンス(max ms)` — 如果从正常的 1–2ms 增长到 10ms+，说明 JudgeThread 被阻塞
2. `current free memory` — state transition 时输出，持续下降说明泄漏
3. `This is sticky GC` — 频率增加说明 GC 压力增大

### 5.4 代码层面的证据

BMSPlayer.java 中有注释表明之前调用过 `System.gc()`：

```java
// BMSPlayer.java:666-667
// 不再调用 System.gc()：强制 GC 之前/之后对比 freeMemory 会把软引用、终结器队列对象
// 一起回收,得到的 "disposed" 数字虚高,污染了真实内存观测。
```

这说明**该代码库历史上确实有 GC 相关问题**。

---

## 6. 根因层级关系与叠加效应

```
┌─────────────────────────────────────────────────┐
│                根因 A：音频时钟异步                │
│    (长时间累积偏差，1小时后 100–540ms)            │
│  ┌───────────────────────────────────────────┐  │
│  │        根因 B：量化噪声 (0–16ms 抖动)       │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │   根因 C：JMM 不可见 (额外延迟)      │  │  │
│  │  │  ┌───────────────────────────────┐  │  │
│  │  │  │  根因 D：GC 暂停 (偶发跳变)   │  │  │
│  │  │  │                               │  │  │
│  │  │  └───────────────────────────────┘  │  │
│  │  └─────────────────────────────────────┘  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

四个根因**互相独立且可叠加**。最坏情况下：音频时钟偏差 200ms + 帧率下降到 30fps (16ms 量化) + JMM 陈旧读取 (额外 33ms) + GC 跳跃 200ms = **近 450ms 的判定偏移**。

---

## 7. 改进方案

### 7.1 方案 A（高优先级）：修复音频时钟同步

**核心思路**：定期从 OboeAudio 获取实际播放位置，用平滑算法校正 `TIMER_PLAY`。

```java
// 伪代码 — 在 JudgeThread 或独立线程中执行
void syncAudioClock() {
    // OboeAudio 提供当前播放帧位置
    long audioFrames = oboeAudio.getPlaybackHeadPosition();
    long audioTimeUs = audioFrames * 1_000_000L / sampleRate;

    long gameTimeUs = timer.getMicroTimer(TIMER_PLAY);
    long drift = audioTimeUs - gameTimeUs;

    if (Math.abs(drift) > 1000) {  // 偏差超过 1ms
        // EMA 平滑：90% 保留旧值 + 10% 新偏差
        smoothedDrift = (smoothedDrift * 9 + drift) / 10;
        // 缓慢校正（每次最多修正 500μs），避免突变
        long correction = Math.min(Math.abs(smoothedDrift), 500) * Math.signum(smoothedDrift);
        timer.setMicroTimer(TIMER_PLAY, timer.getMicroTimer(TIMER_PLAY) + correction);
        smoothedDrift -= correction;
    }
}
```

**风险**：需要 `OboeAudio` 暴露 `getPlaybackHeadPosition()` 接口。

### 7.2 方案 B（高优先级）：消除量化噪声

**核心思路**：把"写 TIMER_PLAY"的职责从主线程挪到 JudgeThread（输入线程），降步进从 16ms 到 1ms。

```java
// JudgeThread 内（单写者模式）
while (!stop) {
    long now = player.timer.getNowMicroTime();     // 墙钟
    long dt = now - prevInputTime;                  // 真实流逝
    long deltaPlay = dt * (100 - localPlaySpeed) / 100;
    // 单写者：读写都在本线程，无竞争
    player.timer.setMicroTimer(TIMER_PLAY,
        player.timer.getMicroTimer(TIMER_PLAY) + deltaPlay);
    long mtime = player.timer.getNowMicroTime(TIMER_PLAY);
    judge.update(mtime);

    prevInputTime = now;
    // sleep 1ms...
}
```

改进效果：

| 指标 | 修复前 | 修复后 |
|------|--------|--------|
| mtime 步进 | 16ms (60fps) | 1ms |
| 平均量化误差 | 8ms | 0.5ms |
| 跨线程可见性需求 | 写者=主线程，读者=输入线程 | **写者=输入线程自己** |

需同步处理：playspeed 用 `volatile int` 传递（主线程改，输入线程读，最多延迟 1ms）。

### 7.3 方案 C（中优先级）：修复 JMM 可见性

最简单的方式：将 `timer[]` 改为 `AtomicLongArray`，或将 `setMicroTimer`/`getMicroTimer` 用 `synchronized` 包裹。

如果实施了方案 B（单写者），则可进一步简化为仅对写操作使用 `volatile` 语义即可。

### 7.4 方案 D（中优先级）：减少 GC 压力

1. **对象池复用**：`KeyLogger.logpool` 中 `KeyInputLog` 对象在每次 `clear()` 后未复用
2. **审查每帧分配**：BMSPlayer.render() 和 JudgeManager.update() 中是否有临时对象
3. **添加 GC 监控**：定期记录 `Runtime.getRuntime().freeMemory()` 和 `totalMemory()`，检测泄漏趋势

### 7.5 方案 E（低优先级）：监控 + 诊断

在 JudgeThread 中添加定期日志：

```java
// 每 30 秒输出一次
if (System.nanoTime() - lastLogTime > 30_000_000_000L) {
    Log.i("ClockDiag", String.format(
        "gameTime=%d audioPos=%d drift=%d maxSleep=%d",
        mtime, getAudioPosition(), drift, maxFrameTime));
    lastLogTime = System.nanoTime();
}
```

---

## 8. 相关文件 & 行号速查

| 文件 | 关键行号 | 角色 |
|------|---------|------|
| `core/.../TimerManager.java:18` | `long[] timer` | 跨线程共享的 timer 数组，**无同步** |
| `core/.../TimerManager.java:44-46` | `getNowMicroTime()` | 墙钟 (μs)，不依赖帧率 |
| `core/.../TimerManager.java:48-53` | `getNowMicroTime(int id)` | 墙钟 − timer[id] |
| `core/.../TimerManager.java:79-85` | `setMicroTimer()` | 写 timer 数组 |
| `core/.../play/BMSPlayer.java:87` | `playspeed = 100` | 速度倍率 |
| `core/.../play/BMSPlayer.java:108` | `prevtime` | 主线程上一帧的墙钟 |
| `core/.../play/BMSPlayer.java:767-771` | STATE_PLAY | **TIMER_PLAY 累加（方案 B 删这里）** |
| `core/.../play/BMSPlayer.java:951` | `prevtime = micronow` | 主线程帧尾更新 |
| `core/.../play/KeyInputProccessor.java:158-203` | JudgeThread | 1000Hz 判定线程 |
| `core/.../play/KeyInputProccessor.java:173` | `mtime = getNowMicroTime(TIMER_PLAY)` | **跨线程读 timer** |
| `core/.../play/JudgeManager.java:233` | `update(mtime)` | 判定核心逻辑 |
| `core/.../play/JudgeManager.java:250-251` | `note.getMicroTime() <= mtime` | 判定窗口判定 |
| `core/.../MainController.java:574-592` | inputPollingThread | 1000Hz 输入轮询线程 |
| `core/.../MainController.java:985-1029` | frame pacing | 帧率控制逻辑 |
| `core/.../input/BMSPlayerInputProcessor.java:623-634` | `poll()` | 输入统一轮询入口 |
| `core/.../input/KeyBoardInputProcesseor.java:150-227` | `poll(microtime)` | 键盘轮询 + 时间戳记录 |
| `android/.../AndroidLauncher.java:68-81` | `createAudio()` | OboeAudio 初始化 |

---

## 附录：术语表

- **墙钟** (wall clock)：实际流逝的物理时间，`System.nanoTime()` 读出
- **量化噪声** (quantization noise)：连续时间离散化产生的步进级误差
- **JMM** (Java Memory Model)：Java 内存模型，定义多线程可见性/原子性/有序性
- **happens-before**：JMM 核心关系，写 happens-before 读时读一定能看到写后的值
- **STW** (Stop-The-World)：GC 暂停所有应用线程的阶段
- **ppm** (parts per million)：百万分率，1ppm = 每百万秒偏差 1 秒，即每小时 3.6ms
