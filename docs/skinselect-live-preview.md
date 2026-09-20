# 皮肤选择界面的实时预览（Skin Preview）

> 目标：SKIN SELECT 界面右上角那个 640×360 的灰块（皮肤里的 `preview-bg`），改成**当前选中皮肤的实时预览**。
> 状态：已移植（2026-09-20），versionCode 15→16；预览内的**自绘演示音符**于同日补上（第六节）。

## 一、这不是从零设计，是"补移植"

上游 PC 版**早就有这个功能**：`beatoraja-master/src/bms/player/beatoraja/config/SkinPreview.java`（129 行，
自带 FrameBuffer 离屏渲染）。Android 版在移植时整块没引入 —— `core/.../config/` 下没有这个文件，
`Skin` 也没有配套的绘制入口。所以本次工作 = 把上游那 129 行搬过来 + 适配 Android 的渲染接口。

那个灰块在皮肤里的定义：`skin.image.preview-bg`（`skinselectmain.lua:43`）+
`skin.destination` 的 `{x=470, y=350, w=640, h=360}`。上游的 `skin-preview` 目的地位置**与它完全重合**，
搬过来天然对齐。

## 二、机制

```
SkinConfiguration.render()          ← 每帧：处理"参数调整后延迟重建"
        │
        │ selectSkin() / 参数变更
        ▼
SkinConfiguration.loadSelectedSkinPreview()
   ├─ new SkinConfig(config.getPath())      ← 带上用户改过的 properties
   ├─ SkinLoader.load(this, type, cfg)      ← 加载一份**独立的** Skin 实例
   └─ preview.prepare(this)                 ← 剔除不满足条件的对象
        │
        ▼
SkinPreview（SkinObject，由 skin.skinpreview 声明）
   draw(renderer):
     1. 外层 batch flush + end
     2. FrameBuffer.begin() → 按预览皮肤自身分辨率清屏
     3. previewBatch 用 setToOrtho2D(0,0,w,h) 重设投影
     4. previewSkin.drawAllObjectsSafely(previewBatch, configuration)
     5. FrameBuffer.end() → batch 矩阵还原
     6. 外层 batch begin，再把 frameRegion 贴到自己的 dst
```

**"实时"体现在两处**：① 切皮肤立刻重新渲染；② 右侧自定义参数（Lane Size / Scratch Side / Layout…）
一改，重载的皮肤实例就带上新 properties，轨道布局随之改变。

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `core/.../config/SkinPreview.java` | **新增**。上游实现的 Android 适配版 |
| `core/.../skin/Skin.java` | 抽出 `ensureRenderer()`；**新增** `drawAllObjectsSafely()`（含可指定动画时间的重载，第十节）、`insertSkinObjectAfter()`、`SCENE_UNSPECIFIED`；`SkinObjectRenderer` 加 `getSpriteBatch()` 与静态 `setCurrentViewport()` |
| `core/.../play/PreviewNoteLayer.java` | **新增**。预览用的自绘演示音符层（第六节），并负责点亮 keybeam / bomb 的计时器（第六节第 8 条） |
| `core/.../PlayStateValues.java` | **新增**。`getNowJudge` / `getNowCombo` / `getGauge` 三个值的窄契约（第七节） |
| `core/.../play/PreviewPlayValues.java` | **新增**。上述契约的合成实现，并点亮判定 / 连击的显示计时器（第七节） |
| `core/.../config/SkinConfiguration.java` | `getSelectedSkin()`；`loadSelectedSkinPreview()` / `reloadSelectedSkinPreview()` / `setSelectedSkin()`；`selectSkin` 调用点；三个 `setCustom*` 挂重建请求；`dispose()` 释放；**2026-09-20 放开 RESULT / COURSE_RESULT**（第十节第 4 条） |
| `core/.../skin/json/JsonSkin.java` | 加 `skinpreview` 字段 + `SkinPreview` 内部类（只声明 `id`） |
| `core/.../skin/json/JsonSkinConfigurationSkinObjectLoader.java` | 覆写 `loadSkinObject`：基类未命中且 `dst.id == sk.skinpreview.id` 时返回 `new SkinPreview()` |
| `android/assets/skin/default/skinselect/skinselectmain.lua` | 加 `skin.skinpreview = {id = "skin-preview"}` 与一条 destination |

## 四、五个必须遵守的实现约束（都是踩过的）

### 1. 每个对象单独容错，不能整张皮肤一起崩

`drawAllObjects` 的 debug 分支虽然有 try-catch，但**非 debug 分支的 catch 只是打日志继续**，
`prepare` 一次失败并不阻止后续帧重试。预览环境里这是灾难：预览是没有谱面 / 分数 / BPM 的环境，
依赖它们的对象会**稳定地**每帧抛异常。

`drawAllObjectsSafely` 的做法是 **prepare / draw 各自 try-catch 并把该对象的 `draw` 置 false** ——
失败一次就永久跳过该对象。同时 `SkinPreview` 自己还有一层：整块渲染抛异常就 `disabled = true`
（换皮肤时重置），不再每帧重试。

### 2. viewport 是 ThreadLocal，必须保存/还原

`SkinObject.checkViewport()` 读的是 `Skin.SkinObjectRenderer.getCurrentViewport()`（**ThreadLocal**），
而 `setViewport()` 会同时写实例字段和 ThreadLocal。

预览皮肤的尺寸常常和外层皮肤不同（外层往往 1280×720，预览可能是 1920×1080）。
如果不还原，预览画完之后外层皮肤的裁剪矩形就残留在预览尺寸上，表现为**外层元素被错误裁掉**。
所以 `drawAllObjectsSafely` 进入前 `new Rectangle(getCurrentViewport())` 留存、`finally` 里写回。

