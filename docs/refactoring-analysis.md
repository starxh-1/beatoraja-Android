# beatoraja Android 重构分析报告

## 一、KeyConfiguration 键位配置重构

### 1.1 现状分析

**当前代码位置：**
- `core/src/main/java/bms/player/beatoraja/config/KeyConfiguration.java` (823行)
- `core/src/main/java/bms/player/beatoraja/input/KeyBoardInputProcesseor.java` (619行)
- `core/src/main/java/bms/player/beatoraja/input/BMControllerInputProcessor.java` (481行)
- `core/src/main/java/bms/player/beatoraja/input/XboxControllerHelper.java` (131行)

**上游参考代码：**
- `endlessdream-upstream-src/bms/player/beatoraja/config/KeyConfiguration.java` (614行)

### 1.2 问题清单

#### 问题1: 手柄支持不完整 — 仅7KEYS界面支持Gamepad映射

当前代码在以下模式中，Gamepad按键映射功能不正常工作：
- 5KEYS 模式
- 9KEYS 模式
- 10KEYS 模式 (14KEYS)
- 24KEYS DOUBLE 模式

**根因分析：**

当前 `KeyConfiguration.java` 在 `pollControllerNavShortcuts()` 方法中仅针对 `7KEYS` 模式的按钮数做了硬编码映射。`XboxControllerHelper` 的 `mapControllerToGameKey()` 方法中的按键映射逻辑与当前 `KEYS[][]` 阵列和 `KEYSA[][]` 阵列不匹配。具体来说：

- `create()` 中 `input.updateControllers()` 调用延迟导致不同模式切换时，控制器引用未能及时更新
- `render()` 中 `keyinput == false` 分支调用的 `pollControllerNavShortcuts()` 需要针对每种游戏模式单独适配
- `setControllerKeyAssign()` 的 player offset 计算（`index >= 100 ? index/100 : 0`）在某些模式下不正确

**需要修复：** 参考上游 `KeyConfiguration.java` 中的控制器轮询逻辑，但保留现有的 player offset (100) 和 2P 支持。

#### 问题2: Xbox 键位映射错误

用户已指出：BUTTON 98, 99, 100 等数字在 Android 上的映射存在错误。这些代码对应 Android 系统层面对 Xbox 手柄的重新编号。

当前 `XboxControllerHelper.java` 中定义的映射：
```java
ANDROID_BUTTON_A = 97    // 应为 96
ANDROID_BUTTON_B = 98
ANDROID_BUTTON_X = 100
ANDROID_BUTTON_Y = 101
ANDROID_DPAD_UP = 20
ANDROID_DPAD_DOWN = 21
ANDROID_DPAD_LEFT = 22
ANDROID_DPAD_RIGHT = 23
```

**注意：** 用户的说明指出这些值已经是用户修正过的正确值，需要在重构时保留，不能被上游代码覆盖。

另外，`BMControllerInputProcessor.BMKeys.toString()` 中对于 BUTTON 编号的显示名也需要同步更新，以及 `mapControllerToGameKey()` 和 `mapControllerToSystemKey()` 中的逻辑需要保持一致。

#### 问题3: Enter/Delete/数字键过滤逻辑不正确

**当前行为：**
- 按下 ENTER 进入键位分配模式 (`keyinput = true`)
- 在分配模式下，`isReservedKey()` 阻止 ENTER、DELETE、F1-F12、方向键等被分配为游戏按键
- 数字键 1-9 在 `keyinput == false` 时触发 Reset 操作（NUM1=切换输入设备, NUM2=切换手柄, NUM3=切换手柄2, NUM7=重置键盘, NUM8=重置手柄, NUM9=重置MIDI）
- 因此 789 以及 123 数字键被过滤，无法作为游戏键位

**期望行为：**
- 进入键位分配模式后，**所有键位**（包括 ENTER、DELETE、数字1-9、方向键等）都应该可以作为映射键位被捕获
- ENTER、DELETE、数字键的快捷操作（切换设备、重置键位等）应该在**键位映射完成之后**（即 `keyinput == false` 时）才触发
- 这与上游代码的设计一致：上游 `KeyConfiguration` 没有这样的过滤，因为它将 ALL ControlKeys 都加入了 reserved 列表——但这样连分配都做不了

**修复方案：**

