# `assets/walkure/index.html` 重构规格

## 背景与需求

用户(2026-07-17)提出 4 点:

1. **starrating 不可用** — 不清楚原因,需要排查修复。
2. **walkure-offline 是上游** — 包括计算公式和一部分运作代码,本项目应尽量对齐。
3. **歌曲为 0 时显示 0.00** — 即 `observationCount === 0` 时,前端始终显示 `0.00`,即便后端 fallback 算出别的非零值。
4. **starrating 支持负数显示** — 当玩家实力低于模型最低映射点(`theta < 1` 等),显示 `-X.XX` 形式。

## 现状盘点

### 文件位置

实际生效的 HTML 是 `android/assets/walkure/index.html`(由 `android/build.gradle` 的 `assets.setSrcDirs(['assets'])` 引入)。
`android/src/main/assets/walkure/index.html` 是历史遗留的简化版,不会被打包。

### 当前 star rating 显示链路

- `AndroidLauncher.showRatingWebView(jsonData)` 在 `onPageFinished` 中调用 `RatingApp.showRating(<jsonData>)`。
- `RatingApp.showRating()` 解析后调用 `render()`。
- `render()` 调用 `this.updateStarSlot(d.playerStarRating)` 渲染老虎机动画。
- `PlayerRatingService.computeRating` 输出 JSON 顶层 `playerStarRating`(double)、`theta`、`observationCount` 等。

### 已知的"不可用"来源(老虎机实现层)

走读 `android/assets/walkure/index.html:844-974` 老虎机代码,有这些隐患:

| # | 问题 | 后果 |
|---|------|------|
| 1 | `updateStarSlot(v)` 在 `v == null` 时直接返回,只写 `?`,**但 `RatingApp.render()` 即使 `observationCount === 0` 也会调用 `updateStarSlot(0)`,此时老虎机跑一遍没意义的动画**。 | 数字闪烁/看到"0.00"出现又消失。 |
| 2 | `_formatMatch` 把前次值格式化对齐到 refStr 位数,但**初始 prev = 0.0**(Java 静态字段默认值)与新算出的非零值(或 0)对位时,若 refStr 是 `-2.10` 这种带符号的,长度对齐后会丢失符号位置感。 | 负号位置错乱或被吃。 |
| 3 | `_setSlotPos` 假设 `fromPos` 的列数等于 `str` 的数字位数,但 `prevStr = "0.00"` 和 `refStr = "-2.10"` 列数都是 3,只有符号列从无到有 — 老虎机 HTML 中**只有数字列被生成 slot-col,符号 `-` 是静态 `.slot-dot`**。`_setSlotPos` 跳过 `.` 和 `-`,所以索引对得上,但当 prev 是 `0.00` 而 ref 是 `-2.10` 时,从 prev 切到 ref 的动画里没有符号列,只能靠后续 DOM 不再追加。 | 视觉跳变/不显示负号。 |
| 4 | `requestAnimationFrame` 嵌套两次的目的是等 CSS layout 稳定 — 但老虎机的 `.strip` 用了 `position: absolute`,layout 不依赖兄弟元素,在 Android WebView 里这套 timing 容易让首帧出现"错位闪过"(`-20em` 瞬间可见)。 | 短时间空白或数字错位。 |
| 5 | `Math.round(... * 100) / 100.0` 在 Java 端会输出 `0.0`、`12.34`、`-2.1` 等形式(JSON 字符串里末尾 0 可能被丢),前端拿到后 `v.toFixed(2)` 又会补回来,**单看一帧好像没事,但老虎机的 refStr 长度变化 → `_SLOT_START*10+t` 偏移逻辑基于固定列数**,所以即便补 0 也没问题。 | OK,这一项其实是误报。 |
| 6 | `RatingBridge.saveStarRating` 与 `getPrevStarRating` 走 Android 静态字段 — 多次开关 WebView 会"记住"上一次数字,这是设计但若前次显示错误,本次也会从错误出发。 | 状态污染。 |

**根因**:老虎机这套为视觉效果做了太多事,而 walkure-offline 上游就是一行 `★12.34` 静态文本。Android 端引入老虎机时已经把"显示 + 动画 + 状态持久化"耦合在一起,排查时无法一眼看穿流程。

### walkure-offline 上游的显示形态

`walkure-offline/src/ui/js/player-report-formatters.js`:

```js
function formatStarRating(starRating) {
  return starRating == null ? '' : `★${starRating.toFixed(2)}`;
}
function formatSkillRatingSummary(skillRating, lastModified) {
  return `あなたの実力: ★${skillRating.toFixed(2)} (更新: ${formatLastModifiedDateTime(lastModified)})`;
}
```