### 3. 参数变更必须去抖，切皮肤不必

`SkinLoader.load` 每次都全量解析皮肤 + 重建全部贴图引用 + `resource.disposeOld()`。
在目标低端 32 位 ARM 上单次可能几百毫秒。

- **切皮肤**（`selectSkin`）：用户主动操作，立即加载。
- **改参数**（`setCustomOption` / `setFilePath` / `setCustomOffset`）：用户会连按、会长按，
  每次点击都重建 = 界面卡死。所以只记 `previewReloadRequestTime`，由 `render()` 在
  **停手 120ms 后**统一重建一次。

另外 `selectSkin` 内部的 `updateCustom*` 会连续触发 `setCustom*`，若不抑制就会一次选择
触发 3~4 次全量加载。用 `previewReloadSuppressed` 把这一段包起来，结束时统一加载一次。

### 4. GL 视口必须自己存/还原 —— 否则整个界面被拉伸（**上线后立刻踩到**）

这是移植完成后实机第一眼就暴露的问题：**只要预览一渲染（即"有选中皮肤"），皮肤选择界面
立刻被粗暴拉伸到铺满整个 surface**，而其他界面正常。

根因在 libGDX 的 `GLFrameBuffer`：

```java
// GLFrameBuffer.java:390
public void end () {
    end(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
}
// GLFrameBuffer.java:400
public void end (int x, int y, int width, int height) {
    unbind();
    Gdx.gl20.glViewport(x, y, width, height);   // ← 写死成"整个后缓冲"
}
```

`begin()` 会把视口设成 FBO 尺寸、`end()` 会把视口设成**整个后缓冲**，两者都**不还原**调用前的
值。而预览是在皮肤绘制**中途**插进去的（`Skin.drawAllObjects` 的对象循环里），
`MainController.render()` 每帧算好的等比视口（pillarbox / letterbox，或 `stretchFullscreen`
时的全屏）在这一步被冲掉 → 之后画的内容按全屏视口线性铺开 → 表现就是分辨率拉伸。

项目里已有同类先例，照抄即可：

- `BGAProcessor.renderBGAToFramebuffer()`（`:576-588`、`:650`）保存 `GL_VIEWPORT` 后还原，
  注释写着 "fixes out of bounds issue"；
- `MainController` 画触摸指针前也要重设视口，注释写着 stage.draw() 的 Viewport.apply() 会改写视口；
- `KeyConfiguration.render()` 里那句重复的 `glClearColor(0,0,0,1)` —— 同样是被状态泄漏咬过。

所以 `SkinPreview.renderPreview()` 在 `frameBuffer.begin()` 前用
`glGetIntegerv(GL20.GL_VIEWPORT, buf)` / `glGetFloatv(GL20.GL_COLOR_CLEAR_VALUE, buf)` 留存，
`frameBuffer.end()` 之后立刻还原。**clearColor 也要还**：预览把它改成 `(0,0,0,0)`，
不还就等于给下一帧的整屏 clear（黑边颜色）改了值。

顺带一个同源问题：预览用的是**另一个 SpriteBatch**，它直接改写 GL 的 blend 状态，
而外层 `SkinObjectRenderer` 只在"自认为 blend 变了"时才调 `setBlendFunction`
（`Skin.java:718` 的 `activeBlend` 缓存）。预览结束后外层缓存的 `activeBlend` 已失真，
所以 `SkinPreview.draw()` 的 finally 里补一次 `renderer.reset()`，让后续对象按需重设。

### 5. 预览皮肤是独立实例，必须自己负责释放

它不在 `SkinConfiguration` 自己的皮肤对象树里，`Skin.dispose()` 管不到它。
`SkinConfiguration.dispose()` 里显式 `setSelectedSkin(null)`，否则每进一次皮肤选择界面
就泄漏一张皮肤的整套纹理引用。

## 五、已知限制（上游也一样）

