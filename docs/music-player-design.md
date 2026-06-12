# Music Player 功能设计

## 概述

在选曲界面(MUSICSELECT)加入一个**音乐播放器模式**,与现有 BMS 游玩模式(PLAY)并列。本质是把 BMS 图表跑成 autoplay 形式来听音乐 —— 不渲染 BGA、不显示判定画面,只播放音频并展示简化的播放进度 UI。

**核心设计点**:Music Player 不是"音频预览播放器",而是一个**轻量化的 BMS autoplay runner**。它完整复用了 BMS 解析、音轨调度、判定触发、计时时钟等所有 BMSPlayer 的核心机制,只是在渲染和功能上做了减法。

---

## UI 入口:浮动按钮

参照 `FloatingMenu` 的实现模式(`core/src/main/java/bms/player/beatoraja/FloatingMenu.java`),在 MUSICSELECT 状态下绘制一个常驻的音符图标按钮。

- **位置**:屏幕右下角或左下角(与 FloatingMenu 错开),通过 `SpriteBatch` 直接绘制,不走 `Skin` 系统
- **输入**:经由 `MainController` 的 `InputMultiplexer` 抢占触摸事件(参考 `MainController.java:428, 434, 452`)
- **行为**:点击 → 暂停 MUSICSELECT 渲染 → 切换到 `MainStateType.MUSICPLAYER`
- **资源释放**:Music Player 关闭后释放音符按钮的 `Texture`

---

## 核心:Music Player 状态

新增 `MainStateType.MUSICPLAYER`,与 `PLAY` / `MUSICSELECT` / `DECIDE` 并列。

### 状态机简化

`MUSICPLAYER` 状态下维护一个内部状态机:
- `LOADING`:加载 BMSModel、初始化音轨(同 BMSPlayer 的 PRELOAD 阶段)
- `PLAYING`:正常 BMS autoplay 进行中
- `PAUSED`:用户暂停
- `FINISHED`:当前曲目播放结束,自动跳到下一首
- `EXITING`:返回 MUSICSELECT

### 渲染内容(减法清单)

| 元素 | PLAY 模式 | MUSICPLAYER 模式 |
|------|-----------|------------------|
| BGA / 影片 | ✓ | ✗ |
| 判定数字 / 判定线动画 | ✓ | ✗ (只保留判定线) |
| 键型提示 / LN 尾部 | ✓ | ✗ |
| BPM 变化指示 | ✓ | ✗ |
| 进度条 | ✓ | ✓ (放大,带时间码) |
| 歌曲信息(标题、艺术家、等级) | 选曲时一次性 | ✓ 常驻显示 |
| 专辑封面 | 可选 | ✓ (从 BMS 元数据读) |
| 频谱可视化 | 来自主控制 | ✓ (新增) |
| 上一首 / 暂停 / 下一首 | N/A | ✓ (大按钮或键盘 ←/→/SPACE) |

实现方式:不直接复刻 BMSPlayer 的 `LaneRenderer` / `BGARenderer` 等,而是只渲染 **note lane 简化版**(黑白键型从上往下落即可),叠加常驻播放控制条。

### 音频路径

完全沿用 BMSPlayer 的音频机制,**不要**走 `AudioDriver.play(path)` 这种"直接播文件"的方式:

- `KeySoundProcessor.startBGPlay(model, 0)` 启动 BG 音轨(参照 `KeySoundProcessor.java:33`)
- `KeySoundProcessor.AutoplayThread`(lines 49-99)负责按 BMS 时间轴触发所有 note 的音频
- 判定音效和键音由 `JudgeManager` / `KeyInputProccessor` 自动触发(autoplay 模式下没有手输入,全部由 autoplay feed keys)
- 全局音量通过 `main.getAudioProcessor().setVolume(...)` 调节

---

## 选曲联动

Music Player 不维护自己的播放列表,而是**直接消费当前 MUSICSELECT 状态下的选曲条**:

- 进入 Music Player 时:`main.getMusicSelector().getSelectedBar()` 取得当前选中的歌,以及当前 folder 的所有可见 song
- 上一首 / 下一首:在当前 folder 的 song 列表中前进 / 后退一个索引(与 MUSICSELECT 自身的 `cursor` 同步),重新加载 BMSModel
- 跨 folder:Music Player 内部不直接处理 folder 切换逻辑,只跟随选曲条当前显示的歌;用户若想跨 folder,先回到 MUSICSELECT 移动光标再进入

> 这点很重要:**选曲与播放完全解耦**,Music Player 只读 `MusicSelector` 的当前歌 + 列表。

---

## 状态切换 / Lifecycle

