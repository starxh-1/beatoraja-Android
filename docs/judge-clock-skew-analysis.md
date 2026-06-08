# 判定时钟倾斜分析：TIMER_PLAY 的跨线程读 / 量化噪声 / JMM 可见性

> 目的：把"为什么帧率波动会让判定窗口偏"这件事，从时钟源、数据流、内存模型三个层面拆开讲清楚，作为后续重构的论证基础。
>
> 范围：`core/src/main/java/bms/player/beatoraja/TimerManager.java`、`play/BMSPlayer.java`、`play/JudgeManager.java`、`play/KeyInputProccessor.java`、`play/LaneRenderer.java`。

---

## 0. 结论先行

工程里**没有一个全局"时钟对齐模式"开关**，但确实存在一种**隐式的时钟结构**，它把"主线程 60Hz 累加"和"输入线程 1000Hz 读取"硬生生拼接在一起。这个结构在帧率稳定时是工作得不错的，但**只要主线程抖动一次，输入线程读到的"判定时钟"就会出现非确定性的偏移**，表现为：

- 判定窗口在时间轴上整体"晃动"（jitter），典型幅度 ±半帧 ≈ 8ms @ 60fps
- 跨线程没有 happens-before 保障，可见性依赖 CPU 缓存一致性，可能拿到撕裂值或过期值
- 回放（replay）记录的按键时间也是被同一个量化过的 `mtime` 写入，所以回放准度跟着偏
- 越靠近判定窗口边缘的 note（PGREAT 边缘 ±15ms），被错判的概率越高

下面的章节会**逐层证明为什么**。

---

## 1. 三个时间源，各自的角色

工程里和"判定时间"相关的时钟源只有三处。它们的语义和刷新频率都不一样。

### 1.1 墙钟（wall clock）

**位置**：`TimerManager.java:44-46`

```java
public long getNowMicroTime() {
    return (System.nanoTime() - starttime) / 1000;
}
```

- **来源**：`System.nanoTime()`，单调递增，纳秒精度
- **刷新**：每次调用都重新算
- **语义**：从状态机进入当前 state 起的"墙钟流逝时间"
- **是否帧率相关**：**否**。`System.nanoTime()` 走 RDTSC / HPET，**不依赖游戏主循环**

✅ 这就是"为什么墙钟本身不会随帧率波动"——它根本不在游戏循环里读时间，它读的是硬件时钟。

### 1.2 状态机计时器（`timer[i]`）

**位置**：`TimerManager.java:18` 的 `long[] timer = new long[timerCount];`

```java
public void setMicroTimer(int id, long microtime) {
    if (id >= 0 && id < timerCount) {
        timer[id] = microtime;
    } else {
        current.getSkin().setMicroCustomTimer(id, microtime);
    }
}
```

- **存储**：裸 `long` 数组，**无 `volatile`、无 `synchronized`、无 `AtomicLong` 包装**
- **写者**：被多个地方写入——`BMSPlayer`、`KeyInputProccessor`、皮肤逻辑、IR 后台线程……
- **读者**：同样散落在多处
- **可见性**：**完全不保证跨线程可见性**

⚠️ 这是问题最严重的地方。下文 §4 会展开。

### 1.3 派生时间（`getNowMicroTime(int id)`）

**位置**：`TimerManager.java:48-53`

```java
public long getNowMicroTime(int id) {
    if(isTimerOn(id)) {
        return getNowMicroTime() - getMicroTimer(id);
    }
    return 0;
}
```

- **语义**：墙钟 − 该 timer 累计值
- **典型用法**：`getNowMicroTime(TIMER_PLAY)` = "从 PLAY 状态开始到现在，按 playspeed 折算后"经过的播放时间

这个表达式的结构很关键：它让 `TIMER_PLAY` 不需要从 0 开始跟着墙钟走，而是**通过减法把"加速/减速"的换算藏在了 timer 的累加里**。下一节专门拆这个。

---

## 2. TIMER_PLAY 是怎么"累出来"的