1. **预览本来不会有音符下落、也没有判定 / 量表 / 按键光柱 / 爆炸特效 —— 已分别补上
   （2026-09-20，见第六、七节）。**

   原限制：渲染时 state 是 `SkinConfiguration`，没有谱面/分数/BPM，依赖它们的对象会被
   `drawAllObjectsSafely` 静默跳过，能看到只有背景、轨道、判定线、装饰元素。

   **为什么"放一张谱面"不能解决（2026-09-20 查证）**：

   - `SkinNote.prepare()` 第一行就是 `final BMSPlayer player = (BMSPlayer) state;`
     （`SkinNote.java:55`）—— 预览传进去的是 `SkinConfiguration`，这里直接
     `ClassCastException`，被 `drawAllObjectsSafely` 捕获后把该对象 `draw=false`，
     **音符永远不会被画**。
   - 绕过这个 cast 也没用：`SkinNote.draw()` 把绘制整个委托给
     `LaneRenderer.drawLane()`，而 `LaneRenderer` 是**绑在活着的 play 会话上的**：
     构造需要 `BMSPlayer`（`main.main.getSystemFont18()`、`main.getSkin()`、
     `main.resource.getPlayerConfig()`），运行时要 `main.timer`（判定时刻基线）、
     `main.getState()`、`main.getJudgeManager()`（判定时区 / 判定表 / 长条处理）、
     `main.getNowQuarterNoteTime()`（音符扩张动画）、`main.main.getOffset(...)`、
     `main.getImage(IMAGE_WHITE)`、`main.resource.getBGAManager()`（触摸皮肤要取 BGA 帧）。

   所以真正缺的不是"音符数据"，而是**一个不带音频 / 输入 / 判定的演示用 play 宿主**。
   两条可行路线，选了成本低、不碰主路径的那条：

   - ✅ **已实现 —— 自绘演示音符**：见第六节 `PreviewNoteLayer`。
   - ✅ **已实现 —— 合成游玩态数值（判定 / 连击 / 量表）**：走窄接口 `PlayStateValues`，
     预览侧给一份合成实现，用皮肤自己的美术渲染。见第七节 `PreviewPlayValues`。
   - ❌ **未采纳 —— 在预览里造一个真 `BMSPlayer`**（依赖面已量化，见下）。
     真实度最高（连音符都变成真的，还能顺手删掉自绘层），但**谱面不是障碍**，
     障碍是全局副作用与依赖面宽度。

   **真渲染路线的量化结论（2026-09-20 查证）**：

   - **谱面可以不要。** `BMSModel` 能纯内存构造（`new BMSModel()` + `setMode` / `setBpm` /
     `setAllTimeLine`），不需要往 assets 里塞 `.bms`。所以"需要谱面"是个误判。
   - **真正的障碍是 `BMSPlayer.create()` 改全局单例**，第二个实例和真实游玩不能共存：
     `input.setEnable(false)`（关掉全局输入处理器）、`FileCache.clear()`、
     `loadSkin(getSkinType())`（覆盖主控制器的 play 皮肤）、
     `main.getAudioProcessor().setAdditionalKeySound(...)`、写歌曲库
     （`main.getSongDatabase().updateSongTail`）、`setContinuousRendering(true)`。
     构造函数本身也重：`songdata.getTail() <= 0` 时会遍历所有 wav 算音频时长，
     `model.getPath()` 为 null 还会 NPE。
   - **需要"以为自己在游玩"的类比想象中多。** 除了 `SkinNote` / `SkinJudge` / `SkinGauge` /
     `SkinBGA`，`skin/property/{Boolean,Float,Integer}PropertyFactory`（**皮肤属性系统**）与
     `skin/lua/MainStateAccessor` 里到处是 `((BMSPlayer) state).getGauge()` /
     `getScoreDataProperty()` —— 意味着预览皮肤里大量 `if=` / `value=` 表达式也走这条路。
   - **但单个类的依赖面很窄，抽接口可行：**
     `LaneRenderer` 对 `BMSPlayer` 只用了 **8 个成员**（`getSkin` / `getNowQuarterNoteTime` /
     `main` / `timer` / `getState` / `getJudgeManager` / `resource` / `getPracticeConfiguration`），
     其中 `main` / `timer` / `resource` / `getSkin` / `getImage` 在 `MainState` 上本来就有
     （预览 state 也继承到了）。`SkinJudge` 只要 **3 个值**
     （`getNowJudge` / `getNowCombo` / `getGauge`）。`SkinGauge` **已有非 BMSPlayer 的先例**
     （`state.resource.getGrooveGauge()`，给 `AbstractResult` 用）。`GrooveGauge` 还能完全
     脱离 resource 构造：`GrooveGauge.create(model, type, 0, null)`（内部走
     `BMSPlayerRule.getBMSPlayerRule(mode).gauge`）。
   - 所以折中路线（**抽一个"判定/连击/量表"的窄接口，预览给一份合成实现**）能以很低的改动
     拿到血条 + 判定 + combo 的原生渲染，风险远低于造一个真 `BMSPlayer`；
     代价是分数 / BPM / 属性表达式那一层仍然不显示。**这条路线已实现，见第七节。**

2. **触摸屏皮肤可能偏空。** GenericTheme for Touchscreen 的 `play.lua` 依赖 `skin_config`、
   触摸区、频谱等，跳过的对象可能比 PC 皮肤多。最坏情况是整块偏黑，需要实测。
3. **每帧重绘一次预览。** 预览区域 640×360、FBO 按皮肤分辨率（1280×720 ≈ 3.6MB），
   每帧把整张 play 皮肤画进 FBO。低端机上这是一笔固定开销；若实测掉帧，可考虑
   ① 降低重绘频率（例如每 2~3 帧一次）；② 在 `SkinPreview` 里限制 FBO 分辨率上限。

## 六、自绘演示音符：`PreviewNoteLayer`

> 目标：预览里**看得见音符在落**，并反映这张皮肤的轨道宽度、音符贴图与厚度、判定线位置、
> 下落速度；**不碰 `LaneRenderer` / `BMSPlayer`，不需要谱面**。
> 文件：`core/.../play/PreviewNoteLayer.java`（新增，`extends SkinObject`）。

### 1. 接入方式

`SkinConfiguration.loadSelectedSkinPreview()` 里，**在 `preview.prepare(this)` 之前**调用
`attachPreviewNoteLayer(preview)`：遍历预览皮肤的对象表找到第一个 `SkinNote`，用
`Skin.insertSkinObjectAfter(skinNote, new PreviewNoteLayer(skinNote, preview, mode))`
插到它**后面**（同一绘制层，层级与真实音符一致）。

放在 `prepare()` **之前**是必须的：`preview.prepare()` 会走 `validate()`，没有合法 destination
的对象会被直接删掉；放在之前才能像普通对象一样走 `load()` + 校验。

### 2. 为什么画得出来（以及为什么类必须放在 `play` 包）

`SkinNote.getLanes()` 给出 `SkinLane[]`：`region` 是 **public** 的 `Rectangle`（轨道矩形），
而 `note`（`SkinSource`）与 `scale`（音符厚度）是**包内可见** —— 所以自绘类必须落在
`bms.player.beatoraja.play` 包才能读到。`SkinSource.getImage(time, state)` 是 public，
直接当音符贴图用（`SkinNote.prepare()` 那条路走不通，贴图只能自己取）。

