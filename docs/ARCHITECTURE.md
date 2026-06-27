# beatoraja-Android 架构说明

> 核验日期：2026-06-10<br>
> 核验依据：当前仓库源码、Gradle 配置和 Android Manifest。<br>
> 本文是项目架构的主索引；具体问题分析仍放在 `docs/` 下的专题文档中。

## 1. 项目定位

beatoraja-Android 是 beatoraja 的 Android 移植版。它保留了原项目的大部分 Java 游戏核心，
使用 libGDX 承担跨平台渲染和输入，并在 Android 层替换或补充以下能力：

- 使用 `libgdx-oboe.aar` 提供低延迟音频。
- 使用 Android 原生 `SQLiteOpenHelper` 管理歌曲库和成绩库。
- 使用普通 Android `Activity` + XML 布局提供启动前设置页。
- 处理 Android 存储权限、外部目录、全面屏手势、高刷新率和生命周期恢复。
- 增加触摸按键、浮动菜单和音频频谱等移动端功能。

当前源码规模：

| 范围 | Java 文件 | 约计代码行 |
|---|---:|---:|
| `core/src/main/java` | 308 | 68,993 |
| `android/src/main/java` | 7 | 5,382 |
| `core/src/test/java` | 3 | 443 |

## 2. 技术与构建基线

| 项目 | 当前值 |
|---|---|
| 构建模块 | `:core`、`:android` |
| Gradle Wrapper | 9.4.0 |
| Android Gradle Plugin | 9.1.1 |
| Java | 17 |
| Kotlin 插件 | 1.9.22 |
| libGDX | 1.14.0 |
| gdx-video | 1.3.3 |
| gdx-controllers | 2.2.4 |
| Android compile/target SDK | 36 |
| Android min SDK | 21 |
| Android applicationId | `com.starxh.beatoraja` |
| Android versionName | `2.4` |
| JSON | Jackson 2.21.2 |
| Lua | LuaJ 3.0.1 |
| Android SQLite | 原生 SQLite + SQLDroid 依赖 |

重要事实：

- 设置页虽然位于 `android/.../compose/SettingsActivity.java`，但实际不是 Jetpack Compose。
  它继承 `android.app.Activity`，并加载 `res/layout/activity_settings.xml`。
- `settings.gradle` 只包含 `android` 和 `core`。根目录 `app/` 不参与当前构建。
- `libgdx-oboe/` 是 Git submodule 路径，但当前应用实际通过
  `android/libs/libgdx-oboe.aar` 引用音频实现。
- `android` 只打包 ARM 的 `armeabi-v7a` 和 `arm64-v8a` libGDX native 库。

## 3. 仓库地图

```text
beatoraja-Android/
├── android/                    Android 应用和平台实现
│   ├── AndroidManifest.xml
│   ├── build.gradle
│   ├── libs/libgdx-oboe.aar
│   ├── assets/                 APK 内置皮肤、音效、字体、着色器
│   ├── res/                    设置页 XML、字符串和图标
│   └── src/main/java/
│       ├── com/starxh/beatoraja/android/
│       │   ├── AndroidLauncher.java
│       │   ├── AudioSpectrumAdapter.java
│       │   └── compose/SettingsActivity.java
│       └── bms/player/beatoraja/
│           ├── song/           Android 歌曲数据库
│           └── score/          Android 成绩数据库
├── core/                       游戏核心和平台抽象
│   ├── build.gradle
│   └── src/
│       ├── main/java/
│       │   ├── bms/model/      BMS/BMSON 模型与解码
│       │   ├── bms/table/      难度表
│       │   ├── bms/tool/       下载/数据库辅助
│       │   ├── bms/player/beatoraja/
│       │   └── com/starxh/beatoraja/
│       └── test/java/          当前仅有结果页数据库锁测试
├── assets/                     根工程资源，目前主要供 core 资源任务使用
├── docs/                       问题分析、重构计划和性能专题
├── app/                        未纳入 Gradle 的遗留目录
├── libgdx-oboe/                Git submodule 工作目录
├── docs/ARCHITECTURE.md        本文
└── AGENTS.md                   项目记忆入口
```

