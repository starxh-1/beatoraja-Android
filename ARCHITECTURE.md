# beatoraja-Android 架构说明 (v3)

## 1. 项目概述

beatoraja-Android 是 [beatoraja](https://github.com/exch-bms2/beatoraja) 的 Android 移植版。beatoraja 是一款纯 Java 开发的 BMS（Be-Music Source）音乐游戏播放器，支持多种按键模式（5K/7K/9K/10K/14K/24K/24K Double）和多种谱面格式（BMS、BMSON 等）。Android 版使用 **Oboe** 低延迟音频引擎替代原有的桌面音频后端，并使用 **Jetpack Compose** 作为设置界面框架。

### 技术栈

| 层面 | 技术 |
|------|------|
| 游戏框架 | libGDX 1.14.0 |
| 音频引擎 | libgdx-oboe (Google Oboe) |
| UI 框架 | Jetpack Compose + Material3（替代 JavaFX） |
| 脚本引擎 | LuaJ 3.0.1 (Lua) |
| 数据库 | SQLDroid (Android) / SQLite JDBC (Core) |
| JSON 解析 | Jackson 2.21.2 |
| 混淆 | R8 (ProGuard) |
| 最低 API | Android 5.0 (API 21) |
| 编译 SDK | Android 16 (API 36) |
| Java 版本 | Java 17 |
| Kotlin 版本 | 1.9.22 |
| Gradle 版本 | 9.1.1 (AGP) |

---

## 2. 模块结构

```
beatoraja-Android/
├── android/          Android 应用模块（平台特定代码）
│   ├── src/main/java/com/starxh/beatoraja/android/
│   │   ├── AndroidLauncher.java           libGDX 游戏启动 Activity
│   │   ├── AudioSpectrumAdapter.java      频谱适配器（桥接 Oboe）
│   │   └── compose/SettingsActivity.java  设置/启动界面（Compose + XML）
│   ├── src/main/java/bms/player/beatoraja/song/
│   │   ├── AndroidSQLiteSongDatabaseAccessor.java  Android SQLite 歌曲数据访问
│   │   └── SongDatabaseHelper.java         歌曲数据库帮助类
│   ├── res/                                Android 资源（多语言 strings, layout, 图标）
│   ├── assets/                             游戏资源（皮肤、字体、着色器、曲包）
│   ├── libs/library-release.aar            第三方 AAR 库
│   └── patches/oboe_engine.cpp             Oboe 音频引擎原生补丁
│
├── core/             核心游戏逻辑模块（平台无关）
│   └── src/main/java/
│       ├── bms/model/                      BMS/BMSON 谱面数据模型 (34 files)
│       │   ├── BMSModel.java               谱面内存表示
│       │   ├── BMSDecoder.java             BMS 文件解析器
│       │   ├── BMSONDecoder.java           BMSON 文件解析器
│       │   ├── TimeLine.java               时间线
│       │   ├── Mode.java                   游戏模式枚举（8种）
│       │   ├── Note.java / NormalNote.java / LongNote.java / MineNote.java
│       │   └── bmson/                      BMSON JSON 数据结构
│       │
│       ├── bms/player/beatoraja/           播放器核心 (243 files)
│       │   ├── MainController.java         中央控制器 ★
│       │   ├── MainState.java              状态机抽象基类 ★
│       │   ├── Config.java                 系统级全局配置
│       │   ├── PlayerConfig.java           玩家级个性化配置
│       │   ├── ShaderManager.java          GLSL 着色器管理
│       │   ├── TimerManager.java           高精度定时器管理
│       │   ├── FloatingMenu.java           浮动快捷键菜单
│       │   │
│       │   ├── audio/                      音频驱动层 (9 files)
│       │   ├── config/                     按键/皮肤配置 (4 files)
│       │   ├── decide/                     选曲确定界面 (2 files)
│       │   ├── external/                   BMS 搜索/截图导出 (5 files)
│       │   ├── input/                      输入处理 (9 files)
│       │   ├── ir/                         Internet Ranking (11 files)
│       │   ├── launcher/                   JavaFX 遗留配置界面 (17 files)
│       │   ├── play/                       游玩核心 (27 files)
│       │   │   └── bga/                    BGA 子系统
│       │   ├── result/                     结果界面 (7 files)
│       │   ├── select/                     选曲界面 (27 files)
│       │   │   └── bar/                    Bar 类型体系 (13 files)
│       │   ├── skin/                       皮肤系统 (65 files)
│       │   │   ├── json/                   JSON 皮肤 (11 files)
│       │   │   ├── lr2/                    LR2 CSV 皮肤 (10 files)
│       │   │   ├── lua/                    Lua 皮肤 (5 files)
│       │   │   └── property/              属性系统 (13 files)
│       │   ├── song/                       歌曲数据库 (6 files)
│       │   └── stream/                     直播推流 (3 files)
│       │
│       └── com/starxh/beatoraja/           Android 特有核心类
│           ├── BeatorajaGame.java          libGDX ApplicationAdapter 入口
│           ├── AudioSpectrumProvider.java  频谱数据提供接口
│           ├── AudioSpectrumManager.java   全局频谱管理器
│           └── SideSpectrumRenderer.java  侧边频谱渲染器
│
└── app/              辅助模块（仅含基础 SongDatabaseHelper）

总计: 279 个 Java 源文件
```

---

## 3. 启动流程与入口架构

### 3.1 双 Activity 架构

```
AndroidManifest.xml
├── SettingsActivity  (LAUNCHER 启动器入口)
│   - XML 布局读取/编辑 config_sys.json 和 config_player.json
│   - 管理 BMS 目录、玩家档案、Table URL、难度表更新
│   - 可配置的音量/采样率/BGA/谱面选项/皮肤
│   - JSON 配置读写、玩家导入导出、数据库导出
│   - 点击"启动游戏" → startActivity(AndroidLauncher)
│
└── AndroidLauncher   (游戏入口，横屏锁定)
    - 继承 AndroidApplication (libGDX)
    - 初始化 OboeAudio（替代默认 AndroidAudio）
    - 创建 BeatorajaGame → MainController
    - 处理存储权限、高刷新率请求、屏幕常亮
    - Back 键映射为 ESCAPE
```

### 3.2 启动时序

```
SettingsActivity
  └─ readConfigDirectly()           从 config_sys.json 读取配置
  └─ launchGame()                   startActivity(AndroidLauncher)
       └─ AndroidLauncher.onCreate()
            ├─ detectArchitecture()         检测 32/64 位设备
            │   └─ 32bit → System.setProperty("beatoraja.32bit", "true")
            ├─ checkStoragePermissions()    请求存储权限
            ├─ createDefaultDirectories()   创建 songs/skins/table/songinfo 等目录
            ├─ ensureExternalSkinZip()      解压外部皮肤 ZIP
            ├─ ensureExternalSongZip()      解压外部曲包 ZIP
            ├─ AndroidSQLiteSongDatabaseAccessor  初始化歌曲数据库
            ├─ setupHighRefreshRate()       检测硬件最大刷新率 → AndroidLauncher.maxRefreshRate
            ├─ initialize(BeatorajaGame)    启动 libGDX 游戏循环
            ├─ setupHighRefreshRate()       再次请求刷新率（GL Surface 已就绪）
            ├─ setupSurfaceFrameRate()      延迟 200ms 调用 Surface.setFrameRate() (Android 11+)
            └─ setupSustainedPerformance()  持续性能模式 + KeepAlive 机制
                 └─ BeatorajaGame.create()
                      └─ MainController()
                           ├─ 构造器读取 Config + PlayerConfig
                           ├─ 检测硬件刷新率 (AndroidLauncher.maxRefreshRate)
                           ├─ updateFrameRateAPI(targetFPS)  反射调用 Surface.setFrameRate
                           └─ MainController.create()
                                ├─ GL 优化 (禁用 depth/stencil/dither, 设置 blend 模式)
                                ├─ 加载系统字体 (VL-Gothic 24pt/18pt)
                                ├─ 初始化 AudioDriver (OboeAudio)
                                ├─ 创建 InputProcessor + 独立高精度输入轮询线程
                                ├─ 创建 MusicSelector（选曲状态）
                                ├─ 触发异步歌曲数据库扫描
                                ├─ JIT 预热 (3 帧空渲染)
                                └─ changeState(MUSICSELECT)
```

---

## 4. 核心架构：MainController 与状态机

### 4.1 MainController（中央控制器）

`MainController` 是整个应用的**中枢**，管理所有子系统和状态切换。

**核心字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `selector` | `MusicSelector` | 选曲界面 |
| `decide` | `MusicDecide` | 选曲确定界面 |
| `bmsplayer` | `BMSPlayer` | 游玩界面 |
| `result` | `MusicResult` | 单曲结果界面 |
| `gresult` | `CourseResult` | 段位结果界面 |
| `current` | `MainState` | 当前活动状态 |
| `config` | `Config` | 系统配置 |
| `player` | `PlayerConfig` | 玩家配置 |
| `audio` | `AudioDriver` | 音频驱动 |
| `input` | `BMSPlayerInputProcessor` | 统一输入管理 |
| `timer` | `TimerManager` | 定时器管理 |
| `resource` | `PlayerResource` | 玩家资源管理 |
| `floatingMenu` | `FloatingMenu` | Android 浮动快捷键菜单 |
| `detectedRefreshRate` | `int` | 检测到的硬件最大刷新率 |
| `currentTargetFPS` | `int` | 当前实际目标帧率 |
| `is32BitARM` | `boolean` | 32位设备标记（影响帧率策略） |

### 4.2 状态机 (MainState)

采用 **State Pattern**，所有界面状态继承自 `MainState` 抽象基类：

```
MainState（抽象基类）
├── MusicSelector     选曲界面
├── MusicDecide       确认界面
├── BMSPlayer         游玩界面
├── MusicResult       单曲结果界面
├── CourseResult      段位结果界面
├── KeyConfiguration  按键配置界面
└── SkinConfiguration 皮肤配置界面
```

**状态切换流程**：

```
MUSICSELECT ──选曲──→ DECIDE ──开始──→ PLAY ──结束──→ RESULT ──返回──→ MUSICSELECT
                   ↓                        ↓
              COURSERESULT               CONFIG / SKINCONFIG
```

**状态切换时的帧率策略（关键）**：

```
32位设备 + MusicSelect → FPS 限制为 30（降低 GPU 负载）
64位设备 + MusicSelect → 不限帧 (1000)
其他界面（PLAY/RESULT等）→ 恢复到 detectedRefreshRate
目标 FPS < 1000 时 → 调用 updateFrameRateAPI() 告知系统
```

MusicSelector 仅在首次创建时 `create()`（扫描数据库），后续切回时跳过 create 仅重新加载 skin，大幅减少切回选曲界面的开销。切到 PLAY 时依赖 `bmsplayer.dispose()` 释放前一界面的内存，**不**再手动 `System.gc()` — 强制 GC 不可靠且会引发长 STW 帧率抖动。

---

## 5. 图形引擎与渲染管线深度分析 ★

### 5.1 渲染架构总览

```
MainController.render()                          每帧主循环
│
├── GL 线程优先级提升 (THREAD_PRIORITY_DISPLAY)
├── 等比视口计算 (pillarbox/letterbox)
│   └── viewportX/Y/W/H 存入字段供 screenToGameX/Y 坐标转换
│
├── glClear 全屏 → glViewport 设置等比区域
├── current.render()                            状态渲染
│   ├── PLAY 状态: LaneRenderer.drawLane()  → 判定区/BGA背景/小节线/音符
│   └── SELECT 状态: BarRenderer.draw() + Skin
│
├── sprite.begin()
│   └── skin.drawAllObjects(sprite, current)    SkinObject 绘制 (一次 begin/end)
├── sprite.end()
│
├── stage.act() + stage.draw()                  Scene2D 叠加层
│
├── sprite.begin()                              第二对 begin/end
│   ├── FPS 显示
│   └── 消息渲染 (MessageRenderer)
├── sprite.end()
│
├── sprite.begin()                              第三对 begin/end (条件性)
│   ├── 触摸指针 + 坐标文字
│   └── 浮动菜单渲染
├── sprite.end()
│
├── sprite.begin()                              第四对 begin/end (条件性)
│   └── 触摸按键渲染 (PlayTouchKeyMapper)
├── sprite.end()
│
└── 精确帧率控制 (3阶段睡眠策略)
```

### 5.2 SpriteBatch 架构

- **缓冲区大小**: 4096（`new SpriteBatch(4096)`），相较默认 1000 大幅提升，减少高 BPM 谱面数百音符同时渲染时的 flush 次数
- **投影矩阵**: 预分配的 `Matrix4` 对象 (`projMatrix`)，避免每帧 `new Matrix4()` 的 GC 压力
- **多 begin/end 问题**: 当前渲染循环中有 2~4 对 `sprite.begin()/end()`，每次 end 都会触发一次 GPU flush。理想情况下应合并为单次 begin/end，但当前架构受限于不同阶段需要不同的投影矩阵和混合模式

### 5.3 SkinObjectRenderer（皮肤对象渲染器）

`SkinObjectRenderer` 封装了 `SpriteBatch` 的底层绘制，提供以下优化：

| 优化项 | 实现 |
|--------|------|
| Shader 缓存 | 6 种 shader 预加载到数组，switch 时检查当前 != 目标才切换 |
| Blend 状态缓存 | `activeBlend` 字段追踪当前 GL 混合状态，避免冗余 `glBlendFunc` 调用 |
| 纹理过滤 | Default skin 强制 Nearest 过滤（降低 GPU 带宽），其他 skin 仅在需要时设置 Linear |
| 视口裁剪 | ThreadLocal 存储当前视口，供 SkinObject.prepare() 读取判断是否跳过渲染 |
| 字体绘制优化 | 无 Consumer callback 时的快速路径避免不必要的 `sprite.flush()` |
| 偏移修正 | TextureRegion 绘制时 +0.01f 补偿 Windows 下半像素对齐问题 |

支持的渲染类型（6种）：

```
TYPE_NORMAL (0)          默认渲染 → 无 shader
TYPE_LINEAR (1)          线性过滤 → 无 shader
TYPE_BILINEAR (2)        双线性过滤 → bilinear shader (framebuffer blit 用)
TYPE_FFMPEG (3)          [已废弃]
TYPE_LAYER (4)           图层混合 → layer shader
TYPE_DISTANCE_FIELD (5)  距离场字体 → distance_field shader (含硬编码 fallback)
```

### 5.4 LaneRenderer 音符渲染优化

[LaneRenderer.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/play/LaneRenderer.java) 包含以下关键渲染优化：

- **Timeline 预过滤**: `init()` 中仅保留含 BPM/STOP 变化、Note 存在或 SectionLine 的 Timeline，减少遍历量
- **字符串预缓存**: BPM 文本、时间文本、STOP 文本在初始化时一次性 `String.format()` 缓存到 `cachedTimeText[]` 数组
- **竖屏模式缓存**: `cachedSkinForPortrait` 避免每帧重复检测 skin 的 portrait 选项
- **视口可见性裁剪**: 音符绘制前检查 `dsty + dsth < visibleViewport.y` 等边界，跳过视口外元素
- **字体复用**: 使用 MainController 预加载的 18pt 系统字体 `getSystemFont18()`
- **BGA 帧缓冲复用**: Touchscreen 皮肤的 BGA 背景使用预渲染的 `getCurrentBGAFrame()` 纹理
- **颜色状态重置**: 每个 timeline 迭代开始强制 `sprite.setColor(1f,1f,1f,1f)` 防止跨迭代 alpha 泄漏

### 5.5 GLSL 着色器

```
assets/glsl/
├── default.vert/frag      简单纹理采样 + alpha 混合
├── bilinear.vert/frag     framebuffer blit（复用 default shader 代码）
├── layer.vert/frag        图层叠加混合
└── distance_field shader  SkinTextFont 距离场文字渲染（含硬编码 fallback）
```

所有 vertex shader 共享相同的 `v_color.a = v_color.a * (255.0/254.0)` 预乘 alpha 修正。distance_field shader 在文件加载失败时使用硬编码的 fallback 源码编译。

---

## 6. 垂直同步与帧率控制系统深度分析 ★

### 6.1 三层帧率控制架构

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 1: WindowManager.preferredRefreshRate                     │
│   AndroidLauncher.setupHighRefreshRate()                        │
│   → 遍历 Display.getSupportedModes() 找最高刷新率                 │
│   → params.preferredRefreshRate = highestRR                     │
│   → window.setAttributes(params)                                │
│   影响: WindowManager 层面的刷新率偏好                             │
├─────────────────────────────────────────────────────────────────┤
│ Layer 2: Surface.setFrameRate() (Android 11+)                   │
│   AndroidLauncher.setupSurfaceFrameRate() +                     │
│   MainController.updateFrameRateAPI()                           │
│   → 反射获取 SurfaceView → Surface                              │
│   → surface.setFrameRate(targetFPS, FRAME_RATE_COMPATIBILITY_DEFAULT) │
│   影响: SurfaceFlinger 会尽量将刷新率设置为该帧速率的倍数           │
├─────────────────────────────────────────────────────────────────┤
│ Layer 3: 应用层绝对时间对齐帧率控制                                │
│   MainController.render() 末尾                                  │
│   → 3 阶段睡眠策略: Thread.sleep → parkNanos → busy-wait        │
│   → 绝对时间基准 nextFrameTimeNanos，消除累积漂移                  │
│   影响: 精确的应用层帧间隔控制                                     │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 应用层帧率控制详细机制

```java
// MainController.render() 末尾
if (doFrameLimit) {
    final long frameIntervalNanos = 1_000_000_000L / maxFPS;
    
    // 初始化/重置: 距现在超过3帧周期则重新对齐
    if (nextFrameTimeNanos == 0 || now - nextFrameTimeNanos > frameIntervalNanos * 3) {
        nextFrameTimeNanos = now + frameIntervalNanos;
    } else {
        nextFrameTimeNanos += frameIntervalNanos;  // 绝对时间推进
    }
    
    // 阶段1: >2ms 剩余 → Thread.sleep (节省CPU，保留1ms缓冲)
    // 阶段2: 200µs~1ms 剩余 → LockSupport.parkNanos (微秒级让出CPU)
    // 阶段3: <200µs 剩余 → busy-wait (仅非Android，避免抢占GPU线程)
}
```

**设计优势**:
- 绝对时间对齐避免逐帧累积误差（传统"每帧 sleep(剩余时间)"会导致 120fps 实际跑成 123fps）
- 3 阶段策略平衡 CPU 节省与精度
- Android 平台跳过 busy-wait，避免 GL 线程忙等时 GPU 驱动线程得不到 CPU 时间
- **VSync 相位锁定**: 通过 `Choreographer` 获取 VSync 时间戳并微调 `nextFrameTimeNanos`，确保应用渲染节奏与显示器刷新相位同步，消除微卡顿。

**多级刷新率管理的一致性**:
`Config.audioFramePerSecond` (30/60)、`AndroidLauncher.maxRefreshRate` (硬件最大值)、`MainController.currentTargetFPS` (界面策略值)、`Config.maxFramePerSecond` (用户可配置值) — 这些值已通过 `updateFrameRateAPI` 和相位对齐逻辑实现统一调度。

### 6.3 与 Android Choreographer 的集成

Android 原生应用通过 `Choreographer.postFrameCallback()` 获得 VSync 对齐的回调。beatoraja-Android 采用了**混合模式**：

1. **AndroidLauncher** 启动一个持久的 `Choreographer` 回调循环，将最新的 VSync 纳秒时间戳同步给 `MainController`。
2. **MainController** 在手动帧率控制逻辑中，计算 `nextFrameTimeNanos` 与 `lastVsyncTimeNanos` 的偏差。
3. **相位修正**: 将 `nextFrameTimeNanos` 强制对齐到最近的 `lastVsyncTimeNanos + N * intervals` 位置。

**效果**: 既保留了 libGDX 灵活的渲染循环和高精度输入采样，又获得了原生级别的 VSync 稳定性。

### 6.4 帧率控制数据流

```
32-bit 设备检测 (beatoraja.32bit)
          │
    ┌─────┴─────┐
    ↓           ↓
  32bit       64bit
    │           │
 changeState  changeState
    │           │
 MusicSelect  MusicSelect
  → 30FPS     → 1000 (不限)
    │           │
 PLAY/RESULT  PLAY/RESULT
  → detectedRefreshRate (通常60)
                │
          updateFrameRateAPI()
          → Surface.setFrameRate()
                │
          config.setMaxFramePerSecond()
          → render() 末尾帧率控制
```

---

## 7. 性能优化全景分析 ★

### 7.1 已实施的性能优化

#### 7.1.1 GL 层面

| 优化 | 位置 | 效果 |
|------|------|------|
| GL_DEPTH_TEST / GL_STENCIL_TEST / GL_DITHER 禁用 | `MainController.create()` | 减少 GPU driver overhead |
| GL_BLEND / GL_TEXTURE_2D 启用 | `MainController.create()` | 启用 2D 必需功能 |
| 预乘 Alpha 混合 `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` | `MainController.create()` | 高性能混合模式 |
| Mipmap 生成提示 `GL_FASTEST` | `MainController.create()` | 加速 mipmap 生成 |
| Blend 状态缓存 | `SkinObjectRenderer.preDraw()` | 避免冗余 `glBlendFunc` |
| Shader 切换缓存 | `SkinObjectRenderer.preDraw()` | 仅在不同 shader 时切换 |
| Nearest 过滤 (default skin) | `SkinObjectRenderer` 构造器 | 降低 GPU 带宽 |
| 视口裁剪 | `SkinObjectRenderer.viewport` | 跳过视口外的 draw call |

#### 7.1.2 内存与 GC 层面

| 优化 | 位置 | 效果 |
|------|------|------|
| Matrix4 预分配 | `MainController.projMatrix` | 避免每帧 `new Matrix4()` |
| StringBuilder 预分配 | `MainController.coordTextBuilder` | 避免 String.format() |
| 时间线文本预缓存 | `LaneRenderer.cachedTimeText[]` | 避免每帧 `String.format()` |
| 音符渲染区域预计算 | `LaneRenderer` isPortrait 缓存 | 避免每帧判断 |
| JIT 预热 | `MainController.create()` 末尾 3 帧空渲染 | 提前编译 SpriteBatch 热点 |

#### 7.1.3 调度与线程层面

| 优化 | 位置 | 效果 |
|------|------|------|
| GL 线程优先级 `THREAD_PRIORITY_DISPLAY` | `MainController.render()` | 渲染不被调度器降权 |
| 独立输入轮询线程 (1000Hz, MAX_PRIORITY-1) | `MainController.create()` | 输入不跟随帧率 |
| KeepAlive 伪触摸 | `AndroidLauncher.keepAliveRunnable` | 防止系统降频 |
| 持续性能模式 | `AndroidLauncher.setupSustainedPerformance()` | `setSustainedPerformanceMode(true)` |
| 绝对时间对齐帧率控制 | `MainController.render()` | 消除帧率漂移 |
| VSync 相位锁定 | `MainController.render()` + `Choreographer` | 消除与 SurfaceFlinger 不同步导致的微卡顿 |
| 32位设备 30FPS 策略 | `MainController.changeState()` | 降低低端 GPU 负载 |

#### 7.1.4 渲染批次优化

| 优化 | 位置 | 效果 |
|------|------|------|
| SpriteBatch 缓冲区 4096 | `MainController.create()` | 减少高 BPM 谱面 flush 次数 |
| 多 begin/end 合并（部分） | `MainController.render()` | FPS+Message 合并为一个 begin/end |
| MusicSelector 跳过重复 create | `MainController.changeState()` | 切回选曲界面仅重新加载 skin |
| 输入轮询线程优雅停机 | `MainController.dispose()` | `pollingRunning=false` + `interrupt()` + `join(100ms)` |
| GL 上下文恢复 | `MainController.resume()` | 重建所有失效的 GPU 纹理 |

### 7.2 可进一步优化的方向

#### 7.2.1 图形引擎优化

| 问题 | 影响 | 建议 |
|------|------|------|
| **多 begin/end 对** | 当前每帧 2~4 对 `sprite.begin()/end()`，每对都 flush batch 到 GPU | 理想情况应合并为单次 `begin/end`。skin 渲染和触摸指针可考虑使用不同的投影矩阵或条件渲染合并 |
| **GlyphLayout 分配** | `systemfont.draw()` 内部创建 `GlyphLayout` 对象 | 预分配 `GlyphLayout` 并复用，特别是在 FPS 显示和坐标文字渲染中。也可在 debug=false 时禁用 FPS 渲染 |
| **SkinObject.prepare() 全量调用** | 每帧对所有 SkinObject 调用 `prepare()`（含关键帧插值、属性读取），即使对象从未改变 | 引入 dirty flag：仅当绑定的 Property 值变化时才重新计算。静态皮肤元素（如背景图）可标记为 static |
| **Shader 运行时加载** | Shader 首次使用时从文件加载+编译，可能造成首次进入某个界面的卡顿 | 在 `create()` 阶段预加载所有 shader |
| **LaneRenderer 矩形分配** | `new Rectangle()` 用于绘制区域计算 | 复用静态 Rectangle 对象 |
| **缺少 FBO/双缓冲** | 每帧直接渲染到屏幕 framebuffer，无法做后处理效果 | 可选：将 notefield 渲染到 FBO，然后做 bloom/blur 后处理。但需要评估额外的 VRAM 消耗 |

#### 7.2.2 垂直同步优化

| 问题 | 影响 | 建议 |
|------|------|------|
| **帧率突变** | 状态切换时 `targetFPS` 从 30 → 120 → 1000 的突变可能导致瞬时的帧节奏不均匀 | 使用帧率渐变（ramp up/down），或至少在下一次 VSync 信号时再切换 |
| **Surface frame rate API 与渲染线程不同步** | `updateFrameRateAPI()` 在 `changeState()` 中调用（渲染线程），但 `setupSurfaceFrameRate()` 在 `postDelayed` 中调用（主线程），两者可能存在竞态 | 统一在单一位置管理 frame rate，使用 `AtomicInteger` 存储 targetFPS |

#### 7.2.3 其他性能方向

| 方向 | 说明 |
|------|------|
| **纹理图集 (Texture Atlas)** | 皮肤加载的多个小图片可合并为图集，减少纹理绑定切换 |
| **TypedArray 替换 ArrayMap** | `Skin.java` 中大量使用 `IntIntMap`（基于 libGDX 的 `IntIntMap` 是开放寻址 hash map，已经比较高效） |
| **增量式 Skin 更新** | `Skin.updateCustomObjects()` 每帧遍历所有 customEvents 和 customTimers，可以考虑标记活跃的才更新 |
| **音频频谱采样的帧间差异** | `SideSpectrumRenderer` 的 draw 使用 `ShapeRenderer`——这会结束当前的 `SpriteBatch` 渲染并开始新的。应尽量放在同一个渲染上下文中或考虑用纹理图集模拟 |
| **BGA 视频解码** | `GdxVideoProcessor` 使用 `MediaCodec` 硬件解码。可以考虑降低非活跃 BGA 的帧率（如 BGA 层被遮挡时降到 10fps） |

---

## 8. 皮肤系统

### 8.1 三种皮肤格式 → 统一内部表示

```
                    SkinLoader.load()
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
    .json 文件      .luaskin 文件    LR2 CSV 文件
   JSONSkinLoader   LuaSkinLoader   LR2SkinCSVLoader
   (Jackson 反序列化) (Lua → Java)  (Command Word 逐行解析)
         │               │               │
         └───────────────┴───────────────┘
                         ▼
                    Skin 容器 → SkinObject[]
                         │
                    drawAllObjects()
                         │
              prepare(time) → 关键帧插值
              draw(renderer) → SkinObjectRenderer
```

### 8.2 SkinObject 类型体系

```
SkinObject
├── SkinImage                  图片/按钮/动画
├── SkinNumber                 整数显示（分数/连击）
├── SkinFloat                  浮点数显示
├── SkinTextFont               TTF 字体
├── SkinTextBitmap             位图字体 (.fnt)
├── SkinTextImage              LR2 图片字体
├── SkinSlider                 滑块控件
├── SkinGraph                  条形图
├── SkinBPMGraph               BPM 曲线图
├── SkinHitErrorVisualizer     打击误差
├── SkinNoteDistributionGraph  音符密度分布
├── SkinTimingVisualizer       时序可视化
└── SkinTimingDistributionGraph 时序分布统计
```

### 8.3 属性绑定与关键帧动画

- **属性 ID** → `*PropertyFactory` → `*Property` 实例（Boolean/Integer/Float/String/Timer）
- **关键帧动画**: 每个 `SkinObject` 持有 `SkinObjectDestination[]`（时间+位置+颜色+角度+缓动），运行时插值
- **条件绘制**: `BooleanProperty[] drawCondition` 控制对象可见性（如仅在特定选项开启时显示）
- **自定义事件**: `CustomEvent[]` / `CustomTimer[]` 支持皮肤层的时间驱动逻辑

---

## 9. 输入系统

### 9.1 三层架构

```
BMSPlayerInputProcessor（统一管理）
├── KeyBoardInputProcesseor    键盘 + 触摸手势 + 模拟按键
│   - poll(microtime)          轮询 Gdx.input.isKeyPressed()
│   - 手势映射（Select/Result/Decide 各有不同模式）
│   - 模拟按键系统（150ms 有效期 / 长按锁定）
│   - Android Back 键 → ESCAPE 映射
│
├── BMControllerInputProcessor  游戏手柄
│   - libGDX Controllers API
│   - 物理按键 + 模拟轴（转盘 V1/V2 算法）
│
└── MidiInputProcessor          MIDI 设备
    - javax.sound.midi API
    - NOTE_ON/OFF → 按键, PITCH_BEND → 转盘
```

### 9.2 高精度输入轮询

独立于渲染的输入轮询线程（`Thread.MAX_PRIORITY - 1`），使用绝对时间对齐：

```java
Thread polling = new Thread(() -> {
    long nextPollTime = System.nanoTime();
    for (;;) {
        input.poll();
        nextPollTime += 1_000_000_000L / config.getInputPollingRate(); // 默认 1000Hz
        LockSupport.parkNanos(nextPollTime - System.nanoTime());
    }
});
```

可配置频率: 500/1000/2000/4000 Hz（`Config.inputPollingRate`）。

---

## 10. 音频系统

### 10.1 分层架构

```
AudioDriver (接口)
    ├── AbstractAudioDriver (抽象骨架)
    │   └── GdxSoundDriver (OpenAL / Oboe 实现)
    │       - 256 个声音实例池
    │       - 按键音 / BGM / BGA 播放
    │
    └── GdxAudioDeviceDriver (未完成)

PCM 数据抽象:
    PCM<T> ← BytePCM / ShortPCM / ShortDirectPCM / FloatPCM
```

### 10.2 Android Oboe 集成

```
AndroidLauncher.createAudio()
    ├── new OboeAudio(assets, sampleRate)     Oboe 原生音频引擎
    │   └── AudioSpectrumAdapter              频谱数据适配器
    │       └── AudioSpectrumManager.setGlobalProvider()
    └── 失败时回退: super.createAudio()       Android AudioTrack
```

- `ShortDirectPCM` 使用 `DirectBuffer` 避免 JNI 数据复制
- `AudioSpectrumManager` 全局单例，`SideSpectrumRenderer` 读取 FFT 数据绘制频谱柱状图

---

## 11. 歌曲数据库与选曲系统

### 11.1 数据流

```
启动 → SongUpdateThread
    ├── 扫描 bmsroot[] 目录
    ├── BMSDecoder.decode() → SongData + SongInformation
    ├── 写入 songdata.db (AndroidSQLiteSongDatabaseAccessor)
    └── 写入 information 表 (SongInformationAccessor)
```

### 11.2 MusicSelector 核心组件

```
MusicSelector (MainState)
├── BarManager                层级导航管理
│   ├── BarSorter             排序器
│   ├── BarContentsLoaderThread 后台加载（分数/图片）
│   └── bar/                  Bar 类型体系 (13 files)
│       ├── SongBar, FolderBar, TableBar, HashBar
│       ├── GradeBar, CommandBar, SearchWordBar
│       └── RandomCourseBar, SameFolderBar
│
├── BarRenderer               Bar 渲染
├── MusicSelectInputProcessor  4 层面板选项系统
│   ├── Panel 0: 歌曲浏览
│   ├── Panel 1 (Start): 游戏选项
│   ├── Panel 2 (Select): 辅助选项
│   └── Panel 3 (Start+Select): 详细选项
├── PreviewMusicProcessor      乐曲预览
└── ScoreDataCache             分数缓存
```

---

## 12. BMS 谱面模型

```
BMS/BMSON 文件 → BMSDecoder/BMSONDecoder → BMSModel
    ├── TimeLine[]             按时间排序的时间线数组
    │   ├── Note[]             各轨道音符 (NormalNote/LongNote/MineNote)
    │   ├── BPMEvent/StopEvent  BPM 变化 / STOP 事件
    │   └── Section            小节标识
    ├── wavmap[]               音源 ID → 文件路径
    ├── bgamap[]               BGA ID → 文件路径
    ├── Mode                   8种游戏模式 (5K/7K/9K/10K/14K/24K/24K_DOUBLE)
    └── md5/sha256             文件完整性校验
```

---

## 13. Android 平台适配

### 13.1 存储管理

- 歌曲: `Download/beatoraja/songs/` (支持 ZIP 自动解压导入)
- 皮肤: `Download/beatoraja/skins/` (支持 ZIP 自动解压导入)
- 配置/数据库: `Android/data/com.starxh.beatoraja/files/`
- 首次启动自动解压 assets 内置的 `inochi_ogg` 示例曲包

### 13.2 性能适配

- 32位设备: 30FPS + 低精度计时器
- 64位设备: 不限帧 + 高精度计时器
- 持续性能模式 + KeepAlive 伪触摸防止系统降频
- 高刷新率支持 (WindowManager + Surface API)
- GL 线程优先级 `THREAD_PRIORITY_DISPLAY`
- 竖屏支持 (`isPortrait` 模式，音符横向落下)
- 触摸按键映射 (`PlayTouchKeyMapper`)
- Oboe 低延迟音频 + DirectBuffer PCM
- GL 上下文恢复 (resume 时重建所有失效纹理和字体)

---

## 14. 关键配置字段速查

### Config（系统级）

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `playername` | `"player1"` | 当前活跃玩家 |
| `vsync` | `false` | 桌面端垂直同步（Android 不使用） |
| `maxFramePerSecond` | `300` | 最大帧率（垂直同步 OFF 时有效） |
| `androidUnlimitedFPS` | `false` | Android 无限制帧率模式 |
| `androidStableFPS` | `true` | Android 稳定帧率模式 |
| `inputPollingRate` | `1000` | 输入轮询频率 (Hz) |
| `frameskip` | `1` | 跳帧数 |
| `cacheSkinImage` | `false` | 皮肤图片缓存 |
| `skinPixmapGen` | `4` | 皮肤 Pixmap 资源池大小 |
| `bga` / `bgaExpand` | `0` / `1` | BGA 显示模式 / 扩展模式 |

### 新增字段（v3）

| 字段 | 说明 |
|------|------|
| `androidUnlimitedFPS` | Android 平台是否允许无限制帧率（默认关闭，强制绝对时间对齐） |
| `inputPollingRate` | 独立输入轮询线程频率 (500/1000/2000/4000 Hz) |
| `keySoundTailMs` | 曲结束时按键音播放宽限时间 (默认 5000ms) |
| `showAudioSpectrum` / `spectrumInGameArea` | 频谱可视化控制 |
| `showFloatingMenuInPlay` | Play 界面浮动菜单显示控制 |
| `showTouchKey` | Play 界面触摸按键显示 |

---

## 15. 关键文件索引

| 文件 | 作用 |
|------|------|
| [AndroidLauncher.java](file:///c:/Users/11879/Documents/beatoraja-Android/android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java) | Android 游戏入口 Activity ★ |
| [SettingsActivity.java](file:///c:/Users/11879/Documents/beatoraja-Android/android/src/main/java/com/starxh/beatoraja/android/compose/SettingsActivity.java) | 设置/启动界面 |
| [BeatorajaGame.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/com/starxh/beatoraja/BeatorajaGame.java) | libGDX ApplicationAdapter |
| [MainController.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/MainController.java) | 中央控制器 + 渲染管线 + 帧率控制 ★ |
| [MainState.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/MainState.java) | 状态机抽象基类 |
| [Config.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/Config.java) | 系统级配置 |
| [PlayerConfig.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/PlayerConfig.java) | 玩家级配置 |
| [Skin.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/skin/Skin.java) | 皮肤容器 + SkinObjectRenderer ★ |
| [SkinObject.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/skin/SkinObject.java) | 皮肤对象抽象基类 |
| [SkinLoader.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/skin/SkinLoader.java) | 皮肤加载入口 |
| [ShaderManager.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/ShaderManager.java) | GLSL 着色器管理 |
| [LaneRenderer.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/play/LaneRenderer.java) | 音符轨道渲染 ★ |
| [BMSPlayer.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/play/BMSPlayer.java) | 游玩主状态 |
| [JudgeManager.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/play/JudgeManager.java) | 判定核心 |
| [BMSPlayerInputProcessor.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/input/BMSPlayerInputProcessor.java) | 统一输入管理 |
| [MusicSelector.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/select/MusicSelector.java) | 选曲界面 |
| [SongDatabaseAccessor.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/song/SongDatabaseAccessor.java) | 歌曲数据库接口 |
| [AudioDriver.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/audio/AudioDriver.java) | 音频驱动接口 |
| [FloatingMenu.java](file:///c:/Users/11879/Documents/beatoraja-Android/core/src/main/java/bms/player/beatoraja/FloatingMenu.java) | Android 浮动快捷键菜单 |