> ⚠️ **必须自己给每条轨道调 `lane.prepareRegion(time, state)`**（2026-09-20 实机踩到）。
>
> `SkinObject.region` 这 4 个值**只在 `prepareRegion()` 里被赋值**（`SkinObject.java:338-387`）；
> 各个 `setDestination(...)` 重载只写 `dst[]` 和 `fixr`，**从不写 `region`**。
> 而 `SkinNote.prepare()` 在第一行 `(BMSPlayer) state` 就抛异常 → 它后面那句
> `for (SkinLane lane : lanes) lane.prepare(...)`（`SkinNote.java:66-68`）**永不执行**
> → 每条 `SkinLane.region` 恒为 `(0,0,0,0)`。
>
> 于是 `hu - hl == 0`，`travel <= 1` 直接 return —— **一个音符都不画，且毫无报错**
> （不像 `SkinNote` 那样会被 `drawAllObjectsSafely` 记为 `draw=false`，本层是"正常但画不出"）。
> `scale` 不受影响：它是 `SkinNote.setLaneRegion()` 在加载期直接赋值的（`SkinNote.java:47`）。
>
> ⚠️ **同一陷阱还有第二处：`SkinNote` 自己的 `off[]` 也全是 null**（2026-09-20 二次实机踩到）。
>
> `SkinObject.off[]` 的初始值是 `EMPTY_OFF`（长度 0），加载期按 `offset[]` 长度重新申请成
> `new SkinOffset[n]` —— 但这只是**申请，元素全为 null**（`SkinObject.java:781`），
> 真正的值由 `prepareRegion()` 里的 `off[i] = state.getOffsetValue(offset[i])` 填
> （`SkinObject.java:335`）。`SkinNote.prepare()` 挂在第一行的 cast 上 → 它自己的
> `prepareRegion()` 也没跑 → `getOffsets()` 返回一个**长度正确、元素全 null** 的数组。
>
> 本层 `for (SkinOffset offset : offsets) { offsetX += offset.x; … }` 不判空 → NPE →
> `draw()` 抛出 → 被 `drawAllObjectsSafely` 静默吞掉。症状极其误导：**几何全对
> （`hu / hl / travel` 都正常打印）、`draw()` 也确实被调用了，但屏幕上零音符**。
> 修法是遍历时 `if (offset == null) continue;`。
>
> **教训**：`SkinNote` 在预览环境里是"半死不活"的——对象自己没 prepare 完，凡是它派生出来的
> 状态（`region`、`off[]`、`lane.noteImage`…）**都不可信**，要么自己重算，要么判空兜住。

### 3. 几何：与 `LaneRenderer.drawLane()` 逐行同源

| | 横屏 | 竖屏（option 1101） |
|---|---|---|
| 出生端 `hu` | `region.y + region.height` | `region.x + region.width` |
| 判定线 `hl` | `enableLift ? region.y + region.height * lift : region.y` | `(region.x + 40) + (region.width - 40) * lift` |
| 下落方向 | Y 递增（上→下） | X 递减（右→左） |
| 音符矩形 | `x = region.x + offsetX`，`w = region.width + offsetW`，`h = scale + offsetH` | `w = region.height + offsetH`、`h = scale + offsetW`，**以 pos 为中心**、旋转 270° |

> 竖屏的 `hl` **不**用 `enableLift` 把关 —— 这一点是照抄 `LaneRenderer.java:399`，
> 那边就是这样写的，别"顺手修正"。

一屏穿越时间用真实公式 `240000 / bpm / hispeed`（ms），演示 BPM 固定 **150**，
结果钳到 **[300, 3000] ms** —— 防止用户极端的 hispeed 让预览变成瞬移或爬行。

`SkinNote` 自己的 `dst` 偏移（`getOffsets()`）累加后套用，保证音符落在和真实渲染相同的位置。

### 4. 用用户自己的设置，而不是默认值

`resolveConfig()` 每实例只跑一次，从 `state.resource.getPlayerConfig().getPlayConfig(mode)` 读：

- **hispeed** → 下落速度；
- **lift** → 判定线位置；
- **lane cover** → 遮挡区内的音符**直接不画**。本层画在罩子之后，若照画反而会浮在罩子上面。

拿不到配置就退化成 hispeed 1.0 / 无 lanecover / 无 lift，不抛异常。

### 5. 合成图案（明确不是谱面）

16 槽 = 2 小节 4/4 的 8 分音符网格，循环播放。`PRIMARY` 给主音符（对键位数取模，`-1` = 空槽），
`SECOND` 给和弦副音符的偏移，`SCRATCH` 标记皿的槽位。相位用 `state.timer.getNowTime()`
对一个循环取模，**不依赖 `prepare()` 传进来的 `time`** —— 否则下落会和 prepare 节流耦合、看起来卡顿。

只有普通音符（单点 / 和弦 / 皿）；**没有长条、地雷、音符扩张动画**。长条缺席连带的后果是
`TIMER_HOLD_*`（70~77，LN 按住时的轨道光）永远不会亮 —— 见 8 节末尾。

同一套图案除了画音符，还负责点亮 keybeam / bomb 的计时器（见第 8 节）。

### 6. 容错：任何异常都不能让本层被永久停用

`prepare()` 里取贴图和 `super.prepare()` 各自 try-catch（预览环境没有 play 数据，
`destination` 求值失败是预期的），最后强制 `draw = true`；`draw()` 里逐音符判空。
这样 `drawAllObjectsSafely` **不会**把本对象 `draw` 置 false —— 与 `SkinNote` 的遭遇正相反。

