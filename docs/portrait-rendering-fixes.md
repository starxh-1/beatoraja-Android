# Portrait 模式和 Select BGM 问题分析与修复方案

## 问题1：Select 界面 BGM (select.ogg/wav) 无法播放

### 根因

`PreviewMusicProcessor.java` 第166-177行，Android 平台有显式的提前 return，直接禁用了 preview BGM 的播放：

```java
if (isAndroid) {
    while(!stop) {
        try {
            commands.pollFirst();
            sleep(50);
        } catch (InterruptedException e) { }
    }
    return;  // <-- 直接返回，不执行任何播放
}
```

Select BGM 的播放路径是：
1. `MusicSelector.create()` → `getSound(SELECT)` → `preview.setDefault(path)`
2. `PreviewMusicProcessor.PreviewThread.run()` → `audio.play(defaultMusic, vol, true)`

而 `audio.play(String, float, boolean)` 走的是 `AbstractAudioDriver.getKeySound()` → `GdxAudio.newSound()` 路径（与 `DECIDE` BGM 和所有效果音相同），使用的是 OboeAudio 的 `NativeSoundpool`，并非 `newMusic()`。原始注释说 "libGDX Music会native崩溃" 指的应该是 `Gdx.audio.newMusic()`，而当前路径走的是 `newSound()`，这条路径已经在所有效果音上稳定运行。

**结论：Android 端禁用 Preview BGM 是过时的保守措施，移除即可恢复。**

### 修复方案

1. 删除 `PreviewThread.run()` 中 Android 专属的 early-return 块（第166-177行），让代码走统一的 `audio.play()` 路径
2. 行内已有一个未使用的 `playAndroidMusic()` 方法（第47-99行），可作为备选（走 `OboeMusic` 流式播放），但不必要
3. 同步修复 `SystemSoundManager.shuffle()` 第69行的 SELECT 重复注册问题（上游有特殊处理但 Android fork 去掉了）：
   ```java
   // 当前:
   if (newpath.equals(oldpath)) break;
   // 上游:
   if (newpath.equals(oldpath) && !sound.equals(SoundType.SELECT)) break;
   ```

### 涉及文件
- `core/src/main/java/bms/player/beatoraja/select/PreviewMusicProcessor.java`
- `core/src/main/java/bms/player/beatoraja/SystemSoundManager.java`

---

## 问题2：Portrait 模式皮肤渲染问题

所有 Portrait 相关代码集中在以下文件：
- **Java 渲染层**: `core/.../play/LaneRenderer.java`（lane、LongNote、lanecover）
- **Java BGA 层**: `core/.../play/bga/BGAProcessor.java`（BGA 旋转变换）
- **Lua 皮肤层**: `android/assets/skin/GenericTheme for Touchscreen/play/play.lua`
- **Java 皮肤组件**: `core/.../play/SkinGauge.java`（gauge）、`core/.../skin/SkinNumber.java`（数字）、`core/.../play/SkinJudge.java`（判定数字）

Lua 脚本中的 `initPortraitGeo(geo)` 函数（第360-476行）重新定义了竖屏下的所有布局几何。

---

### (1) LongNote 头部和尾部歪斜

**问题现象：** LongNote 的 Head 和 Tail 与 Body 对不上，在判定线附近无法像 Landscape 那样对齐。

**根因分析：** `LaneRenderer.java` 第1024-1063行 `drawLongNote()` 方法：

- **Body**: `sprite.draw(longImage[bodyIdx], x - 0.5f * W, y + 0.5f * W, W, H, 0.5f, 0, 270.0f)` — 原点 `(0.5, 0)`，以底部为锚点
- **Head**: `sprite.draw(longImage[headIdx], x - 0.5f * W, y + 0.5f * W, W, headH, 0.5f, 0.5f, 270.0f)` — 原点 `(0.5, 0.5)`，以中心为锚点
- **Tail**: `sprite.draw(longImage[tailIdx], x + H - 0.5f * W, y + 0.5f * W, W, headH, 0.5f, 0.5f, 270.0f)` — 原点 `(0.5, 0.5)`，以中心为锚点