### 2.1 累加代码

**位置**：`BMSPlayer.java:766-770`

```java
case STATE_PLAY -> {
    final long deltatime = micronow - prevtime;
    final long deltaplay = deltatime * (100 - playspeed) / 100;
    PracticeProperty property = practice.getPracticeProperty();
    timer.setMicroTimer(TIMER_PLAY, timer.getMicroTimer(TIMER_PLAY) + deltaplay);
    ...
}
```

变量：
- `micronow = timer.getNowMicroTime()`：墙钟（来自 §1.1）
- `prevtime`：上一次 update 时记录的 `micronow`
- `playspeed`：100 = 1x，50 = 0.5x，200 = 2x，……

### 2.2 公式的"负数技巧"——为什么不是 `playspeed/100`

直觉上的写法应该是 `deltaplay = deltatime * playspeed / 100`，但代码写的是 `(100 - playspeed)/100`。**这个公式是反的**吗？

把它展开看：

| playspeed | (100 - p) / 100 | 累加效果 | 最终 mtime 增长速率 |
|---|---|---|---|
| 0 | 1.0 | TIMER_PLAY 跟着墙钟正向走 | wall − wall = **0**（冻结）|
| 25 | 0.75 | 慢速增长 | wall − 0.75·wall = **0.25·wall** |
| 50 | 0.50 | 半速增长 | wall − 0.5·wall = **0.5·wall** |
| 100 | 0 | TIMER_PLAY 永远为 0 | wall − 0 = **1.0·wall** |
| 200 | −1.0 | TIMER_PLAY 越来越负 | wall − (−wall) = **2.0·wall** |
| 300 | −2.0 | TIMER_PLAY 越来越负 | wall − (−2·wall) = **3.0·wall** |

所以**虽然累加方向反了，但因为 mtime 是"减 TIMER_PLAY"，相当于乘 playspeed/100**。这是一个把"乘法"塞进"加法"再通过"减法"还原的迂回表达。

单独看是 ok 的，**但它和"每帧累加"这个事实绑定在一起**——下面 §3 是这个事实导致的问题。

### 2.3 整数除法的截断

`(100 - playspeed) / 100` 是整数除法，会**主动丢掉小数部分**。

- playspeed=100：截断是 0，无所谓
- playspeed=50：`(100-50)/100 = 50/100 = 0`（**也是 0**）

等等，playspeed=50 的情况不是应该是 0.5 吗？再看一遍：`deltatime * (100 - playspeed) / 100`。

对于 playspeed=50：`deltatime * 50 / 100`。

`deltatime` 是微秒（μs）。60fps 一帧的 deltatime ≈ 16667μs。

```
16667 * 50 / 100 = 833350 / 100 = 8333 μs
```

实际想要的是 `16667 * 0.5 = 8333.5`，**截断掉 0.5μs**。这其实不是个事——0.5μs 远小于帧时。

但慢速模式截断会变严重吗？算一下：
- playspeed=25：`deltatime * 75 / 100`。deltatime=16667 → 12500（实际 12500.25，截断 0.25μs）
- playspeed=1：`(100-1)/100 = 99/100 = 0`，但分子是 `deltatime * 99`，例如 deltatime=16667 → 1650033μs，再除 100 = 16500μs（实际 16500.33，截断 0.33μs）

每帧最多损失亚微秒级，**长时间累计也不到 1ms**。所以"整数截断"在这里**不是实际问题**。

### 2.4 prevtime 的更新点

**位置**：`BMSPlayer.java:950`

```java
prevtime = micronow;
}
```

这是 `update()` 的**最末尾**，在所有 state 分支之后。也就是说 `prevtime` 永远在 STATE_PLAY 之外的分支也会被更新。下次进入 STATE_PLAY 时 `deltatime = micronow - prevtime` 会**包含整个 state 切换期间的真实墙钟流逝**——但因为 STATE_PLAY 内部会先 setMicroTimer(TIMER_PLAY, ...) 之前才取 deltatime，所以第一次 STATE_PLAY 的 deltatime 可能略大（从 STATE_READY 进 STATE_PLAY 的过渡期）。