## 4. 模块边界

### 4.1 `core`

`core` 是主业务模块，包含：

- BMS/BMSON 解码、谱面模型和模式定义。
- 游戏状态机、资源管理和配置模型。
- 选曲、决定、游玩、结果、按键配置和皮肤配置状态。
- 判定、Gauge、谱面随机、回放和成绩业务。
- JSON/Lua/LR2 CSV 三类皮肤加载器。
- 键盘、手柄、MIDI 和统一输入处理。
- 音频、歌曲库、成绩库的接口或抽象层。

`core` 应尽量避免直接依赖 Android SDK。现有 Android 交互主要通过：

1. libGDX 平台接口。
2. `MainLoader.setSongDatabaseAccessor(...)` 静态注入。
3. `ScoreDatabaseAccessor.setFactory(...)` 工厂注入。
4. 少量反射调用 `AndroidLauncher` 或 Android API。

### 4.2 `android`

`android` 依赖 `core`，负责：

- 两个 Activity 和 Android 生命周期。
- Oboe 音频初始化。
- 权限、目录和 ZIP 导入。
- Android 原生歌曲库与成绩库。
- 显示刷新率、Surface frame rate、Choreographer 相位同步。
- 系统返回键、软键盘/IME、全面屏边缘手势和屏幕常亮。
- APK 资源和 ABI native 库打包。

依赖方向应保持为：

```text
android  ───────────────>  core
  │                         │
  ├─ Android SDK            ├─ libGDX API
  ├─ Oboe AAR               ├─ 游戏业务
  └─ SQLite 实现             └─ 平台抽象
```

不要让 `core` 普遍 import Android 类。若需要新增平台能力，优先采用接口或 Factory 注入；
反射只适合已有兼容路径或很小的平台钩子。

## 5. 应用入口与启动流程

Manifest 定义两个 Activity：

| Activity | 角色 |
|---|---|
| `SettingsActivity` | `MAIN/LAUNCHER` 入口，读取和编辑配置 |
| `AndroidLauncher` | libGDX 游戏 Activity，横屏运行 |

启动链路：

```text
SettingsActivity.onCreate()
├── 根据系统语言更新 Resources
├── 读取 config_sys.json
├── 读取 player/<id>/config_player.json
├── setContentView(activity_settings.xml)
└── 用户点击启动
    ├── 保存系统和玩家配置
    └── startActivity(AndroidLauncher)

AndroidLauncher.onCreate()
├── 读取语言并设置 Locale
├── 检测 32/64 位进程
├── 申请存储权限
├── 设置 beatoraja.root = getExternalFilesDir(null)
├── 创建内部目录和 Download/beatoraja 目录
├── 导入内置资源/检查 ZIP
├── 注入 AndroidSQLiteSongDatabaseAccessor
├── 注入 AndroidScoreDatabaseAccessor Factory
├── 请求最高刷新率并启动 Choreographer 回调
└── initialize(BeatorajaGame)

BeatorajaGame.create()
└── new MainController(...)
    ├── 从磁盘读取 Config / PlayerConfig
    ├── 初始化输入、音频、资源和各 MainState
    └── changeState(MUSICSELECT)
```

权限尚未授予时，`AndroidLauncher` 会先初始化一个空的 `ApplicationAdapter`，等待权限结果后
再继续真实游戏初始化。修改启动流程时必须覆盖这个分支。

## 6. 核心运行时

### 6.1 `BeatorajaGame`

`com.starxh.beatoraja.BeatorajaGame` 是 libGDX `ApplicationAdapter`：

- `create()` 创建 `MainController` 和 `SideSpectrumRenderer`。
- `render()` 先运行主控制器，再按配置为游玩状态绘制频谱。
- `resize/pause/resume/dispose()` 转发生命周期。

它是 Android Activity 与游戏核心之间的最薄入口。

