# Portrait 模式第二轮问题分析

## 问题1：Lanecover 影响 Note 对齐判定线

### 现象

竖屏下移动 lanecover 滑块时，hispeed 随之降低，note 与小节线、判定线发生错位——note 已完全掉落好几百毫秒后才触发判定。

### 根因分析

问题分三层：

#### A. 滑块方向与 Note 下落方向不匹配

`play.lua` 第2188行滑块定义：
```lua
angle = 2, range = geo.lane.h
```

- `angle = 2` → 方向为「下」(Y轴)，滑块只响应 Y 轴触摸
- `range = geo.lane.h = 1080` → 滑块总行程 = 竖屏 buffer 的 lane 高度

但在竖屏下，note 沿 **X 轴** 水平下落（从右到左），lane 实际长度为 `geo.lane.w ≈ 1594`。滑块：
- 方向仍然是 Y 轴（与 X 轴 note 下落垂直），用户在竖屏上拖动滑块时，触摸映射到的方向不对
- range 为 1080，仅覆盖实际 lane 长度的 67.7%

这导致滑块返回值无法正确映射到实际的 lanecover 偏移量。

#### B. Lanecover 视觉目标位置在判定线一侧

上一轮修复中，将 lanecover 目标放在了：
```lua
{x = geo.lane.x, y = geo.lane.y, ...}
```

`geo.lane.x = 226` 是判定线位置（左端），而 lanecover 应从 **spawn 侧**（右端，`geo.lane.x + geo.lane.w`）开始向判定线方向延伸。

#### C. OFFSET_LANECOVER 的 X 偏移方向

`LaneRenderer.java` 第418行：
```java
OFFSET_LANECOVER.x = (float) ((hu - hl) * lanecover);
```

`hu = lanes[0].region.x + trackWidth`（spawn，右端），`hl = lanes[0].region.x + ... + 40`（判定线+40修正）。

当 lanecover 从 0→1 时，此 offset 为正值（向右移动），而 lanecover 视觉应向判定线方向（左）移动以覆盖 note。

#### D. currentduration 公式

`currentduration = region * (1 - lanecover)` 降低了可见时间窗口。当滑块值与实际 lane 几何不匹配时，duration 的缩减量与实际视觉效果不同步，导致 note 位置判定严重错位。

### 修复方向

1. **滑块定义需要区分横屏/竖屏**：竖屏下 `angle = 1` 或 `3`（水平方向）且 `range = geo.lane.w`
2. **Lanecover 目标位置**：改为 spawn 侧 `x = geo.lane.x + geo.lane.w`
3. **OFFSET_LANECOVER 方向**：竖屏下应为负 X 偏移（向左/向判定线移动）

---

## 问题2：BGA 无法对齐 Lane

### 现象

Portrait 模式下 BGA 渲染位置与 lane 区域不对齐。

### 根因分析

`LaneRenderer.java` 第520-538行，lane 背景 BGA 的采样逻辑存在 **Y-up / Y-down 坐标混淆**：

1. `renderBGAToFramebuffer()` 将 BGA 渲染到 FBO 中，FBO 使用 **Y-up** 坐标（OpenGL 默认）
2. FBO 渲染时做了转换：`tmpRect.y = height - targetRect.y - targetRect.height`
3. `getSharedBGATexture()` 返回 **Y-up** 原始 FBO 纹理
4. `drawLane()` 中采样时：
   ```java
   TextureRegion bgaRegion = new TextureRegion(bgaFrame, srcX, srcY, srcW, srcH);
   ```
   `srcY` 直接使用 **Y-down** 的 lane 坐标（如 `srcY = 0`），但 `bgaFrame` 是 **Y-up** 纹理。
   
   在 Y-up 纹理中，`srcY = 0` 对应纹理底部，而非 Y-down 中「lane 顶部」。正确的应是：
   ```java
   srcY = bgaFrame.getHeight() - srcY - srcH;
   ```

5. 随后 `bgaRegion.flip(false, true)` 做了二次翻转，但此时采样坐标已是错误的，翻转无法纠正。

当前结果：lane 背景显示的 FBO 采样区域与实际 BGA 位置存在垂直错位。

### 修复方向

采样前将 Y-down 坐标转为 Y-up：
```java
int fboSrcY = bgaFrame.getHeight() - srcY - srcH;
TextureRegion bgaRegion = new TextureRegion(bgaFrame, srcX, fboSrcY, srcW, srcH);
bgaRegion.flip(false, true);
```

---

## 问题3：判定数字出界且间距过大

### 现象

- SkinNumber 的数字渲染后 Y 坐标出界（横屏视角下 Y 坐标超出了 1080 buffer 高度）
- 数字之间的间距比横屏模式下大

### 根因分析

#### A. 数字出界：GaugeValue 的 Y 起始坐标错误

`play.lua` 第2589-2590行，竖屏下 gaugevalue 定位：
```lua
local start_y = geo.gauge.y + 350
```

`geo.gauge.y = 1040`，所以 `start_y = 1390`。但 buffer 高度仅 **1080**。

Gauge 本身（`x=113, y=1040, w=1000, h=35, angle=270, cx=0.5, cy=0.5`）经 270 度旋转后，视觉中心在 `(613, 1057.5)`，纵向跨度从 Y≈558 到 Y≈1558（顶部超出 1080）。Gauge 的「底端」（0% 位置）对应 Y≈558。