实际影响很轻微，但**说明 prevtime 的语义其实不是"上一帧的 frame 时间"，而是"上一次 update() 调用的时间"**。

---

## 3. 输入线程的 mtime：从哪里来

### 3.1 输入线程主循环

**位置**：`KeyInputProccessor.java:158-203`

```java
public void run() {
    int index = 0;
    long frametime = 1;
    final BMSPlayerInputProcessor input = player.main.getInputProcessor();
    final JudgeManager judge = player.getJudgeManager();
    final long lasttime = timelines[timelines.length - 1].getMicroTime() + player.getMaxTailMs() * 1000;

    final long pollIntervalNs = 1_000_000L; // 1000Hz = 1ms
    long nextPollTime = System.nanoTime();
    long prevtime = -1;
    while (!stop) {
        // 实时读取播放时间（TimerManager 现已改为实时计算，不再受帧率限制）
        final long mtime = player.timer.getNowMicroTime(TIMER_PLAY);
        ...
        judge.update(mtime);
        ...
        // 精确休眠到下一轮询周期（1000Hz）
        nextPollTime += pollIntervalNs;
        ...
    }
}
```

注意几个**很关键的事实**：

1. **轮询周期 1ms（1000Hz）**——比主线程 60Hz 高 16 倍多
2. **`mtime` 的来源是 `TIMER_PLAY`**（§1.3），不是直接读墙钟
3. **`TIMER_PLAY` 是被主线程每帧写一次**（§2.1）——`KeyInputProccessor` 本身不更新它
4. **没有任何同步**：`timer[]` 数组是普通 `long[]`，跨线程读写无 happens-before 保证

### 3.2 mtime 的实际步进

把 §1.1、§2.1 拼起来画时序：

```
时间(ms)   0    16.67  33.33  50.00  66.67  83.33  100.00
──────────────────────────────────────────────────────
主线程    [update: 60Hz]
micronow    0   16670  33330  50000  66670  83330  100000
TIMER_PLAY 0       0      0      0      0      0      0  (playspeed=100)

输入线程  [poll: 1000Hz, 每 1ms 一次]
mtime #1   0
mtime #2   0       ← 跟上一次一样
mtime #3   0
...
mtime #16  0       ← 整整 16 次都读到 0
mtime #17  16670   ← 主线程刚跑完 update, TIMER_PLAY 累了一次
mtime #18  16670
...
mtime #32  16670
mtime #33  33330   ← 又过了一帧
...
```

**输入线程读到的 mtime 是 16ms 步进的离散值**——尽管它每 1ms 轮询一次，但只有在主线程 update 完之后 mtime 才会变。

这就是**量化噪声**（quantization noise）的来源。

### 3.3 把这个 mtime 喂给 judge

`judge.update(mtime)` 的内部循环：

**位置**：`JudgeManager.java:250-251`

```java
for (Note note = state.lanemodel.getNote();
     note != null && note.getMicroTime() <= mtime;
     note = state.lanemodel.getNote()) {
    if (note.getMicroTime() <= prevmtime) {
        continue;
    }
    ...
}
```

判定逻辑（PGREAT 窗口）：

**位置**：`JudgeManager.java:438-441`（部分）

```java
for (judge = 0; judge < mjudge.length
        && !(dmtime >= mjudge[judge][0] && dmtime <= mjudge[judge][1]); judge++) {
    ;
}
```

其中 `dmtime = note.getMicroTime() - pmtime`（pmtime 是按键时间戳，也来自 `mtime` 系列）。

**判定窗口的"现在"用的是 mtime**。所以 mtime 的 16ms 步进，**直接决定了判定窗口在时间轴上的位置**。

---

## 4. 量化噪声 = ±8ms 判定误差

### 4.1 算一下

设帧率稳定 60fps（16.67ms 帧），playspeed=100（正常速度）。