### 6.2 `MainController`

`MainController` 是运行时中枢，主要拥有：

- 当前 `MainState` 与各状态实例。
- `Config`、`PlayerConfig`、`PlayerResource`。
- `AudioDriver`、`BMSPlayerInputProcessor`、`TimerManager`。
- `SpriteBatch`、系统字体、消息渲染和浮动菜单。
- 歌曲更新、表更新、下载和截图等后台任务。

它同时承担状态切换、渲染编排、输入分发和 Android 帧率策略，职责较重。
新增功能时应先判断它是否真正属于全局生命周期；局部功能优先留在对应状态或子系统。

### 6.3 状态机

所有界面状态继承 `MainState`：

```text
MUSICSELECT  MusicSelector
DECIDE       MusicDecide
PLAY         BMSPlayer
RESULT       MusicResult
COURSERESULT CourseResult
CONFIG       KeyConfiguration
SKINCONFIG   SkinConfiguration
```

主要流程：

```text
MUSICSELECT -> DECIDE -> PLAY -> RESULT -> MUSICSELECT
                    \-> PLAY -> COURSERESULT

MUSICSELECT -> CONFIG
MUSICSELECT -> SKINCONFIG
```

`changeState()` 的关键行为：

- 离开状态时调用 `shutdown()` 并释放当前 skin。
- `PLAY` 每次创建新的 `BMSPlayer`。
- `MusicSelector` 只完整 `create()` 一次，后续返回时主要重新加载 skin。
- 切换后重建 `InputMultiplexer`，按状态加入 Stage、浮动菜单和触摸键。
- Android 结果页触摸会被映射为 `ESCAPE`。
- 32 位设备的选曲状态限制为 30 FPS；64 位设备选曲状态使用 1000 作为“不做应用层限帧”的哨兵值。

## 7. 渲染架构

`MainController.render()` 的高层顺序：

```text
计算 skin/配置分辨率
计算等比 viewport，保留黑边
current.render()                    状态自己的底层绘制
skin.drawAllObjects()               通用皮肤对象
Stage.act()/draw()                  Scene2D 控件
FPS + MessageRenderer
触摸指针
FloatingMenu
PlayTouchKeyMapper
输入事件处理
应用层帧率等待
```

核心特征：

- 使用 `SpriteBatch(4096)`。
- 使用 skin 尺寸作为逻辑坐标，屏幕坐标通过 viewport 转换为游戏坐标。
- 宽屏设备使用 pillarbox，窄屏/高屏使用 letterbox，避免皮肤拉伸。
- `resume()` 会重建系统字体、位图字体缓存、触摸指针和浮动菜单纹理。
- `SideSpectrumRenderer` 在 `BeatorajaGame.render()` 中位于主控制器绘制之后。

### 7.1 皮肤系统

加载入口是 `SkinLoader`，三类格式最终统一为 `Skin` 和 `SkinObject`：

```text
JSON     -> JSONSkinLoader
Lua      -> LuaSkinLoader
LR2 CSV  -> LR2SkinCSVLoader
               │
               v
        Skin + SkinObject[]
```

`core/.../skin` 是最大的子系统之一，包含 72 个 Java 文件。修改皮肤行为时要同时考虑：

- loader 层是否需要支持新字段。
- `SkinObject` 的 prepare/draw 生命周期。
- property factory 的 ID 映射。
- JSON、Lua、LR2 三种输入是否需要一致行为。
- Android GL context 恢复后的纹理重建。

## 8. 输入系统

`BMSPlayerInputProcessor` 统一管理：

- `KeyBoardInputProcesseor`
- `BMControllerInputProcessor`
- `MidiInputProcessor`

Android 额外增加：