GaugeValue 的 `start_y` 应该在 gauge 底端附近（Y≈540），而非 Y=1390。

正确的计算公式：
```lua
local start_y = geo.gauge.y - geo.gauge.w / 2 - offset
-- = 1040 - 500 - margin ≈ 530
```

当前 `start_y = 1390` 远在 buffer 之外，导致数字完全不可见。

#### B. 数字间距

`SkinNumber.draw()` 中竖屏分支（上一轮修复）：
```java
final float y = region.y - (region.height + space) * j + shift;
```

`region.height = 35`（w=h=35 的正方形数字），`space` 默认为 0（Lua 中 `number()` 未传递 `space` 参数）。

横屏计算公式：
```java
region.x + (region.width + space) * j - shift
```

同样 `region.width = 35, space = 0`。由于 `region.width == region.height`，间距计算相同。

因此「间距过大」的原因可能并非 SkinNumber 代码，而是：
- 数字在 Y 坐标出界后，仅部分可见，造成视觉上的间距偏差
- 或者 `start_y` 的设置不合理导致起始位置与期望不符

修正 Y 起始坐标后，间距问题应随之解决。

### 修复方向

1. GaugeValue `start_y` 改为 `geo.gauge.y - geo.gauge.w / 2 - margin`（约 530）
2. 其他竖屏 number（exscore, hispeed 等）的 Y 坐标同样需要排查和修正

---

## 问题4：Gauge 相关素材未能旋转

### 现象

Gauge 周围的文字素材 (`text_image_exscore`, `text_images_hispeed` 等) 在竖屏下未旋转 270°，仍保持横屏方向。

### 根因分析

`play.lua` 第2730-2777行，score/hispeed 区域的定义**没有竖屏分支**：

```lua
-- score 区域 (lines 2730-2749)
local x = geo.gauge.x local y = geo.gauge.y - 32
table.insert(skin.destination, {id = "text_image_exscore", filter = 1, dst = {
    {x = x + 4, y = y, w = image_w * text_size / image_h * 1.06, h = text_size},
}})
table.insert(skin.destination, {id = "exscore", dst = {
    {x = x + geo.gauge.w * 0.5 - num_size * 6, y = y, w = num_size, h = num_size},
}})

-- hispeed 区域 (lines 2750-2777) - 同样无竖屏分支
```

这些 destination 均使用 `geo.gauge.x` 和 `geo.gauge.y` 定位，无 `angle = 270`，无 `isPortraitLayout()` 条件分支。

对比已有的正确旋转元素：
- `gauge` 本身（第2577行）：有 `angle = 270, cx = 0.5, cy = 0.5`
- `gaugevalue`（第2592行）：有 `angle = 270`
- `rank_random`（第2634行）：有 `angle = 270, cx = 0.5, cy = 0.5`

缺失的元素：
| Element ID | Type | 需要添加 |
|---|---|---|
| `text_image_exscore` | Image | `angle = 270` + 竖屏坐标 |
| `exscore` (number) | SkinNumber | `angle = 270` + 竖屏坐标 |
| `text_images_hispeed` | Image | `angle = 270` + 竖屏坐标 |
| `hispeed` / `hispeed_ad` (number) | SkinNumber | `angle = 270` + 竖屏坐标 |
| `text_image_dot` (hispeed) | Image | `angle = 270` + 竖屏坐标 |
| Gauge area background (`id = -110`) | Rect | 竖屏下需要正确的宽高 |

### 修复方向

为以上所有元素添加 `isPortraitLayout()` 分支：
1. 设置 `angle = 270, cx = 0.5, cy = 0.5`
2. 重新计算竖屏下的 X/Y 坐标（注意 Y 坐标在旋转 270° 后映射关系）
3. Gauge area 背景需要交换宽高（竖屏下 gauge area 是横向的）

---

## 涉及文件汇总

| 文件 | 问题 |
|------|------|
| `play.lua` (slider 定义) | Lanecover 滑块方向/范围不匹配竖屏 |
| `play.lua` (lanecover dst) | Lanecover 视觉目标位置在判定线一侧而非 spawn 一侧 |
| `play.lua` (gaugevalue dst) | GaugeValue Y 坐标远超 buffer 高度 (1390 > 1080) |
| `play.lua` (score/hispeed dst) | Score/HiSpeed 区域无竖屏分支、无旋转、无坐标重算 |
| `play.lua` (gauge area bg) | Gauge 背景在竖屏下未旋转 |
| `LaneRenderer.java:418` | OFFSET_LANECOVER X 偏移方向可能反了 |
| `LaneRenderer.java:536-537` | BGA FBO Y-up/Y-down 坐标混淆 |
| `LaneRenderer.java:912` | 竖屏 late-BAD 路径 dsth 使用了错误的 region.width |
| `SkinNumber.java:210-218` | 竖屏数字排列（已修复，但 Y 起点需 Lua 配合） |

---

## 修复顺序建议

1. **Lanecover 滑块方向 + 目标位置**（独立，影响最大）→ `play.lua`
2. **BGA Y-up/Y-down 坐标修正**（独立，可并行）→ `LaneRenderer.java`
3. **GaugeValue Y 坐标修正**（依赖 SkinNumber 竖屏支持）→ `play.lua`
4. **Score/HiSpeed 区域竖屏支持**（独立）→ `play.lua`
5. **Late-BAD dsth 修正**（独立）→ `LaneRenderer.java:912`
6. **Gauge area 背景竖屏**（独立）→ `play.lua`
