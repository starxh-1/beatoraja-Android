# Result 界面卡死 10 秒 Bug 修复报告

## 现象

- 打完歌进入 Result 界面后整个 result 卡住 ~10 秒
- GLThread CPU 突然飙高
- clear/new record 复现率更高
- 卡死期间按键 → 立刻回 select
- log 只剩 audiolog，其他不动
- ~10 秒后 scoreData 才出现

## 根因

`MusicResult.create()` → `updateScoreDatabase()` 在 **GL Thread** 上同步调用 `readScoreData()` → `ScoreDatabaseAccessor.getScoreData()`。

`ScoreDatabaseAccessor` 所有方法使用 `synchronized` 互斥。同时启动的 `ScoreWriteThread` 持有同一把锁执行 `writeScoreData()` → `setScoreData()` → `setPlayerData()`（SQLite WAL commit + fsync）。

**时序图：**

```
GL Thread                         ScoreWriteThread (后台)
────────                          ─────────────────────
create()
  updateScoreDatabase()
    readScoreData() ──等待锁──→   writeScoreData() [持有锁]
      ...                          scoredb.getScoreData()
      ...                          scoredb.setScoreData()
      ...                          scoredb.setPlayerData() ← SQLite fsync
      ...                          释放锁
    [获取锁] ← 5-15 秒后
    继续...
```

Android 设备上 SQLite WAL commit + fsync 可达 5-15 秒，GL Thread 期间被完全阻塞，无法渲染 result 界面。

已知"按键立刻回 select" 是因为 `BMSPlayerInputProcessor` 独立于 GL Thread 处理输入，按键被缓存在事件队列中。

## 修复方案（两层防御）

### 层 1: `MusicResult.updateScoreDatabase()` — 旧分加载脱离 GL Thread

**文件:** `core/src/main/java/bms/player/beatoraja/result/MusicResult.java`

- `create()` 中删除同步 `readScoreData()` 调用
- `updateScoreDatabase()` 先用 `new ScoreData()`（全零）填充 `oldscore`，GL Thread 立即继续
- 启动 `OldScoreLoadThread` 后台加载真实旧分
- 旧分加载完成后刷新 `ScoreDataProperty`，用户 1-3 帧内可见
- replay 自动保存也移到 `OldScoreLoadThread` 中（需要真实 `oldscore` 判断 `ReplayAutoSaveConstraint`）

### 层 2: `ScoreDatabaseAccessor` — ReentrantLock 兜底

**文件:** `core/src/main/java/bms/player/beatoraja/ScoreDatabaseAccessor.java`

- 所有读方法（`getScoreData`/`getInformation`/`getScoreDatas`/`getPlayerDatas`）：`tryLock(500ms)` → 超时返回 null/空数组
- 所有写方法（`setScoreData`/`setInformation`/`setPlayerData`/`deleteScoreData`）：`lock()` 阻塞（后台线程调用）
- `unlock()` 放入 `finally` 确保异常安全

## 测试

### JUnit 测试

```bash
# 需要 junit:4.13.2 依赖（已在测试文件头部注释说明）
gradle :core:test --tests "*ResultFreezeDBLockTest"
```

6 个测试全部通过：

| # | 测试场景 | 验证点 |
|---|---------|--------|
| testReadScoreNoContention | 无竞争读 | < 200ms |
| testReadWithWriteContention_fastWrite | 写锁 800ms → 读超时 500ms | tryLock 超时返回 null |
| testReadWithWriteContention_slowWrite | 写锁 3s → 读超时 500ms | 不无限阻塞 |
| testLockReentrancy | setScoreData → getScoreData 链式 | ReentrantLock 可重入 |
| testConcurrentReadWriteNoDeadlock | 4 线程 80 次交替读写 | 0 错误，无死锁 |
| testExactBugScenario | GL Thread + Writer 竞争精确模拟 | 验证时序 |

### 探针诊断工具

项目保留了完整的诊断探针基础设施（全部已注释，需调试时取消注释即可）：