`draw()` 外层还留了一层 try-catch + **一次性**日志，这条别删：`drawAllObjectsSafely`
吞掉异常后**只把 `draw` 置 false，不打印任何东西**，"几何算对了却零音符"这种症状
只有靠它才能定位（`off[]` 全 null 那次的 NPE 就是它抓到的）。

`dispose()` 只把 `noteImages` 数组清空：贴图归预览皮肤所有，这里**不释放任何纹理**
（释放由 `SkinConfiguration.dispose()` → `setSelectedSkin(null)` 负责）。

### 7. 两个设计细节（别改回去）

- **`portrait` 判定延迟到 `prepare()`**：`Skin.prepare()` 会清空 option 表，构造函数里读
  `skin.getOption()` 拿到的是空的。改为首次 `prepare()` 时判定一次（`portraitResolved` 标志），
  路径与 `LaneRenderer` 一致：先看 header 的 `Layout` 自定义项，再看 option 表。
- **构造函数必须调 `setDestination(...)`**：没有 destination 的对象会在 `validate()` 阶段被删掉。
  给的那条只用于通过校验，真正的绘制坐标全部由轨道矩形算出来。

### 8. keybeam / bomb：与判定同一个病根，也由本层点亮（**第二轮反馈补上**）

现象：音符、血条、判定图、combo 都有了，**按键光柱和爆炸特效还是没有**。

根因与第七节判定 / 连击**完全一样**——这两类东西在皮肤里不是特殊对象：

- `keybeam` 是普通 `SkinImage`，dst 挂 `timer = TIMER_KEYON_*`（100~107 / 110~119）；
- `bomb` 也是普通 `SkinImage`（`bomb.png` 的 10 帧切片），dst 挂
  `timer = TIMER_BOMB_*`（50~57 / 60~69）。

计时器 off 时 `SkinObject.prepareRegion()` 直接
`if (timer.isOff(state)) { draw = false; return; }` —— 所以它们不是"画错位置"，而是**压根不画**。
真游玩里分别由 `KeyInputProccessor.input()`（按键）和
`JudgeManager.updateMicro()`（判定，`judge <= skin.getJudgetimer()` 时）点亮。

实现落在 `PreviewNoteLayer.pulseFeedback(now, stepMs, cycleMs)`：

| 做什么 | 怎么做 | 依据 |
|---|---|---|
| lane → 皮肤 key / player 编号 | `new LaneProperty(mode)`，与 `BMSPlayer` 同一个构造 | `BMSPlayer.java:518` |
| 按下的判定 | `sinceArrival < KEY_HOLD_MS` | 与 `KeyInputProccessor.input()` 的"按下/松开"同义 |
| bomb 触发 | 每个槽的 `hitCycle` 变化时点一次 | 序号 = `floor((now - slot*stepMs) / cycleMs)`，每循环 +1 |
| len 光柱显隐 | 按下 → `setTimerOn(on)` + `setTimerOff(off)`；松开 → 反过来 | `SkinPropertyMapper.keyOnTimerId/keyOffTimerId` |

**四个必须遵守的点：**

1. **`KEY_HOLD_MS` 不能小于 keybeam 自己的展开动画**。参考皮肤 play5 的 keybeam 是
   `{"time":0, …narrow}, {"time":100, …wide}, "loop":100`，即 0→100ms 展开；松早了会在
   展开到一半被掐掉，看起来像闪一下。现取 150ms（也刚好让 150BPM 的 8 分音符之间留出间隙）。
2. **bomb 不需要考虑残留**：它的 dst 是 `loop:-1`，计时器一直不重置的话 `time` 只会越走越大，
   早就超过 `endtime` 不画了（`prepareRegion` 把 `time` 置 -1 → `starttime > time` → `draw=false`）。
   但 **keybeam 会残留** —— `loop:100` 且 `lasttime == dstloop` 时 `time` 被钉在 `dstloop`，
   计时器亮着就一直显示。所以换皮肤时在 `dispose()` 里走一遍 `releaseTimers()` 把 ON 熄掉
   （新皮肤的 `pulseFeedback` 下一帧也会纠正，两道都留）。
3. **首帧只登记不触发**（`inputSeeded`）。否则 16 个槽的 `hitCycle` 从默认值一起跳变，
   一进界面 16 道槽同时炸。登记之后本帧仍然照常刷按键状态，所以上一张皮肤残留的光柱当帧就被清掉。
4. **触发用"到达序号"而不是"距判定线不足几 ms"**。序号每过一个循环 +1，掉帧不漏触发、
   同一个音符也不会连着两帧炸两次；用"距离窗口"则会随帧率抖。（同理见第七节第 3 条。）
5. **`keyOnTimerId` / `bombTimerId` 都可能返回 -1**（player ≥ 2 或 key ≥ 100），
   必须先判 `>= 0` 再 `setTimerOn` —— `TimerManager.setMicroTimer(-1, …)` 会走到
   `current.getSkin().setMicroCustomTimer(-1, …)` 那条自定义计时器分支上去。

**已知未覆盖**：`TIMER_HOLD_*`（70~77，长条按住时的轨道光）不会亮。它要求图案里有长条，
而本层只合成单点 / 和弦 / 皿。要做就得给 `PreviewNoteLayer` 加一段 LN 绘制，比点亮计时器
复杂得多（`LaneRenderer.drawLongNote` 里还有 CN / HCN 的 bodyIdx/headIdx/tailIdx 分支）。

## 七、合成游玩态数值：`PreviewPlayValues`

> 目标：预览里**判定 / 连击 / 血条**也显示出来 —— 而且用皮肤自己的美术
> （判定图、连击数字、量表条都是皮肤里定义的对象，这里只负责喂值）。
> 文件：`core/.../play/PreviewPlayValues.java`（新增，`implements PlayStateValues`）。