三个部分的 `y` 偏移都是 `y + 0.5f * W`，但：
- Head 的定位是 `x - 0.5f * W`（与 Body 起点对齐）
- Tail 的定位是 `x + H - 0.5f * W`（在 Body 终点 `x+H` 往左偏移）

问题在于 Portait 模式下，`W` = `lanes[lane].region.height + offsetH`（竖直方向厚度），`H` = `scale + offsetW`（水平方向长度）。Head/Tail 使用原点 `(0.5, 0.5)` 进行 270 度旋转，而旋转后的锚点计算在 X 和 Y 上的偏移可能与 Body 的不一致，导致 Head 和 Tail 看起来"歪了"。

**修复方案：**
- Head 和 Tail 应使用与 Body 相同的原点 `(0.5, 0)` 而非 `(0.5, 0.5)`，确保旋转锚点一致
- 或者将三者都改为使用 `(0.5, 0.5)` 并统一 Y 轴偏移量
- Head 的 Y 位置应精确对齐 `y`（判定线位置），Tail 应对齐 `y + H`（远程结束位置），而不是都使用 `y + 0.5f * W`

### (2) Gauge 及 Gauge 相关元素 (othertexts, rank_random, parts/number) 旋转和坐标

**问题现象：**
- rank_random 素材旋转成功但坐标错乱（屏幕出界）
- parts/number 的 PNG 无法旋转到与 lane、notegraph 一样的角度（270度）
- 即使旋转成功也只是素材本身的旋转，不是整个元素的坐标重算（如 EXSCORE 1234：EXSCORE 字样在，但数字 1234 是打竖的）

**根因分析：**

Lua 脚本中不同元素的旋转处理不一致：

1. **Gauge 背景** (第2542-2567行)：`x = 113, y = 1040, w = 1000, h = 35, angle = 270` — 基本正确，但个别素材坐标偏移是基于横屏逻辑推算的，未正确转换

2. **rank_random** (第2595-2639行)：`x = geo.gauge.x + 50, y = geo.gauge.y - 100, angle = 270` — 坐标参考 `geo.gauge` 但 `judgerank` 素材的 `space_y = 15` 间距可能导致多个图标堆叠后超出屏幕

3. **parts/number** — `SkinNumber.java` 第210-213行逐位绘制数字时，位置计算公式为 `region.x + (region.width + space) * j - shift`，完全依赖 Lua 中 `dst` 的 `x, y, w, h, angle` 属性。如果 Lua 中数字的 `angle=270` 且 `cx=0.5, cy=0.5`，则每个单独的数字字符会各自以自己的中心为轴旋转 270 度——结果是数字串变为纵向排列（1234 → 1在上、2在下、3在下...），而不是整个数字串横向旋转。

**修复方案：**
- rank_random：在 Lua 中增大负坐标偏移（`y` 起始位置更靠上），或使用循环动态计算 Y 坐标
- parts/number：`SkinNumber` 需要新增竖屏模式支持。当前架构中 `SkinNumber` 逐个数字独立绘制，每个数字的 `dst` 由 Lua `number` 对象负责定位。旋转 270 度后每个数字会变成纵向排列。
  - **方案A**：在 Lua 层手动调整每个数字位的 X/Y 坐标（水平排变竖直排），同时保持旋转
  - **方案B**：在 `SkinNumber.java` 中添加 `portrait` 模式，逐位绘制时自动交换 X/Y 坐标计算逻辑
  - **方案C**：将整个数字组作为一个整体旋转（使用 FBO 预渲染整个数字串为一个纹理，再旋转该纹理）

### (3) 竖屏判定数字方向错误

**问题现象：** 竖屏模式下判定数字（如 1234）是"打竖的"（同 Y 坐标、不同 X 坐标），正确的应该是同 X 坐标、不同 Y 坐标。