主线程在 `t = k * 16.67ms` 时更新 TIMER_PLAY，TIMER_PLAY 跳到对应的 mtime 值。输入线程在任意时刻 `t_now` 轮询，**读到的 mtime 一定是某个 `k * 16.67ms`**——它**不会**告诉你"现在到底是 33.3ms 还是 35.7ms 还是 38.1ms"。

换句话说，**输入线程永远比墙钟最多落后半帧**。具体落后多少，取决于它恰好在帧的哪一刻读：

| 输入线程读取时机 | TIMER_PLAY 写入时机 | 输入线程读到的 mtime | 与真实墙钟的偏差 |
|---|---|---|---|
| 帧 A 刚结束（主线程刚写完） | 帧 A 结束 | 帧 A 对应 mtime | 0 |
| 帧 A 结束 + 0.5ms | 帧 A 结束 | 帧 A 对应 mtime | +0.5ms |
| 帧 A 结束 + 8ms | 帧 A 结束 | 帧 A 对应 mtime | +8ms |
| 帧 A 结束 + 15ms | 帧 A 结束 | 帧 A 对应 mtime | +15ms |
| 帧 A 结束 + 16.67ms | 帧 B 刚写完 | 帧 B 对应 mtime | 0（新一轮） |

**误差范围：0 ~ 16.67ms**，**平均 ~8.3ms**。

### 4.2 这个误差对判定意味着什么

PGREAT 窗口（默认 ±15ms）：

```
                       ┌─ PGREAT: ±15ms ─┐
        ───────────────┼─────────────────┼───────────────
                  -15ms│       note      │+15ms
                       └─────────────────┘
```

如果按键时间精确落在 ±15ms 的边缘（实际相距 14.5ms），由于 mtime 比墙钟晚 0~16ms 报告"现在"：

- mtime 比真实墙钟晚 1ms → 报告的偏差从 14.5ms 变成 15.5ms → 落到窗口外 → **被判定为 GREAT**
- mtime 跟真实墙钟差不多 → 报告的偏差 14.5ms → 还在窗口内 → **PGREAT**

**同一次按键，可能因为 mtime 的报告时刻不同，被判成 PGREAT 或 GREAT**。

换句话说，**判定窗口在时间轴上是抖动的**，抖动幅度 0~16ms，平均 8ms。**这是一个统计上随机的事件**，对玩家表现为"同样准度的按键有时候 PGREAT 有时候 GREAT"。

GREAT 窗口更大（±30ms），所以 GREAT 内部的 note 几乎不会受影响。

### 4.3 帧率不稳时这个误差会"放大"

上面的 8ms 是**稳定 60fps 时的平均误差**。如果帧率不稳：

- 掉到 30fps：帧时 33.33ms → 误差范围 0~33.33ms，平均 16.7ms
- 偶发卡顿到 200ms：那一帧之前的所有 mtime 都过时了 200ms；卡顿结束后下一次读 mtime 会"突然跳变" 200ms

跳变对回放/录像影响最大——参见 §6。

---

## 5. 跨线程的内存模型问题

除了 §4 的"量化噪声"，还有更基础的**JMM 可见性问题**——即使我们把 §4 的问题修了，如果线程间不保证可见性，读到的值依然是错的。

### 5.1 JMM 怎么规定

Java 内存模型（JLS §17）规定：

- **写线程**对 `long` 字段的写入，**对其他线程不一定可见**——除非有 happens-before 关系
- 即使有 happens-before 关系（如 `volatile`、`synchronized`、`AtomicLong`），32 位 JVM 的 `long` 写入**也不原子**——会拆成两次 32-bit 写

`timer[]` 数组的情况：
- 不是 `volatile`
- 不是 `synchronized`
- 不是 `AtomicLongArray`

所以**理论上**：
- 输入线程可能读到一个**陈旧**的 TIMER_PLAY 值（缓存里没失效）
- 32-bit 平台上，可能读到**撕裂值**（高 32 位是旧值、低 32 位是新值，结果既不是旧值也不是新值）

