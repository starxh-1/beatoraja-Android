# 开启「拉伸至全屏」后第一帧仍是等比画面（带黑边）

> 2026-09-20 排查。症状：开启 `stretchFullscreen` 后进入**练习模式（PRACTICE）**，
> 第一帧画面仍按 1920x1080 等比显示（左右/上下黑边），下一帧才铺满。
> 用户补充：**这个问题以前只在 autoplay 场景修过，而且只对 landscape 皮肤有效，
> 触摸皮肤换成 portrait 布局后修复也失效。**

---

## 一、结论先行

**主画面缺少一次"视口 + 投影矩阵的恢复"。**

`MainController.render()` 把 GL 视口与 SpriteBatch 投影设在 `current.render()` **之前**，
之后**不再复原**就直接画皮肤 / 音符 / 判定。而 `current.render()` 及其调用链上
（Stage 的 `Viewport.apply()`、BGA 的 FBO 渲染、LaneRenderer 首次初始化等）会改写
这两样 GL 状态 —— 一旦被改写，本帧的主画面就画到错误的矩形里。

这不是新问题，项目里**已经有同一类问题的先例与修法**，只是当时只补了触摸指针：

> `76901306` (2026-06-21) feat: stretch-to-fullscreen option + fix touch coords
> **MainController.render: re-apply GL viewport + skin projection before drawing the
> touch pointer ring (Stage's FitViewport.apply() clobbers the viewport on select screens)**

对应的代码就在 `MainController` 里（触摸指针分支），注释原话：

```java
// 关键：上方的 stage.draw() 会调用其 Viewport.apply() 改写 GL 视口（如 SearchTextField
// 的 FitViewport）；若不恢复，触摸指针的 (gameX, gameY) 会画到错误的屏幕区域。
Gdx.gl.glViewport(viewportX, viewportY, viewportW, viewportH);
sprite.setProjectionMatrix(projMatrix.setToOrtho2D(0, 0, skinW, skinH));
```

**触摸指针有这一行，主画面没有** —— 这就是缺口。

---

## 二、已排除的路径（不要再重复查）

| 假设 | 结论 | 依据 |
|---|---|---|
| 拉伸逻辑有缓存 / 只在某些帧生效 | **否** | 视口每帧重算，判据只有 `config.isStretchFullscreen()`（`MainController.render` L807 附近） |
| `config` 读错了 / 开关没生效 | **否** | 设备上 `config_sys.json` 确认 `"stretchFullscreen": true`；`config` 在 `MainController` 构造时读一次后不变 |
| surface 尺寸被固定成 1920x1080 | **否** | `AndroidLauncher` 用 `FillResolutionStrategy`；`GLSurfaceView20` 无 `setFixedSize` |
| BGA 的 FBO 渲染漏了视口恢复 | **否** | `BGAProcessor` 用 `glGetIntegerv(GL_VIEWPORT)` 存 `prev*` 并在两条出口都恢复 |
| 帧末的频谱渲染改坏了视口 | **否** | `SideSpectrumRenderer` 两个分支各自 `glViewport(...)`，且它在 `controller.render()` 之后、下一帧会被重置 |
| 练习面板碰了 GL 状态 | **否** | `PracticeConfiguration.draw()` 里没有 `glViewport` / `setProjectionMatrix` / `begin()` |
| `SkinObjectRenderer.viewport` 默认值 1920x1080 是元凶 | **否** | 它只是皮肤渲染器的**裁剪矩形**（优化用）；`Skin.ensureRenderer()` 首次绘制时就会 `setViewport(0,0,w,h)` |
| `config.useResolution` 导致固定分辨率渲染 | **否** | 该字段在 Android fork 里只有定义与拷贝，**没有任何使用点**（上游遗留） |
| 视口计算段有"首帧特殊处理"被删过 | **否** | `git log -L 786,830:MainController.java` 只有 3 个提交动过这段：`ce88ae59`、`e913876d`、`76901306`，都不含首帧逻辑 |

**关键事实（判据）**：画面出现黑边 ⇔ 画面被画进了 pillarbox/letterbox 矩形 ⇔
本帧生效的那个 GL 视口不是全屏。既然视口每帧都按 `config` 重算，那唯一的解释就是
**算好的视口在生效之前被别的东西覆盖了**。

---

## 三、本次改动

`core/.../MainController.java`：

1. **抽出 `applyMainViewportAndProjection()`**，把原来内联的
   `glViewport(...)` + `sprite.setProjectionMatrix(...)` 合并成一处，并在两个位置调用：
   - `current.render()` **之前**（保持原行为）；
   - `current.render()` **之后、`sprite.begin()` 之前**（**新增**，本次修复的核心）。
2. **新增临时诊断 `probeViewportDrift()`**（见下节），`viewportProbeBudget = 40` 次后自动停。
3. `render()` 开头加 `frameCounter++`（仅诊断用）。

改动是**幂等且无副作用的**：即使 `current.render()` 没有改写视口，多设一次视口与投影
也只是同一组值再写一遍。

---

## 四、诊断日志：怎么确认 / 怎么证伪

日志 tag：`VIEWPROBE`。触发条件是 **界面切换** 或 **视口值变化**（所以不会刷屏），
且漂移检查只在 `BMSPlayer` 状态下做（`glGetIntegerv` 是同步读，会打断 GPU 流水线）。

```bash
adb logcat -s VIEWPROBE
```

输出形如：

```
VIEWPROBE: f=1234 state=BMSPlayer(enter) screen=2400x1080 backbuffer=2400x1080 skin=1920x1080 stretch=true want=0,0,2400,1080
```

**三种可能的读法：**

| 观察到 | 说明 | 下一步 |
|---|---|---|
| `stretch=false` | 那一刻 config 就不是拉伸 | 查 `config.isStretchFullscreen()` 的赋值时机（与静态分析矛盾，需重新审） |
| `screen=` / `backbuffer=` 不是真实屏幕尺寸（如 `1920x1080`） | `Gdx.graphics` 报的尺寸不对 → 视口算错 | 改从 `getBackBufferWidth/Height()` 取，或在 `resize()` 里强制重算 |
| 出现 `GOT=... <== current.render() 改写了视口` | **与本次修复的判断一致** | 加固已生效，确认画面正常后即可删掉诊断 |
| 全程 `want=0,0,2400,1080` 且无 drift，但画面仍黑边 | 黑边不是 GL 视口造成的 | 转查皮肤自身的背景 dst（可能本来就留了边）或 BGA 未加载完 |

**确认根因后请移除**：`probeViewportDrift()`、`viewportProbeBudget`、`viewportProbeLastState`、
`viewportProbeLastX/Y/W/H`、`viewportProbeBuf`、`frameCounter`。

---

## 五、遗留：同源的"未恢复"还有几处

`MainController.render()` 里，`stage.draw()`（内部 `Viewport.apply()`）之后仍然**没有**重设视口，
以下绘制都直接沿用当时的 GL 视口：

- FPS 文字 + `messageRenderer`（只重设了投影矩阵，没重设视口）
- `floatingMenu.render(...)`
- `touchKeyMapper.render(...)`（PLAY 界面的触摸按键）
- `downloadIpfsMessageRenderer(...)`

触摸指针那一处因为 `76901306` 自己补了恢复，所以是好的。
若以后发现"浮动菜单 / 触摸按键 / FPS 在 select 界面画到错误位置"，多半是同一类问题，
**统一的办法是在 `stage.draw()` 之后再 `applyMainViewportAndProjection()` 一次**。

---

## 六、附：为什么"只有 autoplay 修过、只有 landscape 皮肤有效"

- **autoplay 与 practice 的渲染差异**在 `SkinBGA.draw()`：
  ```java
  if (resource.getPlayMode().mode == BMSPlayerMode.Mode.PRACTICE) {
      // 画练习面板，不画 BGA
  } else if (resource.getBGAManager() != null) {
      resource.getBGAManager().drawBGA(this, sprite, region);   // ← 只有非 PRACTICE 才走这里
  }
  ```
  另外 `BMSResource` 只在 `BGA_ON` 或（`BGA_AUTO` 且 AUTOPLAY/REPLAY）时加载 BGA，
  所以**练习模式天生没有 BGA 可画**。
- **autoplay 里修过一次 portrait 全屏**，见 `53b318ed`：
  `drawBGA()` 在 portrait 下用 `sprite.getViewport()` 覆盖绘制区域，
  并把 FIT_OUTER 的目标改成正方形 `max(w,h)`，保证 270° 旋转后仍覆盖整屏。
  这套逻辑**只作用于 BGA 这一层**，而 `drawBGA` 在 PRACTICE 下根本不会被调用 ——
  所以它既修不到 practice，也管不了"主画面对不齐"的问题。
  也就是说：**过去那两次修复都在 BGA / 触摸指针这一层，主画面的视口恢复一直是缺的。**