修改 `KeyBoardInputProcesseor.isReservedKey()`：在 `KeyConfiguration` 的键位分配模式下（keyinput == true），所有键位都应该被接受，不进行 reserved 检查。或者更简单的方式是：在 `setKeyAssignMode()` 时设置一个计数器/延迟，让分配模式下的按键直接进入 `setKeyboardKeyAssign()`，而不是先被 `render()` 中的 ControlKeys 处理拦截。

具体修改 `KeyConfiguration.render()` 中的按键处理顺序：
1. 当 `keyinput == true` 时：**优先处理**键盘/手柄/MIDI的输入，直接分配给当前槽位
2. 当 `keyinput == false` 时：才处理 ControlKeys（导航、重置等快捷键）
3. 在 `setKeyAssignMode()` 调用时，清除所有 pending 按键状态，避免残留按键触发

**当前 render() 的处理顺序问题：**
```java
// render() 中当前的处理顺序：
// 1. 先 poll 所有 ControlKeys (ENTER/DELETE/NUM1-9/UP/DOWN...)
// 2. 然后检查 keyinput 标志
// 这导致：即使 keyinput == true，ENTER/DELETE 也被先消费了

// 正确顺序应该是：
// 1. 先检查 keyinput，如果为 true，所有输入直接映射
// 2. keyinput == false 时才处理导航快捷键
```

---

## 二、歌曲加载 / SQLite 线程问题

### 2.1 现状分析

**关键文件：**
- `android/src/main/java/bms/player/beatoraja/song/AndroidSQLiteSongDatabaseAccessor.java` (2106行)
- `core/src/main/java/bms/player/beatoraja/MainController.java` (SongUpdateThread)
- `core/src/main/java/bms/player/beatoraja/select/MusicSelector.java`
- `core/src/main/java/bms/player/beatoraja/DatabaseUtils.java`

### 2.2 问题清单

#### 问题1: BMS文件扫描线程池未充分利用CPU

**当前线程配置 (`AndroidSQLiteSongDatabaseAccessor.java:51-54`):**
```java
private static final int PARALLEL_THREAD_COUNT = 
    "true".equals(System.getProperty("beatoraja.32bit"))
        ? Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 4))
        : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
```

在64位Android设备上，通常是 `availableProcessors - 1`（比如8核设备 = 7线程）。这个数字看起来合理，但实际问题是：

1. **线程池仅在 `forceRefresh` 路径中使用** — `scanFolderRecursively()` 使用串行扫描，仅在 `updateSongDatasParallel()` 中使用并行路径
2. **两个独立的线程池** — 删除检查（line 928）和并行扫描（line 1069）各创建独立的 `Executors.newFixedThreadPool`，不能复用
3. **写入阶段完全串行** — 解码结果通过单个 `Future.get()` 循环写入数据库，一次只处理一个文件

#### 问题2: 主线程阻塞数据库查询

`BarManager.updateBar()` → `FolderBar.getChildren()` → `songdb.getSongDatas(...)` — 这是在 GL 渲染线程上执行的同步 SQLite 查询。当并行扫描持有写锁时，此查询会阻塞 UI。

#### 问题3: 无界原始线程创建

代码中大量使用 `new Thread(...).start()` 模式：
- `MusicSelector.create()` — 启动扫描线程
- `BMSResource` — BGA和音频加载
- `BarContentsLoaderThread` — 分数/图片加载
- `MusicSelector.render()` — 乐谱模型加载

这些线程没有池化，每次操作创建新线程有开销，且无上限控制。

#### 问题4: 数据竞争

- `MainLoader.illegalSongs` — `HashSet` 在扫描线程写入、渲染线程读取，无同步
- `tags`/`favorites` — 普通 `HashMap` 在并行解码任务中读取，无 happens-before 保证
- `BarContentsLoaderThread.stop` — 非 volatile 布尔标志

### 2.3 优化方案

1. **增加并行线程数**：对于文件扫描/解码这种 I/O 密集型操作，线程数应该超过 CPU 核心数。可以调整为 `availableProcessors * 2` 或使用 `CachedThreadPool`
2. **合并线程池**：删除检查和并行扫描共用同一个 `ExecutorService`
3. **并行化写入阶段**：使用批量 INSERT 而非逐条写入，减少事务开销
4. **数据库查询异步化**：将 `getSongDatas()` 调用从 GL 线程移到后台线程，通过 `Gdx.app.postRunnable()` 返回结果
5. **使用共享线程池**：在 `MainController` 中创建全局 `ExecutorService`，供所有后台任务使用