### 1. 为什么抽接口，而不是在预览里造一个真 `BMSPlayer`

`SkinJudge` / `SkinGauge` 原本写的是 `(BMSPlayer) state` 强转，预览里 state 是
`SkinConfiguration` → 异常被 `drawAllObjectsSafely` 吞掉 → 对象被永久置 `draw=false`。
而造真 `BMSPlayer` 的代价与风险见第五节（`create()` 会动输入处理器 / `FileCache` /
皮肤缓存等全局单例）。

所以只把**这三个值**抽成最小契约：`bms.player.beatoraja.PlayStateValues`
（`getNowJudge` / `getNowCombo` / `getGauge`）。

| 角色 | 实现 |
|---|---|
| `MainState`（默认） | 返回 `null` —— 调用方判空后不画，等于「这个界面没有游玩态」 |
| `BMSPlayer` | `implements PlayStateValues`，直接转发 `JudgeManager` / `GrooveGauge`，**取值与改动前一致** |
| `SkinConfiguration` | 返回 `PreviewPlayValues`（合成），只在成功加载 play 皮肤预览时非空 |

接口声明在 `bms.player.beatoraja`（`MainState` 所在包），因为它是「状态向 play 皮肤暴露的契约」；
`BMSPlayer` 本来就有 `import bms.player.beatoraja.*`，零额外导入。

> 结果界面（`AbstractResult`）不受影响：`getPlayStateValues()` 拿到 `null`，
> 继续走原来的 `state.resource.getGrooveGauge()` 分支；`SkinJudge` 在那边本来就是 `draw=false`
> （改动前是强转抛异常被吞掉，现在是显式判空返回，行为一致）。

### 2. 合成方式（刻意不是真实得分）

- **判定**：每 400ms 在 `{PERFECT, PERFECT, PERFECT, GREAT, PERFECT, PERFECT, GOOD, PERFECT}`
  之间轮换 —— 大部分时间是 PERFECT，看着稳；
- **连击**：随判定次数递增（1 起算、绕圈）；从 0 起会让皮肤里的数字看起来像「还没开始打」；
- **量表**：20s 一个周期，从 20% 平滑涨到满，然后重来。

### 3. 判定 / 连击的 dst 挂着「显示计时器」，必须自己点亮（**上线后立刻踩到**）

症状：血条出来了，但**判定图与 combo 数字一个都不显示**。

判定图的 destination 长这样（默认皮肤 `play5.json:329`）：

```json
{"id":"judgef-pg", "loop":-1, "timer":46, "offset":3, "dst":[
  {"if": [920], "value": {"time":0, "x":70,   "y":240, "w":180, "h":40}},
  {"if": [921], "value": {"time":0, "x":1010, "y":240, "w":180, "h":40}},
  {"time":500}
]}
```

- `timer: 46` = `SkinProperty.TIMER_JUDGE_1P`。2P 是 47、3P 是 **247** —— 别写成
  `TIMER_JUDGE_1P + player`，48 是 `TIMER_FULLCOMBO_1P`。combo 数字是
  `TIMER_COMBO_1P/2P/3P = 446/447/448`（本例里的 combo 数字也挂 46）。
- `if: [920] / [921]` 是**皮肤选项**（`play5.json:14` 把 `1P/2P` 定义成 op 920/921），
  由 `JsonSkinSerializer.ArraySerializer` 在加载时按选项裁剪 —— 和 timer 无关。
  默认选项是列表第一个，所以预览里能拿到 1P 那一支，不是这里的问题。

`SkinObject.prepareRegion()` 开头就是：

```java
if (timer != null) {
    if (timer.isOff(state)) { draw = false; return; }   // ← 判定图在这里就没了
    time -= timer.get(state);
}
```

而 `TimerManager.setMainState()`（`MainController` 切状态时调用，`MainController:380`）
会把**全部计时器**清成 `INVALID_TIMER`。所以进 SKIN SELECT 后 timer 46 恒为 off →
判定图 `draw = false` 直接返回、连 region 都不计算；combo 数字则因为
`SkinJudge` 要先有 `nowJudge.draw` 才去 prepare 它，跟着一起消失。
**血条没事**是因为 `SkinGauge` 的 dst 不挂计时器。

修法就是照抄真游玩语义：`JudgeManager` 每次判定成功做两步
（`JUDGE_TIMER` / `COMBO_TIMER` 的 `setTimerOn`，见 `JudgeManager:746` 与 `753`）：

```java
timer.setTimerOn(TIMER_JUDGE_xP);
timer.setTimerOn(TIMER_COMBO_xP);
```

`PreviewPlayValues.getNowJudge()` 在判定序号变化时（每 400ms）执行同样两步，
判定动画因此能正常跑一轮而不是冻在第 0 帧。只点亮、不清除：dst 自己在
`time > endtime`（本例 500ms）后就 `draw = false`，下一轮再点。这些计时器是全局槽位，
但离开 SKIN SELECT 时会被 `setMainState()` 统一清掉，**不会漏进真实游玩**。

> **推论**：任何 dst 挂 `timer:` 的元素在预览里都画不出来 —— 炸弹特效
> `TIMER_BOMB_*`、全连 `TIMER_FULLCOMBO_*`、以及 `TIMER_PLAY` 系的分数 / 游玩时间等。
> 每补一个都要自己合成「什么时候该亮、亮多久」。

### 4. 三个容易踩的点

