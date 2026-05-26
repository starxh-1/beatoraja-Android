# beatoraja Android 输入与音频架构重构方案

## 一、问题诊断

### 1.1 输入采样率受限于帧率

当前架构的核心问题链：

```
MainController.render()  [每帧调用，60fps = 16.67ms间隔]
  └→ timer.update()       [nowmicrotime 在此更新，每帧仅一次]
       └→ BMSPlayer.render()
            └→ timer.setMicroTimer(TIMER_PLAY, ...)  [PLAY计时器也在此推进]
                 └→ current.input()
                      └→ BMSPlayer.input()
                           └→ control.input()
                           └→ keyinput.input()   [仅更新键位光束UI状态]
```

**JudgeThread 看似独立运行（~1ms 循环），实则严重依赖渲染循环：**

```java
// JudgeThread.run() - KeyInputProccessor.java:170
final long mtime = player.timer.getNowMicroTime(TIMER_PLAY);
if (mtime != prevtime) {
    judge.update(mtime);  // 仅在 mtime 变化时执行
} else {
    sleep(0, 500000);     // mtime 未变化→睡眠等待下一帧
}
```

`timer.getNowMicroTime(TIMER_PLAY)` 返回 `nowmicrotime - TIMER_PLAY_start`，两者都只在 `render()` 中更新。因此 **mtime 每 16.67ms 才变化一次**，JudgeThread 的实际有效工作频率 = 帧率。

**结论：输入检测、判定处理、按键音触发全部锁死在帧率上。60fps = 仅 60Hz 输入采样率 = 16.67ms 输入延迟。**

### 1.2 Autoplay 同样受影响

Autoplay 的 note 自动演奏逻辑在 `JudgeManager.update()` 中，通过 JudgeThread 调用。同样受限于帧率推进的 mtime。

### 1.3 音频延迟链

当前的"按键→出声"路径：

```
触摸/按键事件
  → libGDX InputProcessor (渲染线程分发)
    → BMSPlayerInputProcessor.keystate[] 更新
      → JudgeThread 检测 mtime 变化（等待渲染循环）
        → JudgeManager.update(mtime)
          → keysound.play(note, ...)
            → AbstractAudioDriver.play()
              → OboeSound.play()
                → C++ soundpool::play()  [lock-free pending list → Oboe callback]
```

Oboe 引擎本身已经做到低延迟（AAudio 独占模式，spinlock 无锁管线），但 **触发时机被渲染循环卡住**。

### 1.4 触摸事件的额外问题

`PlayTouchKeyMapper` 的 `touchDown/touchUp` 通过 libGDX 的 `InputProcessor` 接口回调，libGDX 在渲染线程上分发这些事件。Android 系统的触摸事件本身可能有 120Hz+ 的采样率，但到达游戏逻辑时已被限制在帧率。

---

## 二、重构目标

| 目标 | 当前状态 | 目标状态 |
|------|---------|---------|
| 输入采样率 | 60Hz（跟随帧率） | 1000Hz（可配置 2000/4000Hz） |
| 输入→判定延迟 | ≤16.67ms | ≤1ms |
| 按键→出声延迟 | ≤16.67ms + 音频缓冲 | ≤音频缓冲（~3-5ms） |
| Autoplay 精度 | 16.67ms 步进 | ≤1ms |
| 输入与渲染解耦 | 完全耦合 | 完全独立线程 |

---

## 三、架构重构方案

### 3.1 核心思路：时间源解放

**将 `TimerManager.nowmicrotime` 从帧更新改为实时读取：**

```java
// 当前（帧率绑定）
public void update() {
    nowmicrotime = (System.nanoTime() - starttime) / 1000;
}

// 改为（实时）
public long getNowMicroTime() {
    return (System.nanoTime() - starttime) / 1000;
}
```

所有通过 `timer.update()` 缓存的 `nowmicrotime` 改为每次调用 `getNowMicroTime()` 时实时计算。这消除了帧率对时间精度的限制。

### 3.2 高精度输入线程（替换现有 Polling Thread）

**文件：** `core/src/main/java/bms/player/beatoraja/input/HighPrecisionInputPoller.java`（新增）