| 文件 | 探针数量 | 用途 |
|------|---------|------|
| `result/debug/ResultFreezeDiagnostics.java` | 核心类 | 20 个探针方法 |
| `result/debug/TestCases.java` | 文档 | 8 个 testcase 指南 |
| `result/MusicResult.java` | 19 处 | create/render/input/updateScoreDB 全阶段 |
| `result/SkinGaugeGraphObject.java` | 2 处 | gauge 纹理重建耗时 |
| `ScoreDatabaseAccessor.java` | 7 处 | DB 锁等待/获取日志 |
| `PlayDataAccessor.java` | 3 处 | 写分线程追踪 |
| `play/BMSPlayer.java` | 5 处 | BMSPlayer→RESULT 过渡耗时 |
| `skin/Skin.java` | 2 处 | 每帧皮肤渲染耗时（高频） |
| `MainController.java` | 1 处 | >16ms 慢帧报警（高频） |
| `test/.../ResultFreezeDBLockTest.java` | 1 个 | JUnit 6 testcase（无需注释） |

开启探针：删除对应 `// ` 前缀 → `adb logcat -s ResultFreezeDiag:V` 观察。

## 修改文件列表

```
core/src/main/java/bms/player/beatoraja/
├── ScoreDatabaseAccessor.java         # synchronized → ReentrantLock.tryLock(500ms)
├── result/MusicResult.java            # readScoreData() → OldScoreLoadThread
├── result/SkinGaugeGraphObject.java   # [注释] gauge 探针
├── skin/Skin.java                     # [注释] 皮肤渲染探针
├── play/BMSPlayer.java                # [注释] 过渡探针
├── PlayDataAccessor.java              # [注释] 写分探针
├── MainController.java                # [注释] 慢帧探针
└── result/debug/
    ├── ResultFreezeDiagnostics.java    # 诊断工具类
    └── TestCases.java                  # 测试指南

core/src/test/java/bms/player/beatoraja/result/debug/
└── ResultFreezeDBLockTest.java         # JUnit 6 testcase
```

---

## Gauge 图延迟出现修复（2026-06-07）

### 现象

- Result 其他内容已经显示，但 gauge 推移图为空。
- 某些歌曲会等待数秒到数十秒后才出现。
- 问题与数据库旧分加载无直接关系，GL Thread 仍能继续渲染。

### 根因

`SkinGaugeGraphObject` 已把 Pixmap 构建移到 `GaugeGraphRebuildThread`。当 gauge 从
border 上方跌到下方时，下降穿越分支使用：

```java
y2 - yb + lineWidth
```

作为 `Pixmap.fillRectangle()` 的高度。此时 `y2 < yb`，高度可能为负数。负尺寸进入
libGDX 的 native Pixmap 后行为不可靠，在部分 Android 设备上会让后台构建长时间停滞。
由于纹理完成前 `draw()` 会直接返回，用户看到的就是 gauge 图迟迟不出现。

异步生命周期还有两个放大问题：

- 构建异常后只清除 `rebuildPending`，但下一帧没有根据 `redraw` 重试。
- 构建期间切换 gauge 类型或离开 Result，旧线程仍可能上传过期纹理。

### 修复

- 下降穿越 border 时使用 `min(y2, yb)` 和 `abs(y2 - yb)` 构造正高度矩形。
- 所有 Pixmap 矩形统一经过正尺寸保护函数。
- `prepare()` 将 `redraw` 纳入重建条件，失败后可自动重试。
- 上传前校验 gauge 类型、目标尺寸和对象是否已 dispose。
- 过期或已 dispose 的异步结果只释放 Pixmap，不创建 Texture。

### 真机回归

1. 使用 Normal/Easy 等存在 border 的 gauge 完成歌曲。
2. 覆盖 gauge 曾从 border 上方下降到下方的游玩结果。
3. 进入 Result 后确认背景和曲线立即进入正常的 1500ms 展开动画。
4. 在 Result 中切换 gauge 类型，确认图形刷新且不会被旧类型覆盖。
5. gauge 构建期间快速退出 Result，确认下一次进入 Result 仍能正常显示。