- `FloatingMenu`
- `PlayTouchKeyMapper`
- 返回键到 `ESCAPE` 的映射
- 不同状态下的触摸手势模式
- `AndroidLauncher` 统一管理软键盘/IME 与硬件键盘焦点：非文本输入态下，USB/物理键
  事件会先确保 libGDX 的 `GLSurfaceView` 可聚焦并拿到焦点，再交给 libGDX；随后只清除
  隐藏 `EditText` 的残留焦点并隐藏 IME。不要为了抑制 IME 禁用或清除 `GLSurfaceView`
  焦点；`SearchTextField` 触摸进入文本输入态是允许弹出 IME 的例外。排查同类问题先读
  `docs/usb-keyboard-onscreen-ime-debug.md`。

输入轮询线程在 `MainController.create()` 中启动，当前实现硬编码为 1000 Hz：

```text
poll input
next += 1,000,000 ns
LockSupport.parkNanos(...)
```

`Config.inputPollingRate` 字段仍存在，但当前轮询实现和设置页已将可配置逻辑注释掉。
不要仅修改配置字段就认为轮询频率会变化。

判定时钟相关约束：

- `TimerManager` 的内置 timer 使用 `AtomicLongArray`，用于保证渲染线程、输入轮询线程和
  判定线程之间的可见性与 long 原子读写。
- `TIMER_PLAY` 存储的是用于 `now - timer[id]` 的播放起点偏移，不是直接存放当前播放时间。
- 正常速度下 `TIMER_PLAY` 由 `System.nanoTime()` 连续外推；变速时由
  `KeyInputProccessor.JudgeThread` 以 1000Hz 对该偏移做原子增量补偿，避免渲染帧率把判定
  时间量化成 8-16ms 阶梯。
- 不要在 `BMSPlayer.render()` / `STATE_PLAY` 中重新按渲染帧写 `TIMER_PLAY`；同类问题先读
  `docs/judge-clock-skew-analysis.md`。

## 9. 音频与频谱

### 9.1 音频注入

`AndroidLauncher.createAudio()`：

1. 尝试创建 `OboeAudio(assets, 44100)`。
2. 创建 `AudioSpectrumAdapter` 并注册为全局频谱 Provider。
3. 失败时回退 libGDX 默认 `AndroidAudio`。

`MainController.create()` 检查 `Gdx.audio instanceof AudioDriver`：

- Oboe AAR 提供兼容的 `AudioDriver` 时直接使用。
- 否则回退 `GdxSoundDriver`。

因此修改音频接口时必须同时检查：

- `core/.../audio/AudioDriver.java`
- `android/libs/libgdx-oboe.aar` 的兼容性
- `AndroidLauncher.createAudio()`
- `PlayerResource` 和按键音加载路径

### 9.2 频谱

```text
OboeAudio FFT
-> AudioSpectrumAdapter
-> AudioSpectrumManager
-> SideSpectrumRenderer
```

频谱只在 `BMSPlayer` 状态且 `Config.showAudioSpectrum` 开启时绘制。
游戏内位置可来自玩家配置、`spectrumconfig.json` 或内置默认值。

## 10. 谱面与游玩链路

```text
BMS/BMSON 文件
-> BMSDecoder / BMSONDecoder
-> BMSModel
-> PlayerResource.setBMSFile()
-> BMSPlayer.create()
-> LaneRenderer / JudgeManager / GrooveGauge / KeySoundProcessor
-> MusicResult
-> PlayDataAccessor / ScoreDatabaseAccessor
```

`bms.model.Mode` 当前定义 8 种模式：

- 5K、7K、10K、14K
- POP'N 5K、POP'N 9K
- 24K、24K Double

玩法相关代码主要位于：

| 包 | 职责 |
|---|---|
| `play` | 游玩状态、轨道、判定、Gauge、BGA、触摸键 |
| `pattern` | Random、Mirror、LN、Mine、练习等谱面修改 |
| `result` | 单曲和课程结果、成绩保存 |
| `audio` | PCM 与播放抽象 |

## 11. 歌曲库、成绩库与配置

### 11.1 平台注入

歌曲库：

```text
AndroidLauncher
-> new AndroidSQLiteSongDatabaseAccessor(...)
-> MainLoader.setSongDatabaseAccessor(...)
-> core 通过 SongDatabaseAccessor 使用
```