**根因分析：**
这是与 (2) 中 parts/number 相同的问题。`SkinNumber` 的 `draw()` 方法在渲染数字时，逐位计算 X 位置：
```java
float x = region.x + (region.width + space) * j - shift;
```
每个数字的 `region` 由 Lua 中的 `number` 对象定义。当 `angle=270` 且 `cx=0.5, cy=0.5` 时，每个单独的数字绕自身中心旋转 270 度。因为数字之间原本是水平排布的（不同 X、相同 Y），旋转后再按原坐标渲染，就变成了打竖排列。

**修复方案：**
在 `SkinNumber.java` 中添加竖屏模式。当检测到竖屏时，数字的绘制坐标计算由水平偏移（X 方向）改为垂直偏移（Y 方向），使得旋转后数字在视觉上是正确排列的：
```java
if (portrait) {
    float y = region.y - (region.height + space) * j + shift;  // 竖直排列
    // x 保持不变
    sprite.draw(image, region.x, y, w, h, cx, cy, 270f);
} else {
    float x = region.x + (region.width + space) * j - shift;  // 水平排列
    sprite.draw(image, region.x + ..., region.y + ..., ...);
}
```

或者更干净的方案：在 `SkinNumber` 中，当旋转角度接近 270° 或 90° 时，自动交换宽度/高度计算逻辑。

### (4) BGA 坐标无法覆盖 lane

**问题现象：** Portrait 模式下 BGA 无法覆盖到整个 lane 下方。

**根因分析：**
`BGAProcessor.java` 中有两个竖屏 BGA 绘制路径，中心点计算不一致：
- `drawBGAFixRatio` (第502-519行)：`sprite.draw(image, centerX - tmpRect.width/2f, ...)` — 使用 `tmpRect.width/2`
- `drawBGAFixRatioToRect` (第619-633行)：`batch.draw(image, centerX - tmpRect.height/2f, ...)` — 使用 `tmpRect.height/2`

这两者的中心偏移不一致，导致 FBO 渲染的 BGA 纹理（在 `LaneRenderer.drawLane()` 中使用）与实际 BGA 位置有偏移。

另外，`LaneRenderer.drawLane()` 第471-539行中，BGA 被提取为背景纹理覆盖在 lane 上，采样坐标可能不覆盖完整的 lane 区域。

**修复方案：**
1. 统一 `drawBGAFixRatio` 和 `drawBGAFixRatioToRect` 的竖屏中心计算方法
2. 在 `LaneRenderer.drawLane()` 的 BGA 背景绘制中，调整竖屏下的采样矩形，使其完整覆盖 lane 区域

### (5) Lanecover 横屏状态问题

**问题现象：** Lanecover 图片素材仍处于横屏状态，需做成竖屏，并将调整方式由横屏左右滑动改为竖屏上下滑动。

**根因分析：**
`LaneRenderer.java` 第414-426行，竖屏模式下的 lanecover 偏移：
```java
main.main.getOffset(OFFSET_LANECOVER).x = (float) ((hu - hl) * lanecover);
```
这沿 X 轴移动，与音符水平下落方向一致。但从用户角度看：
- 横屏模式：音符下落方向是 **Y 轴（上下）**，lanecover 沿 Y 轴上下滑动覆盖
- 竖屏模式：音符下落方向是 **X 轴（左右）**，lanecover 沿 X 轴左右滑动覆盖

用户期望的竖屏行为是将 lanecover 旋转 270 度后显示在屏幕上（此时从用户视角看仍是"上下"滑动）。

在 Lua 脚本中，`lanecover` 滑块（第2185-2284行）使用 `angle = 2` 滑块定向，且 lift 覆盖在竖屏下有水平偏移处理（第2240-2249行）。但 lanecover 素材本身没有旋转 270 度——它仍保持横屏方向。

**修复方案：**
1. Lua 中的 lanecover 目标添加 `angle = 270`，使素材在竖屏模式下旋转
2. 在竖屏模式下，lanecover 滑动方向由横屏的 Y 轴映射到 X 轴（已在 `LaneRenderer` OFFSET_LANECOVER 中处理）
3. lanecover 显示数字（`num_lanecover`）也需要以 `angle = 270` 渲染
4. 确保 lanecover 的 X 坐标参考 lane 区域的正确边缘