### 5.2 实际概率

- **撕裂值**：在 64-bit ART 上（绝大多数现代 Android 设备），`long` 读写是原子的。32-bit ART 上不原子，但 ART 内部对 `long` 字段读写有锁（详见 [AOSP 注释](https://source.android.com/devices/tech/dalvik/art-object-model)），实际上也很少撕裂
- **陈旧值**：在 x86 上因为有强内存模型（TSO），大多数情况下能"差不多"看到新值；在 ARM 上弱内存模型下，**没有 happens-before 时，跨线程的读可能延迟任意时间才看到新值**

**重点**：即使在 x86 上能"看到"，**JMM 不保证**这个可见性——意味着这是**未被定义的行为**（undefined behavior），只是恰好在大多数硬件上工作。

### 5.3 一个具体的"读错"场景

```
时刻    主线程（60Hz）              输入线程（1000Hz）
t=0     [写 timer[PLAY] = 0]        [读 timer[PLAY]] → 拿到 0 ✅
t=16ms  [写 timer[PLAY] = 16670]    
t=17ms                              [读 timer[PLAY]] → 拿到的可能是 0（陈旧）
t=18ms                              [读 timer[PLAY]] → 拿到的可能是 0
...
t=33ms                              [读 timer[PLAY]] → 终于看到 16670
t=33ms  [写 timer[PLAY] = 33330]    
t=34ms                              [读 timer[PLAY]] → 可能还是 16670（陈旧）
```

**没有任何同步原语保证输入线程能及时看到新值**。它什么时候看到，取决于 CPU 缓存一致性协议的传播时延、JIT 编译器的优化、ART 内存屏障的实现……

### 5.4 还有一个陷阱：调用栈里的 getTimer

**位置**：`TimerManager.java:34-39`

```java
public long getNowTime(int id) {
    if(isTimerOn(id)) {
        return (getNowMicroTime() - getMicroTimer(id)) / 1000;
    }
    return 0;
}
```

`getMicroTimer(id)` 在数组里取值后**直接参与减法**。如果它拿到撕裂值：
- 撕裂值 = (高 32 位旧，低 32 位新)
- 减墙钟 = 错乱的负数（极大或极小）
- `/1000` 还是错乱数
- 上层 `if (mtime > lasttime)` 这类比较**可能为真也可能为假**
- 极端情况：mtime 跳成负数，破坏回放、判定、计时等所有逻辑

**虽然撕裂在 64-bit ART 上罕见，但这是"JMM 不可见性"叠加"无原子性"的复合风险**。

---

## 6. 回放记录被同款 mtime 污染

**位置**：`KeyInputProccessor.java:176-181`

```java
// リプレイデータ再生
if (keylog != null) {
    while (index < keylog.length && keylog[index].getTime() + microMarginTime <= mtime) {
        final KeyInputLog key = keylog[index];
        input.setKeyState(key.getKeycode(), key.isPressed(), key.getTime() + microMarginTime);
        index++;
    }
}
```

回放（replay）是把录制的按键流"按时间"再注入回游戏。`keylog[index].getTime()` 是录音时的 mtime，**注入条件是 `keylogTime <= mtime`**。

mtime 量化到 16ms → 注入时刻也是 16ms 步进 → **回放准度 = 录音时的量化噪声 ±8ms**。

更糟的是：回放注入的 key state 会被 `judge.update(mtime)` 用来做判定——**判定的 mtime 和回放注入的 mtime 来自同一个量化源**。它们是"自洽"的（不会因为 mtime 量化导致回放和判定错位），但**跟真实墙钟仍然差 0~16ms**。

所以**回放打分结果跟实时玩不会 100% 复现**——同样的按键，回放可能多 0~16ms 的误差。

---

## 7. 帧率抖动的复合效应

到这里我们可以画一个完整的"故障画像"。

### 7.1 稳定 60fps 时的"基准噪声"

- mtime 步进 = 16.67ms
- 输入线程读到的 mtime 比墙钟晚 0~16.67ms，平均 8.3ms
- 判定窗口以这个 8.3ms 平均偏移为基准"晃动"
- 同一准度的按键在 PGREAT/GREAT 边界有概率跳级
- 玩家感觉："我打得很准，但判定结果有一点点飘"

### 7.2 帧率掉到 30fps

- mtime 步进 = 33.33ms
- 输入线程读到的 mtime 比墙钟晚 0~33.33ms，平均 16.7ms
- 判定窗口晃动幅度翻倍
- 玩家感觉："画面一卡，判定就飘了"

### 7.3 偶发卡顿（GC / IO / 资源加载）

- 卡顿 200ms 内，主线程没机会跑 update → TIMER_PLAY 停止累加
- 输入线程在卡顿期间读到的 mtime 全部是卡顿前的值
- 卡顿结束后，主线程终于跑了一次 update，deltatime = 200ms → TIMER_PLAY 一次跳 200ms
- 输入线程下一次轮询读到的 mtime **突然从卡顿前的值跳到卡顿结束后的值**（也可能由于 §5 的可见性问题，延迟几个轮询周期才看到）

**跳变的后果**：
- `judge.update(mtime)` 在卡顿期间处理的是旧 mtime，会漏掉卡顿期间本应判定的 note
- 卡顿结束后，新 mtime 让 input thread "瞬间看到了 200ms 之后的歌时间"，但按键流（如果有）也会瞬间灌入一堆
- 回放记录：下一次写入的 mtime 比上一次写入晚 200ms+，**回放/录像里这个位置是个"空档"**

### 7.4 复合效应的可视化

```
主线程 update 周期（ms）  |  输入线程读到的 mtime
                         |
正常 16ms:               |  0  0  0 ... 0  16  16  16 ... 16  33  33 ...   (16ms 步进)
GC 卡顿 200ms:          |  ... 16 16 16 16 16 16 16 16 16 16 216 216 ...  (跳变 200ms)
帧率波动:               |  10  10 10 10 10 30 30 30 30 30 30 30 50 50 ...   (步进不稳)
```

跳变和不稳步进叠加 16ms 的"基准步进"，最终 mtime 的实际"精度"远低于 1ms 输入线程想达到的精度。

---

## 8. 为什么方案 A 有效（单写者）

### 8.1 改动要点回顾

把"写 TIMER_PLAY"的人从主线程挪到输入线程，主线程只读：

```java
// 输入线程（单写者）
while (!stop) {
    long now = player.timer.getNowMicroTime();      // 墙钟，无竞争
    long dt  = now - prevTime;                       // 真实流逝
    long deltaPlay = dt * (100 - localPlaySpeed) / 100;
    player.timer.setMicroTimer(TIMER_PLAY,
        player.timer.getMicroTimer(TIMER_PLAY) + deltaPlay);  // 1ms 一次
    long mtime = player.timer.getNowMicroTime(TIMER_PLAY);
    judge.update(mtime);
    ...
}
```

### 8.2 为什么这能解决问题

| 问题 | 修复前 | 修复后 |
|---|---|---|
| 量化噪声（16ms 步进）| 主线程 60Hz 写 | 输入线程 1000Hz 写 → 步进降到 1ms |
| 跨线程可见性 | 主写、读，无同步 | 写者只输入线程，主线程只读；升级 `volatile long[]` |
| 32-bit 撕裂 | 无原子保证 | volatile 提供 64-bit 原子语义 |
| playspeed 传递 | 主线程改，主线程读，无问题 | 主线程改 → volatile int 字段 → 输入线程每帧读 |
| 累计漂移 | 每帧累加 0.5μs 截断 | 同上，**没改善**，但本来就不严重 |

### 8.3 残留问题

- **playspeed 跨线程传递**：用 `volatile int`，主线程修改时输入线程可能延迟几个 μs 才看到，**最快 1ms 看到**——对于"换挡"这种玩家操作来说完全可接受
- **主线程读 TIMER_PLAY 用来 render**：主线程读的频率是 60Hz，输入线程写的频率是 1000Hz，主线程能稳定读到"比上一次 render 时更新的"值——**可见性靠 volatile 解决**
- **单写者原则**：通过设计保证只有输入线程调 `setMicroTimer(TIMER_PLAY, ...)`，主线程不能写

### 8.4 风险点

- **输入线程压力**：原来只读 TIMER_PLAY，现在还要算 deltaplay + 写 TIMER_PLAY。1000Hz 一次乘法 + volatile 写，CPU 开销可忽略
- **状态机切换时序**：进入 STATE_PLAY 时必须确保 `playStartSongTime` 和 `playStartWall` 同时被重置——多字段更新要么全在输入线程里做（不用 volatile），要么用 `synchronized`/`AtomicReference` 打包
- **退出 STATE_PLAY 的清理**：输入线程可能还在写 TIMER_PLAY，主线程如果在切换状态时清零（`setMicroTimer(0)`），会引入新的 race——需要约定"切换时谁先谁后"或用一个 `stateAtomic` 做关卡

---

## 9. 为什么方案 B 更彻底（去掉 TIMER_PLAY 跨线程读）

### 9.1 改动要点回顾

输入线程**完全不读 TIMER_PLAY**。它自己有 3 个 volatile 字段：

```java
private volatile long playStartWall;      // 墙钟起点
private volatile int  currentPlaySpeed;   // 速度倍率
private volatile long playStartSongTime;  // 起点对应的 song time
```

mtime 直接本地算：

```java
long wallNow = System.nanoTime();
long wallElapsedUs = (wallNow - playStartWall) / 1000;
long mtime = playStartSongTime + wallElapsedUs * currentPlaySpeed / 100;
```

### 9.2 为什么这能"彻底"

| 问题 | 修复 | 说明 |
|---|---|---|
| 量化噪声 | 输入线程每 1ms 算一次 mtime，精度 = 1ms | **输入线程本身就是写者，1ms 步进可接受** |
| 跨线程 TIMER_PLAY 读 | **完全删除** | 方案 A 还有读，B 直接没了 |
| 跨线程 playspeed 读 | volatile int 单字段读 | 比 A 更少跨线程状态 |
| JMM 可见性 | volatile 字段 | volatile long 也有 64-bit 原子语义 |
| 主线程 TIMER_PLAY 用途 | 保留 TIMER_PLAY 字段（主线程自己累加用）或彻底删 | 见 §9.3 |

### 9.3 残留问题：主线程还在用 TIMER_PLAY

主线程在以下地方用 TIMER_PLAY：

1. `BMSPlayer.java:770` 累加——B 方案里这一行**删掉**（主线程不累加了）
2. `BMSPlayer.java:873`：`for (long l = timer.getTimer(TIMER_FAILED) - timer.getTimer(TIMER_PLAY); l < playtime + 500; ...)`——读 `TIMER_PLAY` 做 gauge log 索引
3. `LaneRenderer.java:334-335`：`time - main.timer.getTimer(TIMER_PLAY)`——render 时算 note 位置

**这些都需要重构**：
- 方案 B-1：主线程保留 TIMER_PLAY，但**主线程自己累加**（用主线程的 prevtime/micronow）。缺点：累加精度还是 16ms，但 render 用就够了
- 方案 B-2：彻底删 TIMER_PLAY。`LaneRenderer` 改成读墙钟 + speed，`gauge log` 改用别的时间索引

**B-2 改动大、收益不明显**（render 60Hz 精度足够）。**B-1 性价比高**：方案 A 的代码结构（单写者），主线程多一份独立维护的 TIMER_PLAY。

---

## 10. 总结：为什么"输入线程读 TIMER_PLAY"是 root cause

把全文压缩成一句话：

**主线程每帧把 `TIMER_PLAY += deltatime·speed`（60Hz，步进 16ms），输入线程每毫秒读 `getNowMicroTime(TIMER_PLAY)`（1000Hz），但读到的值被卡死在主线程的 16ms 步进上；跨线程又没有 happens-before，所以"读到什么时候、读到新旧值中的哪个"都是不确定的**。

**这个不确定就是"帧率波动导致判定倾斜"的物理来源**。

修复的两种路径：
- **A 路径（治标）**：把"写者"挪到输入线程，让"读-改-写"都在同一线程，**步进降到 1ms**。主线程 render 仍可读，跨线程只读不写，加 `volatile` 解决可见性
- **B 路径（治本）**：输入线程直接用墙钟 + 一次性 playspeed 换算，**完全不读 TIMER_PLAY**。主线程的 TIMER_PLAY 要么自己维护（B-1），要么彻底删掉（B-2）

工程选择：
- 如果只想消除"判定倾斜"：**A 路径**就够
- 如果未来要把 playspeed 切换做得更平滑、要做"亚毫秒精度"录像、要彻底搞清楚跨线程时间状态：走 **B 路径**

---

## 附录 A：相关文件 & 行号速查

| 文件 | 行号 | 角色 |
|---|---|---|
| `TimerManager.java:18` | `private final long[] timer = new long[timerCount];` | 跨线程共享的 timer 数组 |
| `TimerManager.java:30-32` | `getNowTime()` | 墙钟（ms） |
| `TimerManager.java:44-46` | `getNowMicroTime()` | 墙钟（μs），**不依赖帧率** |
| `TimerManager.java:48-53` | `getNowMicroTime(int id)` | 墙钟 − timer[id] |
| `TimerManager.java:79-85` | `setMicroTimer(int, long)` | 写 timer 数组，**无同步** |
| `BMSPlayer.java:87` | `private int playspeed = 100;` | 速度倍率 |
| `BMSPlayer.java:105` | `private long prevtime;` | 主线程上一帧的墙钟 |
| `BMSPlayer.java:766-770` | STATE_PLAY 里累加 TIMER_PLAY | **主线程累加**（方案 A 删这里） |
| `BMSPlayer.java:950` | `prevtime = micronow;` | 主线程每帧末尾更新 |
| `KeyInputProccessor.java:158-203` | 输入线程主循环，1000Hz 轮询 | **读取 mtime 并喂给 judge** |
| `KeyInputProccessor.java:173` | `final long mtime = player.timer.getNowMicroTime(TIMER_PLAY);` | **跨线程读 timer 数组** |
| `KeyInputProccessor.java:184` | `judge.update(mtime);` | 把量化 mtime 喂给判定 |
| `JudgeManager.java:108` | `private long prevmtime;` | 判定内部的上一次 mtime |
| `JudgeManager.java:250-251` | `for (Note note = ... note.getMicroTime() <= mtime; ...)` | 判定窗口循环 |
| `JudgeManager.java:363` | `prevmtime = mtime;` | 判定内部 mtime 更新 |
| `JudgeManager.java:438-441` | `dmtime >= mjudge[judge][0] && dmtime <= mjudge[judge][1]` | PGREAT/GREAT 窗口比较 |
| `LaneRenderer.java:334-335` | `time - main.timer.getTimer(TIMER_PLAY)` | render 时算 note 位置 |

## 附录 B：术语表

- **墙钟**（wall clock）：实际流逝的物理时间，由 `System.nanoTime()` 读出
- **量化噪声**（quantization noise）：把连续时间离散成 N 步进后产生的、步进大小量级的误差
- **JMM**（Java Memory Model）：Java 内存模型，定义多线程内存访问的可见性 / 原子性 / 有序性
- **happens-before**：JMM 的核心关系，写 A happens-before 读 B 时，B 一定能看到 A 写后的值
- **撕裂值**（torn value）：64-bit 字段在 32-bit 平台上分两次写入时，读到"高 32 位旧 + 低 32 位新"的拼接结果
- **TSO**（Total Store Order）：x86 使用的强内存模型，所有写对其他线程最终都可见
- **ARM 弱内存模型**：ARM 默认不对写做全局可见性，需要显式内存屏障