```
┌─────────────────────────────────────────────────┐
│        HighPrecisionInputPoller                  │
│        (独立高优先级线程)                          │
│                                                  │
│  while(running) {                                │
│    now = System.nanoTime();                      │
│                                                  │
│    // 1. 直接读取所有输入设备状态                    │
│    keyboard.poll(now);     // Gdx.input.isKeyPressed() │
│    controllers[].poll(now); // 手柄轴/按键状态        │
│    touchState.update();    // 见下文触摸方案          │
│                                                  │
│    // 2. 直接写入 keystate[] + 时间戳               │
│    inputProcessor.updateKeyStates(now);           │
│                                                  │
│    // 3. 直接触发判定+音频（跳过 UI）                │
│    if (gameState == PLAYING) {                    │
│      judgeManager.updateRealTime(now);            │
│    }                                             │
│                                                  │
│    // 4. 精确睡眠到下一周期                         │
│    sleepPrecision(now, pollIntervalNs);           │
│  }                                               │
└─────────────────────────────────────────────────┘
```

**关键设计：**
- 线程优先级设为 `THREAD_PRIORITY_URGENT_AUDIO` 或 `THREAD_PRIORITY_URGENT_DISPLAY`
- 使用 `LockSupport.parkNanos()` 实现亚毫秒级精确休眠
- 默认轮询间隔：1000μs（1000Hz），可通过 Config 配置为 500μs（2000Hz）或 250μs（4000Hz）
- 输入状态变化的时间戳使用 `System.nanoTime()` 精度（纳秒）

### 3.3 触摸输入独立采集

Android 触摸事件通过 libGDX 的 `InputProcessor` 分发在渲染线程，需要绕过此限制：

**方案：** 在 `AndroidLauncher` 中覆盖 `onTouchEvent()`，将触摸事件同时发送到高精度输入线程的锁-free 队列中。

```java
// AndroidLauncher.java
@Override
public boolean onTouchEvent(MotionEvent event) {
    // 原始事件直接转发到高精度输入线程
    highPrecisionPoller.onTouchEvent(event);
    // 同时保留 libGDX 原有处理（UI 交互）
    return super.onTouchEvent(event);
}
```

`HighPrecisionInputPoller` 内部维护一个 `ConcurrentLinkedQueue<MotionEvent>` 或使用 ring buffer，在每次轮询时消费队列中的触摸事件，以纳秒精度记录按下/释放时间。

### 3.4 JudgeManager 实时化

**关键改动：`judge.update(mtime)` 不再依赖帧率推进的 mtime**

```java
// 当前：mtime 来自 timer.getNowMicroTime(TIMER_PLAY)，帧率步进
// 改为：mtime 直接从高精度输入线程传入，实时时间
public void updateRealTime(final long realTimeMicro) {
    // realTimeMicro 是当前真实的播放时间（微秒）
    // 不再受帧率限制
    // 其余判定逻辑不变
}
```

`TIMER_PLAY` 的开始时间在 `STATE_READY→STATE_PLAY` 转换时记录一次（使用 `System.nanoTime()`），此后所有"当前播放时间"均由 `(System.nanoTime() - playStartNano) / 1000` 实时计算。

### 3.5 音频直接触发（跳过 UI）

当前音频触发路径中，`JudgeManager.update()` 直接调用 `keysound.play()`，这已经基本不经过 UI。重构后的改进：

```
高精度输入线程
  → JudgeManager.updateRealTime(now)
    → keysound.play(note, volume, pitch)
      → AbstractAudioDriver.play()
        → OboeSound.play()
          → C++ soundpool::play()  [lock-free, 纳秒级入队]
```

Oboe 的 `soundpool` 使用 lock-free pending list，`play()` 调用是纳秒级的。音频回调（`audio_player::generate_audio()`）由 Oboe 引擎独立驱动，与渲染循环无关。**音频管线无需修改 C++ 源码。**

### 3.6 渲染循环的职责收缩

重构后，`MainController.render()` 的职责变为：
1. 更新 UI 状态（皮肤动画、数值显示等）
2. 绘制画面
3. 处理帧率控制

**不再负责：**
- 时间推进（由实时 `System.nanoTime()` 替代）
- 输入轮询（由高精度输入线程替代）
- 判定触发（由高精度输入线程驱动）
- 音频触发（由高精度输入线程驱动）

### 3.7 线程模型对比

**当前：**
```
渲染线程 [60Hz]       轮询线程 [~1ms]        JudgeThread [~1ms]
    │                     │                      │
    ├─ timer.update()     ├─ input.poll()        ├─ 等待 mtime 变化
    ├─ render()           │   └─ keystate更新     ├─ judge.update(mtime)
    ├─ current.input()    │                      │   └─ keysound.play()
    │   └─ UI状态更新      │                      │
    └─ 帧率控制            │                      │
```

