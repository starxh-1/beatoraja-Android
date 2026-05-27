# MusicResult 相关文件线程冲突分析

## 文件清单
- `AbstractResult.java` - 结果画面抽象基类
- `MusicResult.java` - 音乐结果画面
- `CourseResult.java` - 课程结果画面
- `MusicResultSkin.java` - 音乐结果皮肤
- `CourseResultSkin.java` - 课程结果皮肤
- `ResultKeyProperty.java` - 键位配置枚举
- `SkinGaugeGraphObject.java` - 仪表图形绘制对象

---

## 发现的线程冲突风险

### 1. Timer 对象跨线程访问 (高风险)

**MusicResult.java:118**
```java
Thread irprocess = new Thread(() -> {
    ...
    timer.switchTimer(TIMER_IR_CONNECT_BEGIN, true);  // 后台线程调用
    ...
    timer.switchTimer(succeed ? TIMER_IR_CONNECT_SUCCESS : TIMER_IR_CONNECT_FAIL, true);  // 后台线程调用
    ...
    state = STATE_IR_FINISHED;
});
irprocess.start();
```

**CourseResult.java:124, 141**
```java
Thread irprocess = new Thread(() -> {
    ...
    timer.switchTimer(TIMER_IR_CONNECT_BEGIN, true);  // 后台线程调用
    ...
    timer.switchTimer(succeed ? TIMER_IR_CONNECT_SUCCESS : TIMER_IR_CONNECT_FAIL, true);  // 后台线程调用
    ...
});
```

**问题**：Timer 对象通常在主线程管理，后台线程直接调用 `timer.switchTimer()` 可能导致：
- 计时器状态不一致
- 回调在错误的线程执行
- 潜在的死锁或卡死

---

### 2. 共享字段的并发访问 (高风险)

以下字段在多个线程中被访问而没有同步机制：

| 字段 | MusicResult | CourseResult | 主线程访问位置 |
|------|-------------|--------------|----------------|
| `state` | 后台线程写入 (line 150) | 后台线程写入 (line 156) | `input()` line 296 |
| `ranking` | 后台线程写入 (line 139) | 后台线程写入 (line 145) | `render()` |
| `rankingOffset` | 后台线程写入 (line 140, 146) | 后台线程写入 (line 146) | `input()`, `render()` |
| `saveReplay[]` | 后台线程写入 (line 335) | 后台线程写入 (line 323) | `input()` |
| `timingDistribution` | 后台线程写入 (line 368-384) | - | `render()` 间接访问 |

**MusicResult.java:368-385** - timingDistribution 后台计算
```java
new Thread(() -> {
    ...
    for (TimeLine tl : model.getAllTimeLines()) {
        for (int i = 0; i < lanes; i++) {
            Note n = tl.getNote(i);
            if (n != null && ...) {
                int state = n.getState();
                int time = n.getPlayTime();
                if (state >= 1) {
                    count++;
                    td.add(time);  // 写入 timingDistribution.dist[]
                }
            }
        }
    }
    td.statisticValueCalcuate();
}).start();
```

**风险**：主线程的 `render()` 或 `input()` 可能在后台线程还在写入时读取 `timingDistribution`，导致数据不一致。

---

### 3. Resource 对象跨线程访问 (中风险)

**MusicResult.java:473** 和 **CourseResult.java:285** - 数据库写入
```java
new Thread(() -> {
    main.getPlayDataAccessor().writeScoreData(...);  // 访问 resource
}, "ScoreWriteThread").start();
```

后台线程访问 `resource.getScoreData()`, `resource.getBMSModel()` 等，主线程同时在 `render()` 和 `input()` 中访问同一个 resource 对象。

**MusicResult.render()** 中访问：
```java
final Array<FloatArray[]> coursegauge = resource.getCourseGauge();  // line 190
resource.getCourseScoreData()...  // line 194-197
resource.getGauge()...  // line 186, 422
resource.getCourseBMSModels()...  // line 191
```

---

### 4. SkinGaugeGraphObject 纹理重建竞态 (低风险)

**SkinGaugeGraphObject.java:154-163**
```java
private void rebuildTextures() {
    if (backtex != null) {
        backtex.getTexture().dispose();  // 释放纹理
        backtex = null;
    }
    if (shapetex != null) {
        shapetex.getTexture().dispose();
        shapetex = null;
    }
    // ... 重建新纹理
}
```

`prepare()` 在主线程调用 `rebuildTextures()` 时，如果 `dispose()` 被调用（主线程），可能出现短暂的空指针状态，但风险较低。

---

## 线程列表汇总

### MusicResult 创建的线程
1. `irprocess` (line 110) - IR 发送与排名获取
2. 后台 TimingDistribution 计算线程 (line 368)
3. `ScoreWriteThread` (line 471) - 分数数据库写入

### CourseResult 创建的线程
1. `irprocess` (line 116) - IR 发送与排名获取
2. `CourseScoreWriteThread` (line 283) - 课程分数数据库写入
3. `CourseReplayWriteThread` (line 320) - 课程回放数据写入

---

## 结论

**最可能导致 Result 卡死的原因**：

1. **Timer 跨线程调用** - 后台线程调用 `timer.switchTimer()` 可能导致计时器内部状态破坏或死锁

2. **共享字段无锁访问** - `state`, `ranking`, `rankingOffset` 等字段在后台线程写入时，主线程可能正在读取，导致状态机错乱（例如 `state == STATE_IR_PROCESSING` 卡住无法转入 `STATE_IR_FINISHED`）

3. **Resource 并发访问** - 数据库写入线程和主线程同时访问 `resource` 对象，可能导致 SQLite 锁定或数据不一致

**建议优先检查点**：
- Timer 相关的 `switchTimer()` 调用是否应该在主线程执行
- `state` 字段的读写是否需要 volatile 或其他同步机制
- IR 处理线程是否应该使用 `Gdx.app.postRunnable()` 来更新主线程状态