成绩库：

```text
AndroidLauncher
-> ScoreDatabaseAccessor.setFactory(...)
-> PlayDataAccessor 等通过 Factory 创建
-> AndroidScoreDatabaseAccessor
```

成绩库在 Android 上使用玩家目录下的单个 `score.db`，其中包含 `info`、`player`、`score`、
`scorelog` 和 `scoredatalog` 表。历史遗留的 `scorelog.db`、`scoredatalog.db` 只作为迁移来源
读取并备份；运行时查询不要再把它们当成独立数据库文件。

选曲的 `CommandBar`、随机课程和默认 `folder/default.json` 中的成绩条件仍通过
`SongDatabaseAccessor.getSongDatas(sql, score, scorelog)` 进入歌曲库。Android 实现不使用
`ATTACH DATABASE`，而是读取 `score.db` 中的 `score`/`scorelog` 到内存后对项目内常见 SQL
形态做 Java 侧过滤；普通 song/information 条件则直接查询歌曲库，并通过
`song LEFT JOIN information` 支持 density、peakdensity、enddensity 文件夹。

歌曲扫描采用多阶段流程：

1. 收集 BMS/BMSON 文件。
2. 根据数据库日期缓存过滤未变化文件。
3. 使用固定线程池并行解码。
4. 批量写入 SQLite。
5. 更新选曲 Bar。

数据库访问涉及 GL 线程、扫描线程和结果保存线程。修改 SQL 或锁策略前，应先阅读：

- `docs/score-database-refactor-plan.md`
- `docs/musicresult-thread-analysis.md`
- `docs/result-freeze-fix.md`

### 11.2 存储布局

应用私有外部目录：

```text
Android/data/com.starxh.beatoraja/files/
├── config_sys.json
├── songdata.db
├── player/<player-id>/
│   ├── config_player.json
│   └── score.db
├── table/
├── songinfo/
├── irconfig/
├── sound/
├── bgm/
└── font/
```

公共下载目录：

```text
Download/beatoraja/
├── songs/
└── skins/
```

`AndroidLauncher` 将私有外部目录写入系统属性 `beatoraja.root`。
`Config` 和 `PlayerConfig` 依赖这个属性解析配置、玩家和相对资源路径。

### 11.3 设置页

`SettingsActivity` 直接读写 JSON 文本，并没有复用 `Config`/`PlayerConfig` 的完整 Jackson
序列化流程。它负责：

- 系统、按键音和 BGM 音量。
- 玩家选择和导入。
- BMS 根目录与难度表 URL。
- Gauge、Random、Hispeed、Lane cover、Lift、BGA 等常用选项。
- 成绩库导入导出。

这是一个兼容风险点：新增或重命名配置字段时，必须同时检查设置页的手写 JSON 解析和保存逻辑。

## 12. 并发模型

主要线程/任务：

| 线程或任务 | 职责 |
|---|---|
| Android UI 线程 | Activity、权限、窗口和 Surface 设置 |
| libGDX GL 线程 | 状态更新、渲染和大部分游戏逻辑 |
| 1000 Hz 输入线程 | `input.poll()` |
| `SongUpdateThread` | 歌曲扫描协调 |
| 歌曲扫描固定线程池 | 并行谱面解码 |
| 结果页后台线程 | IR、成绩或资源相关任务 |
| 截图线程 | 像素后处理与文件导出 |
| Choreographer callback | 提供最新 VSync 相位 |

注意事项：

- 不要在后台线程直接操作 OpenGL 资源。
- SQLite 写入和结果页切换存在历史卡顿问题，改动前先检查专题文档和测试。
- 当前输入轮询线程是无限循环，`MainController.dispose()` 没有显式停止它。
- 状态切换会清理模拟按键，防止上一状态的输入泄漏。

## 13. 帧率与 Android 性能策略

刷新率控制分三层：