**重构后：**
```
渲染线程 [60-120Hz]        高精度输入线程 [1000-4000Hz]       Oboe 音频回调 [~3ms]
    │                           │                              │
    ├─ 读取 keystate (仅UI)     ├─ 直接读取输入设备               ├─ 混合 PCM 缓冲
    ├─ 皮肤动画更新              ├─ 纳秒精度时间戳记录             ├─ FFT 频谱分析
    ├─ 画面绘制                  ├─ judge.updateRealTime()       ├─ 输出到 AAudio
    └─ 帧率控制                  │   └─ keysound.play()          └─ (独立于所有 Java 线程)
                                 │       └─ soundpool::play()
                                 └─ 精确休眠到下一轮询周期
```

---

## 四、实施步骤

### Phase 1：时间源解放（基础改造）

**文件修改：**
1. `TimerManager.java` — 将 `nowmicrotime` 从缓存改为实时计算
   - `getNowMicroTime()` 直接调用 `(System.nanoTime() - starttime) / 1000`
   - 删除 `update()` 方法或将其改为空操作
   - 保留 `setMainState()` 中的 `starttime` 初始化

2. `BMSPlayer.java` — `render()` 中的 `timer.setMicroTimer(TIMER_PLAY, ...)` 逻辑调整
   - `TIMER_PLAY` 的开始时间在 STATE_READY→STATE_PLAY 时固定为 `System.nanoTime()`
   - `getNowTime(TIMER_PLAY)` 改为 `(System.nanoTime() - playStartNano) / 1000000`

3. `RhythmTimerProcessor.java` — 确保节拍计时器也使用实时时间

**影响范围：** TimerManager 的所有调用者（~26 个文件），但大部分仅需重新编译。

### Phase 2：高精度输入线程

**新增文件：**
1. `core/src/main/java/bms/player/beatoraja/input/HighPrecisionInputPoller.java`
   - 高精度输入轮询线程
   - 可配置轮询频率（1000/2000/4000Hz）
   - 直接驱动 JudgeManager 和音频触发

**修改文件：**
2. `MainController.java` — 创建并管理 HighPrecisionInputPoller 实例，替换旧 polling 线程
3. `JudgeManager.java` — 新增 `updateRealTime(long realTimeMicro)` 方法
4. `KeyInputProccessor.java` — JudgeThread 职责转移到 HighPrecisionInputPoller

### Phase 3：触摸输入独立通道

**修改文件：**
1. `AndroidLauncher.java` — `onTouchEvent()` 拦截，双路分发（原有 libGDX + 高精度输入线程）
2. `PlayTouchKeyMapper.java` — 同步机制调整，确保不和高精度线程冲突

### Phase 4：Autoplay 精度修复

**修改文件：**
1. `JudgeManager.java` — autoplay 分支改用实时时间，不再等待 frame-advanced mtime
2. 移除 autoplay 模式下的帧率依赖

### Phase 5：配置与优化

**修改文件：**
1. `Config.java` — 新增 `inputPollingRate` 配置项（默认 1000，可选 2000/4000）
2. Compose Launcher / 设置界面 — 提供输入采样率选项的 UI
3. 性能测试与线程优先级调优

---

## 五、风险与注意事项

### 5.1 线程安全

- `BMSPlayerInputProcessor.keystate[]` 当前由轮询线程写、渲染线程+JudgeThread 读，需要确保重构后的并发安全
- `JudgeManager` 的状态（notes judged, combo, gauge）需要线程安全保护
- 建议使用 `AtomicReference` 或 `volatile` + 细粒度锁

### 5.2 电池与性能

- 1000Hz 轮询意味着每秒 1000 次 `Gdx.input.isKeyPressed()` 调用，在低端设备上可能显著耗电
- 建议实现自适应轮询：仅在游戏进行中（STATE_PLAY）以高频率轮询，菜单界面降至 100Hz
- 提供 500Hz 选项给低端设备

### 5.3 兼容性

- 桌面版（libGDX Desktop）也需要同步重构对应路径
- `Gdx.input.isKeyPressed()` 在不同平台上的精度不同，需要测试验证

### 5.4 回归风险

- `SkinProperty` 的 Timer 系统被广泛使用（皮肤动画、UI 特效），修改 TimerManager 可能影响所有皮肤
- 建议保留 `timer.update()` 在渲染循环中但仅用于 UI 相关的 Timer（如动画），播放计时器使用独立的实时计算

---

## 六、预期效果

| 指标 | 重构前 | 重构后 |
|------|--------|--------|
| 输入→按键音延迟 | 16.67-33.33ms | 2-5ms（仅音频缓冲延迟） |
| Autoplay 精度 | 16.67ms | ≤1ms |
| 输入采样精度 | 60Hz | 1000Hz（可配置） |
| 判定精度 | 由帧率决定 | 由输入轮询率决定 |
| 渲染独立性 | 阻塞输入 | 完全独立 |