```
┌────────────────┐   click note icon   ┌────────────────────┐
│  MUSICSELECT   │ ──────────────────► │  MUSICPLAYER       │
│  (暂停渲染)    │ ◄────────────────── │  (autoplay 跑起来) │
└────────────────┘   click exit / ESC  └────────────────────┘
```

### 进入 MUSICPLAYER

1. 读取 `MusicSelector` 的当前选中 song
2. `resource.setBMSFile(file, BMSPlayerMode.MUSICPLAYER)`(参考 `MusicSelector.java:436-438`)
3. `main.changeState(MainStateType.MUSICPLAYER)`
4. MUSICSELECT 的 `render()` 暂停(在状态机外部判断 `state == MUSICPLAYER` 时跳过 MUSICSELECT 的绘制)

### 在 MUSICPLAYER 内

- 时间轴与 BMSPlayer 一致:从 `model.getFirstNoteTime()` 开始,按 `playTime` 推进
- 帧循环:`update() → render() → 输入处理`,每帧调用 `keysound` 推进音轨调度
- 上一首/下一首:更新当前 index → `setBMSFile` 重载 → 重新 `create()`

### 退出回 MUSICSELECT

- 触发条件:点击返回按钮 / 按 ESC / 列表播放完一轮
- 调用 `main.changeState(MainStateType.MUSICSELECT)`
- MUSICSELECT 重新 `render()` 时,`cursor` 保持 Music Player 内部的最后位置(由 Music Player 写回 `MusicSelector.setSelectedBar(...)`)

---

## 关键代码改动点

| 文件 | 改动 |
|------|------|
| `core/.../BMSPlayerMode.java` | `Mode` 枚举新增 `MUSICPLAYER`(或新增 boolean `isMusicPlayerMode` 到 `BMSPlayer`) |
| `core/.../BMSPlayer.java` | 在 `bga.prepare(this)` 之前(参考 lines 656, 736)判断 Music Player 模式 → 调用 `bga.stop()` 禁用 BGA;隐藏 LaneRenderer;简化判定线 |
| `core/.../play/KeySoundProcessor.java` | 不变(autoplay + BG 播放已经齐全) |
| `core/.../MainStateType.java` | 新增 `MUSICPLAYER` |
| `core/.../MainController.java` | `changeState(MUSICPLAYER)` 的处理分支(参照 lines 332-344);`render()` 中判断当前 state 决定是否绘制 MUSICSELECT |
| `core/.../select/MusicSelector.java` | 浮动音符按钮的渲染和点击;暴露 `getSelectedBar()` / `getCurrentSongList()` 给 Music Player 读 |
| `core/.../select/MusicPlayer.java`(新建) | Music Player 主类:简化版的 BMSPlayer,持有 `PlayerResource`,实现 `create() / render() / input()` |

---

## 状态切换时的资源管理

- 音频:`KeySoundProcessor` 在 `dispose()` 时停所有音轨(`KeySoundProcessor.java` 对应方法)
- 模型:`BMSModel` 由 `PlayerResource` 持有,`setBMSFile` 时释放旧 model
- 渲染资源:Music Player 内部的专辑封面、频谱纹理在 `dispose()` 释放
- 不要让 MUSICSELECT 的 `bar` / `cursor` 在 Music Player 期间被清空(只读不写)

---

## Open Questions

1. **Guide SE(判定音效)**:用户设置里 `config.isGuideSE()` 默认就是 `false`,且 `BMSPlayer.java:579-582` 在 false 时显式置空 `setAdditionalKeySound(...null)`,所以默认情况下无 Guide SE 干扰。Music Player 模式不需要额外处理 —— 走 BMSPlayer 同一条初始化路径即可。
2. **BG 声道外的 BPM 切换**:跨 BPM 切换时,KeySoundProcessor 的内部调度已经处理,但渲染上要不要显示 BPM 数字?倾向于不显示,保持 Music Player 极简。
3. **跨 folder 自动播放**:folder 末尾自动跳下一个 folder,还是只在该 folder 内循环?倾向于跟随 MUSICSELECT 的播放设置(`Config.getRandomSelection()` 行为)。
4. **Music Player 内的随机选曲**:要不要支持随机播放当前 folder 的所有歌?可作为后续增强。
5. **横屏 / 竖屏**:Music Player UI 是否需要做竖屏适配?初步可不支持,只跑横屏。

---

## 后续工作

1. `BMSPlayerMode` 扩展
2. `MusicPlayer` 主类骨架(实现 `MainState` 接口)
3. 简化版渲染器(只画 note lane + 进度条 + 专辑封面)
4. 浮动音符按钮 UI
5. 选曲条读取 / 写回
6. 暂停 / 上一首 / 下一首 输入映射(物理键 + 触屏)
7. 退出 / ESC 行为