1. `WindowManager.LayoutParams.preferredRefreshRate`
2. Android 11+ `Surface.setFrameRate(...)`
3. `MainController.render()` 末尾的绝对时间应用层限帧

Choreographer 持续把 VSync 时间戳传给 `MainController`，应用层等待会尝试对齐该相位。

状态策略：

| 场景 | 目标 |
|---|---|
| 32 位设备选曲 | 30 FPS |
| 64 位设备选曲 | `1000` 哨兵，不做应用层限帧 |
| PLAY/RESULT 等 | 检测到的硬件最高刷新率 |

当前 `keepAliveRunnable` 只周期性重新调度自身，不再制造模拟触摸。
不要沿用旧文档中“伪触摸防降频”的描述。

## 14. 已知结构性风险

这些不是本次要修复的 bug，而是后续修改时应优先留意的架构事实：

1. `MainController` 同时管理状态、渲染、输入和帧率，改动影响面大。
2. Android 与 core 之间仍有反射和静态全局注入，测试隔离较弱。
3. `SettingsActivity` 使用手写 JSON 查找/替换，容易与配置模型漂移。
4. `android/compose` 包名具有误导性，实际 UI 是 XML View。
5. `app/` 是未参与构建的重复数据库 helper，修改它不会影响 APK。
6. `libgdx-oboe` 子模块与打包 AAR 可能出现版本漂移。
7. 自动化测试极少，当前只有一个结果页数据库锁相关测试。
8. 输入轮询线程没有显式停止机制。
9. `core/.../launcher` 保留桌面 launcher/FXML 遗留代码，但 Android 启动链不使用它。

## 15. 修改导航

| 需求 | 首先查看 |
|---|---|
| Android 启动/权限/目录 | `AndroidLauncher.java` |
| 设置项和配置保存 | `SettingsActivity.java`、`Config.java`、`PlayerConfig.java` |
| 状态切换/全局渲染 | `MainController.java`、`MainState.java` |
| 选曲 | `select/MusicSelector.java`、`select/BarManager.java` |
| 游玩/判定 | `play/BMSPlayer.java`、`JudgeManager.java`、`LaneRenderer.java` |
| 输入延迟/触摸 | `input/`、`PlayTouchKeyMapper.java`、`FloatingMenu.java` |
| 音频/按键音 | `audio/`、`AndroidLauncher.createAudio()`、Oboe AAR |
| BGA/视频 | `play/bga/`、gdx-video Android backend |
| 皮肤 | `skin/`、`SkinLoader.java`、对应格式 loader |
| 歌曲扫描 | `AndroidSQLiteSongDatabaseAccessor.java` |
| 成绩保存/结果卡顿 | `MusicResult.java`、`PlayDataAccessor.java`、`AndroidScoreDatabaseAccessor.java` |
| 频谱 | `AudioSpectrumAdapter.java`、`AudioSpectrumManager.java`、`SideSpectrumRenderer.java` |

## 16. 构建与核验命令

Windows：

```powershell
.\gradlew.bat projects
.\gradlew.bat :core:test
.\gradlew.bat :android:assembleDebug
```

安装和启动通常还需要本机 Android SDK、`local.properties` 或 `ANDROID_SDK_ROOT`。

## 17. 项目记忆约定

后续维护本项目时，默认遵循：

1. 先读 `AGENTS.md` 和 `docs/ARCHITECTURE.md`，再读对应专题文档。
2. 以 `settings.gradle` 判断真实模块，不把 `app/` 当成运行时代码。
3. 把 `SettingsActivity` 视为 XML View Activity，不按 Compose 方案修改。
4. 保持依赖方向 `android -> core`。
5. 配置字段变化必须同时核对设置页、Config、PlayerConfig 和磁盘兼容性。
6. 数据库或结果页并发变化必须补充测试，并核对 `docs/` 中已有分析。
7. 音频变化必须考虑 AAR 边界和默认 AndroidAudio 回退路径。
8. 文档与源码不一致时，以源码和构建文件为准，并同步更新本文。