---

## 三、皮肤加载线程问题

### 3.1 现状分析

**关键文件：**
- `core/src/main/java/bms/player/beatoraja/skin/SkinLoader.java` (377行)
- `core/src/main/java/bms/player/beatoraja/skin/lr2/LR2SkinCSVLoader.java` (1094行)
- `core/src/main/java/bms/player/beatoraja/skin/json/JSONSkinLoader.java` (611行)
- `core/src/main/java/bms/player/beatoraja/skin/Skin.java` (799行)
- `core/src/main/java/bms/player/beatoraja/PixmapResourcePool.java` (200行)

### 3.2 问题清单

#### 问题1: 皮肤加载完全无并行化

**整个皮肤加载流程是单线程同步的，运行在主渲染线程上：**

```
MainController render thread
  → MainState.loadSkin(SkinType)               [同步，主线程]
    → SkinLoader.load()                         [同步，主线程]
      → 格式检测 (.json / .lr2skin / .luaskin)
      → LR2SkinCSVLoader.loadSkin()            [同步，逐行CSV解析]
        → 每个 #IMAGE 命令 → 同步 Pixmap 解码
        → 每个 SRC_* 命令 → 同步纹理加载
      → 或 JSONSkinLoader.load()               [同步，JSON解析+对象创建]
        → 每个 getSource() → 同步 Pixmap 解码
```

**没有任何 ExecutorService、ForkJoinPool 或 CompletableFuture 被使用**。皮肤加载相关的所有代码（约4000行）中没有使用任何线程池。

#### 问题2: 图片解码顺序执行，无法吃满CPU

对于典型播放皮肤（50-200张图片），每个 `#IMAGE` 命令：
1. 检查文件是否存在（磁盘 I/O）
2. `new Pixmap(FileHandle)` 解码 PNG/JPEG（CPU 密集型）
3. 可选的颜色转换和缩放（CPU 密集型）

这些操作完全顺序执行。在 8 核设备上，最多只有 1 个核心在工作。

#### 问题3: LR2皮肤解析与图片加载混合

`LR2SkinCSVLoader.loadSkin0()` 在解析 CSV 行的同时加载图片。这导致：
- 解析和 I/O 交替阻塞，无法流水线化
- 一个慢速的图片加载会阻塞整个解析流程

虽然后续的 `ImageEntry` 重构将纹理加载延迟到了 `SRC_*` 命令处理阶段，但 `SRC_*` 阶段的加载仍然是顺序的。

#### 问题4: JSON皮肤 eager loading

`JSONSkinLoader.getSource()` 为每个 source 引用立即调用 `getTexture()`，进行同步 Pixmap 解码。`getNoteTexture()` 也顺序迭代所有音符图片。

### 3.3 优化方案

1. **创建专用皮肤加载线程池**：`Executors.newFixedThreadPool(availableProcessors)` 用于并行图片解码
2. **解耦解析与I/O**：
   - 第一阶段：解析所有 CSV/JSON/Lua 文件，收集所有需要的图片路径列表
   - 第二阶段：使用 `ExecutorService.invokeAll()` 并行加载所有图片到 Pixmap
   - 第三阶段：创建皮肤对象（在 GL 线程上创建 Texture）
3. **流水线化加载**：解析线程和图片解码线程并行工作
4. **替换原始 Thread**：将 `BMSResource` 和其他地方的 `new Thread()` 替换为共享的 `ExecutorService`
5. **延迟加载优化**：确保真正不立即需要的纹理保持延迟加载，直到首次渲染

---

## 实施优先级

| 优先级 | 问题 | 影响 | 复杂度 |
|--------|------|------|--------|
| P0 | KeyConfig Enter/Delete/数字键过滤 | 功能性bug，用户无法绑定特定键位 | 低 |
| P0 | 手柄支持不完整（5K/9K/10K/24K） | 功能性缺失，影响所有非7K模式 | 中 |
| P1 | 皮肤加载并行化 | 显著性能提升，进入游戏/选歌界面加速 | 高 |
| P1 | 歌曲扫描线程池优化 | 加载速度提升，UI流畅度改善 | 中 |
| P2 | Xbox键位映射确认/修正 | 用户已修正，需保留 | 低 |
| P2 | 数据库查询异步化 | UI卡顿减少 | 中 |
| P3 | 全局线程池统一 | 代码质量提升，资源管理改善 | 高 |