- **必须给量表一个非空 model。** `GrooveGauge` 的增减补正里
  `GaugeModifier.TOTAL = f * model.getTotal() / model.getTotalNotes()` —— 总音符数为 0
  会算出 Infinity / NaN 留在量表内部数组里。所以 `createDemoModel()` 造了一个**纯内存**的
  极小谱面（1 个 `NormalNote` + `TOTAL = 1.0`）—— **不需要谱面文件**，
  这也是"直接上 BMSPlayer 需要谱面"这个判断不成立的原因之一。
- **量表的推进挂在 `getGauge()` 里。** `SkinGauge` 每帧 `prepare` 会取一次量表，
  取用时按当前时间把值设到周期内的位置，因此不必额外加每帧钩子。
  低点取 20% 而不是 0：`Gauge.setValue` 有 `if (this.value > 0f)` 前置判断，
  HARD 系的 `min` 又是 0 —— 一旦掉到 0 就**再也写不进去**（量表永久死住）。
- **`SkinGauge` 里 `state.resource.getBMSModel().getMode()` 必须判空。**
  预览环境 resource 上没有正在游玩的谱面，`getBMSModel()` 可能是 null。
  这一段只是在「原模式 ≠ 游玩模式」时调整量表颗粒数，拿不到跳过即可；
  漏了的话 NPE 会让整个量表对象被永久停用。

### 5. 接入顺序

`SkinConfiguration.loadSelectedSkinPreview()` 里在 `preview.prepare(this)` **之前**调用
`setupPreviewPlayValues(preview)` —— 因为 `SkinJudge` / `SkinGauge` 在首次 `prepare`
就会向 `getPlayStateValues()` 取值。方法开头先把上一轮的 `previewPlayValues` 置 null，
加载失败路径也置 null，保证「没有 play 预览」时一定返回 null。

## 八、验证方法

1. 进 SKIN SELECT，灰块位置应出现当前皮肤的画面；
2. 左右切皮肤，画面应立刻跟着变；
3. 改右侧 Lane Size / Scratch Side，停手约 0.12s 后预览应重新加载并反映变化；
4. 退出界面再进，不应崩溃、不应越来越卡（泄漏的话第二次进入会明显变慢）；
5. **预览里应有音符持续下落**（自绘循环图案），宽度贴合轨道、厚度来自皮肤；
   改 hispeed / lane cover / lift 后下落速度与判定线位置应跟着变，开启 lane cover 时
   遮挡区内不应有音符浮在罩子上；
6. 竖屏皮肤（option `Layout = 1101`）里音符应**横向**从右往左落，且旋转方向正确；
7. 想看日志要 `adb logcat -s beatoraja` —— core 用的是 `java.util.logging`，默认写 stderr，
   在 Android 上等于黑洞；由 `AndroidLauncher.onCreate` 里的 `LogcatLogHandler.install()`
   桥接到 logcat（tag `beatoraja`）。其中 `皮肤预览渲染失败，已停用本预览 : …` 属预期降级，
   不影响界面本身。
8. **预览里应有判定图、连击数字与血条**（第七节的合成值）：判定每 0.4s 在
   PERFECT / GREAT / GOOD 之间轮换，连击递增，血条 20s 内从 20% 涨满再重来；
   换 HARD / EASY 等量表类型时血条 border 位置应跟着变。
9. **预览里应有按键光柱（keybeam）与爆炸特效（bomb）**：音符落到判定线的那一帧，
   对应轨道的 keybeam 应亮起约 150ms 后消失（第六节第 8 条）；判定图 / combo 画不出来时，
   会打印一次原因。注意 keybeam 在 play5 里的绘制顺序在 `notes` **之前**，所以它比音符
   晚一帧反应，属正常。
10. **换皮肤时不该残留光柱**：在光柱正亮着的时候左右切皮肤，新皮肤的预览里不该有
   一道不动的光柱（`dispose()` → `releaseTimers()` + 新层首帧刷新两道保险）。
11. 保留的**诊断日志**只有两处，都只在出问题时输出：
   - `PreviewNoteLayer` 里 `draw()` 的异常日志（见第六节第 6 条）——发现"几何算对了却
     零输出"的唯一手段；
   - `SkinJudge.previewDiag()` —— 判定 / combo 在预览里画不出来时打印一次原因
     （`judgenow` / `nowJudge.draw` / 计时器状态），真游玩不输出。判定消失时先看它。

## 九、注意：必须提升 versionCode

内置皮肤只在 **versionCode 变化**时从 APK assets 覆盖到 `filesDir/skin`
（`AndroidLauncher.checkVersionAndCopyAssets`）。改了 `skinselectmain.lua` 却不动 versionCode，
真机上跑的还是旧 lua —— 灰块依旧。本次已 15 → 16。

## 十、退场动画把预览变黑 + result / courseresult 预览解锁（2026-09-20 第三轮反馈）

### 1. 症状与病根：decide 类皮肤"只显示一次，随后整块变黑，切走再切回仍是黑的"

病根**不在预览层，在皮肤自己的"退场动画"**：

```lua
-- skin/m_select/decide/decidemain.lua:159（m-select decide 皮肤）
{id = -110, loop = skin.scene, dst = {
    {time = skin.scene - 200, x = 0, y = 0, w = 1920, h = 1080, a = 0},
    {time = skin.scene, a = 255}}}
```

- `-110` = `IMAGE_BLACK`（全屏黑图），末帧 `a = 255` = 完全不透明；
- 该皮肤 `scene = 2500`、`fadeout = 1000`；
- `loop == 最后一帧的 time` → `SkinObject.prepareRegion()` 走
  `if (lasttime == dstloop) time = dstloop;` —— **时间一超过它就"钉在末帧"**。