对应的 HTML 是 `<p id="skill-rating">あなたの実力:</p>`,后端把 `<span>` 文本写入。

## 重构方案

### 核心决策

**丢弃老虎机,改用 walkure-offline 风格的静态文本显示**,这同时解决需求 1(原本的复杂显示组件是"不可用"的源头之一)、需求 3(0 歌曲分支只需在渲染前判一次)、需求 4(`toFixed(2)` 自然处理负号)。

保留部分:
- 三个 tab(Recommend / Reverse / Chart List)
- 多语言(ja/en/zh/ko)
- 分类筛选 checkbox + All/None
- 推荐 / 逆推荐 / Chart List 表格
- 分页

### 数据契约(由 `PlayerRatingService` 已经给出的)

```jsonc
{
  "playerName": "string",
  "playerStarRating": 12.34,   // double; 后端会做 clamp [0, 25]
  "theta": 1.2345,
  "observationCount": 42,
  "recommendation": [...],
  "reverseRecommendation": [...],
  "chartEntries": [...]
}
```

**新增前端约定**:
- 当前端拿到 `observationCount === 0` 时,无视 `playerStarRating`,显示 `★0.00`。
- `playerStarRating` 为 `null` / `undefined` 时,显示 `★?`(未知)。

### 显示组件

把 `updateStarSlot(v)` 换成 `renderStarRating()`:

```js
function renderStarRating() {
  var d = this.data;
  var el = document.getElementById('star-rating');
  if (!el) return;
  var value;
  if (!d || d.observationCount === 0) {
    value = 0;                       // 需求 3: 0 歌曲强制 0.00
  } else if (d.playerStarRating == null) {
    el.textContent = '★?';
    return;
  } else {
    value = d.playerStarRating;
  }
  var sign = value < 0 ? '-' : '';
  el.textContent = '★' + sign + Math.abs(value).toFixed(2);
}
```

CSS 从老虎机的 slot-row / slot-col / slot-dot 一长串,简化成:

```css
.star-rating {
  font-size: 34px;
  font-weight: bold;
  color: #ffe082;
  font-variant-numeric: tabular-nums;
}
```

### 删除项

- `updateStarSlot`、`_SLOT_COPIES`、`_SLOT_START`、`_SLOT_SPINS`、`_formatMatch`、`_setSlotPos`
- `@keyframes slotScroll` 及其相关 class(`.slot-row`, `.slot-col`, `.slot-col .strip`, `.slot-dot`, `.scrambling`)
- `mousedown / touchstart / mouseleave` 等长按打乱事件监听
- `RatingBridge.saveStarRating / getPrevStarRating` Java 字段(可保留,不删也行,只是失去调用方)

### PlayerRatingService 是否需要改?

不需要。`playerStarRating` 后端字段保持原样(返回真实计算值)。
需求 3"假显示即可"= 前端在 0 歌曲时覆盖显示,与后端解耦 — 后续若模型或算法变更,后端继续算真实值,前端规则不变。

### 验证清单

- [ ] `observationCount === 0` → 显示 `★0.00`
- [ ] `observationCount > 0`,`playerStarRating = 12.34` → 显示 `★12.34`
- [ ] `observationCount > 0`,`playerStarRating = -2.10` → 显示 `★-2.10`(toFixed 自动处理符号,但要用 Math.abs + 显式符号,避免 `-0.00` 这种边界)
- [ ] `observationCount > 0`,`playerStarRating = 0.0` → 显示 `★0.00`
- [ ] `playerStarRating = null` → 显示 `★?`
- [ ] 切换语言后标题 / 副标题 / tab / 表头同步刷新
- [ ] Recommend / Reverse / Chart List 三个 tab 仍可正常切换、筛选、翻页

## 实施步骤

1. 删除老虎机相关 CSS / JS(预计减少约 130 行)。
2. 在 `render()` 顶部把 `updateStarSlot` 换成 `renderStarRating`。
3. 把首页 star 显示的容器从 `<span id="star-slot">` 改成 `<div id="star-rating" class="star-rating">`,删除 `<span style="font-size:34px;color:#ffe082">★</span>` 这个静态前缀。
4. 抽离 `formatStarRating` 复用模块(供推荐表格"難度"列复用显示)。
5. 在 Chrome / Android WebView 各跑一遍三种取值(正常 / 0 歌曲 / 负数)。