---

## 修复顺序建议

1. **先修复 Select BGM**（独立、无依赖、风险低）
2. **再按依赖顺序修复 Portrait 问题**：
   - (1) LongNote Head/Tail → 只改 `LaneRenderer.java`
   - (5) Lanecover → 改 `LaneRenderer.java` + Lua
   - (3) 判定数字方向 → 改 `SkinNumber.java` + Lua
   - (2) Gauge 相关元素 → 主要改 Lua 坐标
   - (4) BGA 坐标 → 改 `BGAProcessor.java` + `LaneRenderer.java`

## 涉及文件汇总

| 文件 | 问题 |
|------|------|
| `core/.../select/PreviewMusicProcessor.java` | Select BGM 被禁用 |
| `core/.../SystemSoundManager.java` | SELECT 重复注册 |
| `core/.../play/LaneRenderer.java` | LongNote Head/Tail、Lanecover、BGA 背景 |
| `core/.../play/bga/BGAProcessor.java` | BGA 中心点计算不一致 |
| `core/.../skin/SkinNumber.java` | 无竖屏数字排列支持 |
| `android/assets/skin/GenericTheme for Touchscreen/play/play.lua` | Gauge、rank_random、Lanecover、数字坐标 |

---

## 实施记录 (2026-05-31)

### 已完成修复

#### 1. Select BGM (PreviewMusicProcessor.java)
- 删除了 Android 专属的 early-return 块，统一使用 `audio.play()` 路径
- 删除了未使用的 `playAndroidMusic()` 方法和相关字段
- `SystemSoundManager.java`: 修复 SELECT BGM 重复注册问题（`newpath.equals(oldpath) && !sound.equals(SoundType.SELECT)`）

#### 2. LongNote Head/Tail 对齐 (LaneRenderer.java)
- 统一 Body/Head/Tail 三部分使用原点 `(0.5, 0.5)` 进行 270° 旋转
- Body: 中心位于 `(x + H/2, y + W/2)`，覆盖从 x 到 x+H 的水平范围
- Head: 中心位于 `(x, y + W/2)`，对齐判定线
- Tail: 中心位于 `(x + H, y + W/2)`，对齐尾部终点

#### 3. 竖屏数字方向 (SkinNumber.java)
- `draw()` 方法新增 portrait 检测：当 `angle == 270 || angle == 90` 时自动切换为纵向排列
- Portrait: `region.y - (region.height + space) * j + shift`（Y 轴排列）
- Landscape: `region.x + (region.width + space) * j - shift`（X 轴排列，保持原行为）
- 旋转后数字从左到右正常阅读

#### 4. Lanecover 竖屏方向 (play.lua)
- 新增 `isPortraitLayout()` 分支：lanecover 以 270° 旋转 + `cx=0.5, cy=0.5` 渲染
- 位置在 `(geo.lane.x, geo.lane.y)`，覆盖 `geo.lane.h` 高度
- `num_lanecover` 数字也以 270° 旋转定位在 lane 左边缘
- LaneRenderer 中的 OFFSET_LANECOVER X 轴偏移已正确（无需修改）

#### 5. Gauge/rank_random 坐标 (play.lua)
- rank_random 元素新增 `cx = 0.5, cy = 0.5` 确保旋转围绕素材中心
- random 位置保持在 judgerank 下方（`y + judgerank_w + space_y`），视觉上在 phone 右侧
- BGA 区域起点从 `geo.lane.x + judgeline_h + 5` 改为 `geo.lane.x`，完整覆盖 lane

#### 6. BGA 处理器 (BGAProcessor.java)
- 经分析，`drawBGAFixRatio` 和 `drawBGAFixRatioToRect` 的旋转中心计算数学等价（两者都将 pivot 置于 `(centerX, centerY)`），无需修改