而预览的 state 是 `SkinConfiguration`，`TimerManager.getNowTime()` 返回的是
**"进入皮肤选择界面以来的毫秒数"**，只增不减，也不会因为换皮肤而回退。于是：

进入界面约 2.5 秒 → 黑图淡入到 `a=255` 并被钉死 → 预览永久全黑；换别的皮肤再换回来，
计时器仍在 2.5 秒之后 → 依旧全黑。**play 皮肤不受影响**（它们基本不声明 `scene`，
= `Skin.SCENE_UNSPECIFIED`，时间无上限），这正是第一轮 note/judge/keybeam 预览能正常显示的原因。

### 2. 修法：给预览一个自己的时钟，并钳制在"稳态显示窗口"内

- `SkinPreview` 新增 `clockStartNanos`（`System.nanoTime()`），
  **换皮肤时重置**（`previewSkin != lastSkin` 分支）—— 所以每切一次皮肤，入场动画重播一次。
- `SkinPreview.resolvePreviewTime(skin, elapsedMs)` 把动画时间钳制到
  **`scene - max(fadeout, 500)`** 为止，永不进入退场段：

```java
long span = scene - Math.max(previewSkin.getFadeout(), PREVIEW_TAIL_MARGIN_MS);
return Math.min(elapsedMs, span);
```

  `fadeout` 就是皮肤声明的退场时长（`MusicDecide:54` / `MusicResult:193` 等处
  `if (timer.getNowTime(TIMER_FADEOUT) > getSkin().getFadeout())` 用它决定何时切界面），
  正常皮肤够用；再兜一个 500ms 下限，防"有退场动画但没声明 fadeout"。
- `scene > 0 && scene < SCENE_UNSPECIFIED` 才钳制；**未声明的皮肤行为与改动前完全一致**。

对这个皮肤验算：`span = 2500 - 1000 = 1500` ms，而黑图第一帧在 `t = 2300`（`a = 0`），
所以钳制后的 `t = 1500` 稳稳落在黑图之前 → 黑图恒为透明。

### 3. 关键实现细节：**只覆盖"动画时间"，不覆盖 prepare 的节流门**

`Skin.drawAllObjectsSafely(sprite, state, timeOverrideMs)` 里
节流仍用真实计时器 `state.timer.getNowMicroTime()`，只有传给
`obj.prepare(time, state)` 的 `time` 被覆盖。原因：覆盖值被钳制后**不再单调递增**，
拿它去比 `nextpreparetime` 会让 prepare 被门挡住，对象就停在旧状态不再更新。

### 4. result / courseresult 原本完全不预览 —— 确认是"上游就没做"，本分支已放开

`SkinConfiguration.loadSelectedSkinPreview()` 里原本（与上游 `beatoraja-master` **逐字相同**）：

```java
if (selectedSkinHeader == null || config == null || type == SkinType.SKIN_SELECT
        || type == SkinType.RESULT || type == SkinType.COURSE_RESULT) {
```

所以不是"忘了写"，是上游刻意排除。本分支 2026-09-20 去掉了 RESULT / COURSE_RESULT
两个条件（只留 SKIN_SELECT —— 它是宿主界面，不预览自己）。放开是安全的：

- **三条加载链都支持这两个类型**：`SkinLoader.load` 的 JSON / Lua 分支与
  `LR2SkinCSVLoader.getSkinLoader(type, …)` 的 `case RESULT` / `case COURSE_RESULT` 都有；
- **两条"play 专属补丁"本来就按类型跳过**：`attachPreviewNoteLayer()` 与
  `setupPreviewPlayValues()` 开头都是 `if (!(preview instanceof PlaySkin)) return;`
  → result 预览只是"少画音符/判定/量表"，背景与静态版式照画；
- **失败路径不变**：加载抛异常 → `Logger.warning("皮肤预览加载失败 : …")` + `setSelectedSkin(null)`
  → 退回"只有空预览框"，与放开前表现一致（日志经 `LogcatLogHandler` 进 logcat，tag `beatoraja`）。

**实机数据（三张皮肤，2026-09-20 从设备拉取）**：`m_select/result/resultmain.lua`、
`WMII_FHD_result_oraja_bmz_260830/result/resultMain.lua`、
`…/resultMain_course.lua` 全都是 **`scene = 3600000`（1 小时）**、`fadeout = 1000`，
且不含 `scene` 末帧的 `-110` 全屏退场 → 钳制后 `span ≈ 3.6e6 ms`，等于不生效，
不会出现 decide 那种黑屏。（它们的 `-110` 都是局部装饰：分数条、被 `op` 门控的黑底、
以及一个 `w=0,h=0` 的**副作用触发器**。）

⚠️ 顺带记一个坑：**皮肤 lua 里的 `draw = function() … end` 回调会在预览里被执行**。
`wmii_resultMain.lua:1980` 那个 `-110` 就挂着 `draw` 回调，里面调
`saveCurrentCourseData()` 往 `skin/WMII_FHD/result/courseData.json` **写文件**。
经查它开头是 `if isCourse == false then return end`，而 `isCourse` 来自
`main_state.option(280..290)`（课程进行中才为真，预览下 `getCourseData()` 为 null → false），
所以预览不会真的写盘。**但以后看到"皮肤里带 draw 回调 / io 操作"就要留意**：预览会执行它们。

### 5. 验证

改的是核心层，UI 上看不到新控件，只有"预览不再变黑 + result/courseresult 出画面"：
进皮肤选择界面 → 切到 RESULT / COURSE_RESULT 分类 → 预览框应有画面；
在 DECIDE 分类里选 m-select 并停留 10 秒以上，画面应**不再变黑**，
切到别的皮肤再切回 m-select，仍应正常显示。

