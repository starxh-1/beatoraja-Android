# USB 键盘激活输入时误触发屏幕键盘（IME）排查

> 日期：2026-06-10
> 状态：已修复，待真机复测
> 涉及设备：Android 平板 / 手机 + OTG/USB 物理键盘
> 触发现象：插入 USB 键盘（QWERTY）并按键时，屏幕底部软键盘（IME）被错误弹出

---

## 1. 问题现象

| 场景 | 现象 |
| --- | --- |
| select / decide / play 等主界面，连接 USB 键盘按任意键 | 系统软键盘被自动弹出，遮挡游戏画面 |
| 拔掉 USB 键盘 | 软键盘不再误弹 |
| 点击搜索框 EditText | 软键盘正常弹出（预期行为） |
| 点击搜索框外部 | 软键盘正常收起（预期行为） |

只有"按物理键"的瞬间弹 IME 是 bug，搜索框主动触发弹 IME 是正常行为。

---

## 2. 当前相关代码定位

排查过程中已经定位到几处已经做了部分防护的代码，先把现状说清楚再讨论疑问。

### 2.1 `android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java`

| 位置 | 作用 |
| --- | --- |
| `createGraphics()` 内自定义 `GLSurfaceView20` | `onCheckIsTextEditor()` 返回 `false`，`onCreateInputConnection()` 中省略 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` 标志（参考 libgdx issue #7754） |
| `MainStateListener`（PLAY 状态切换时） | 状态切换时若处于非文本输入态，调用 `suppressImeForGameInput()` 清理残留焦点和 IME |
| `OnGlobalFocusChangeListener` | 兜底：任何 EditText / GLSurfaceView 在非文本输入态获得焦点时立即清焦点 + 隐藏 IME |
| `dispatchKeyEvent()` | 按下物理键时先交给 libGDX，再异步执行 `suppressImeForGameInput()` |
| `installUnhandledKeyEventGuard()` | API 28+ 在 DecorView 上兜底未处理的物理键事件 |
| `setTextInputActive(boolean)` | 由 `KeyBoardInputProcesseor.setTextInputMode()` 通过反射调用，切换整个窗口的 `SOFT_INPUT_STATE_ALWAYS_HIDDEN` 与 GLSurfaceView/EditText 的 focusable；退出文本态时再次抑制 IME |
| `applyFocusableToEditTexts()` | 同时处理 EditText 与 GLSurfaceView 节点（注释里提到 libGDX 1.14 的 `DefaultAndroidInput$4` 在 `setOnscreenKeyboardVisible(true)` 时会无条件把 GLSurfaceView 设成 `focusable=true`） |

代码里已经做了"防弹"：改 `onCheckIsTextEditor`、`onCreateInputConnection`，监听全局焦点变化，物理键按下时主动 hide IME。2026-06-10 的修复继续补上 `clearFocus()`、DecorView 未处理按键兜底、Manifest 默认 `stateAlwaysHidden` 和 `IME_FLAG_NO_FULLSCREEN`。

### 2.2 `core/src/main/java/bms/player/beatoraja/select/SearchTextField.java`

- 搜索框的 `TextField.OnscreenKeyboard` 被覆盖：只接管 `show(false)` 分支（→ `Gdx.input.setOnscreenKeyboardVisible(false)`），`show(true)` 保持 no-op，键盘显隐由 `AndroidLauncher.setTextInputActive()` 统一管理（`search.setOnscreenKeyboard()` 注释明确写了这一点）。
- 历史上这个回调签名是 `show(TextField)/close()`，**当前 libGDX 1.14.0 已合并为 `show(boolean visible)` 单方法**（这是把 libGDX 降到 1.14.0 的主要驱动之一）；回车 / 失焦关闭键盘现在统一调用 `textField.getOnscreenKeyboard().show(false)`，不再调用 `close()`。
- 搜索框 `touchDown` 时通过 `KeyBoardInputProcesseor.setTextInputMode(true)` 进入文本输入态。
- 搜索框外部 `screen.touchDown` 触发 `unfocus(selector)` 退出文本输入态。

理论上，搜索框外的物理键事件不应该走"进入文本输入态"这条路径。

### 2.3 `core/src/main/java/bms/player/beatoraja/input/KeyBoardInputProcesseor.java`

- 内部维护 `textmode` 状态，由 `setTextInputMode()` 同步给 `AndroidLauncher.setTextInputActive()`。
- `textmode=true` 时 poll 循环只处理 ControlKeys，不再走游戏键位轮询；这是 `textmode=true` 时游戏按键被屏蔽的设计。
- `keyDown()` 中有"BACK → ESCAPE"重映射。

---

## 3. 误弹 IME 的可能触发路径（假设清单）

需要按下列顺序逐个验证，找出真正漏掉的入口。

### 路径 A：Android 系统在物理键盘事件中自动弹 IME
- 触发条件：当前获得焦点的 View 是 `onCheckIsTextEditor() == true` 且 `onCreateInputConnection()` 返回非 null。
- 已经在 `createGraphics()` 里改成 `false`、并把 inputType 设为无 PASSWORD 变体。**但**：搜索框对应的 `AndroidOnscreenKeyboard`（libGDX 内置的 EditText）默认 `onCheckIsTextEditor=true`，当 `Gdx.input.setOnscreenKeyboardVisible(true)` 调用过、之后 IME 被收起、之后用户按物理键，系统有时会"贴心地"再把焦点送回这个 EditText。修复前 `AndroidLauncher.dispatchKeyEvent()` 已经在物理键按下时 `hideSoftInputFromWindow`，但**没有 `clearFocus()`**；本轮已补上。

### 路径 B：libGDX `DefaultAndroidInput` 在 `setOnscreenKeyboardVisible(true)` 路径上同步修改焦点
- 注释中明确提到 `DefaultAndroidInput$4` 会"无条件把 GLSurfaceView 设成 `focusable=true`，且关闭时不重置"。
- `applyFocusableToEditTexts()` 已经对 GLSurfaceView 一起处理了 focusable，但修复前 `setOnscreenKeyboardFocusable(false)` 仅在 PLAY 状态切换时调用一次；select / decide 界面下没有该监听器。**如果搜索框在 select 界面被隐藏后，GLSurfaceView 的 focusable 残留在 true，下一次按物理键时焦点仍可能落回它。** 本轮已在非文本输入态的窗口恢复、状态切换、物理键事件和退出文本态路径上统一调用 `suppressImeForGameInput()`。

### 路径 C：`onCreateInputConnection` 中 `outAttrs.imeOptions` 设的 `IME_FLAG_NO_EXTRACT_UI`
- 这是"不要显示提取条"，但并不阻止 IME 弹起。设置 `IME_FLAG_NO_FULLSCREEN` 加上隐藏系统栏可能更稳。
- `outAttrs.inputType` 设置为 `TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS` 也只是改键盘样式，不阻止弹出。本轮已额外加入 `IME_FLAG_NO_FULLSCREEN`。

### 路径 D：`dispatchKeyEvent` 拿不到 keyDown 事件
- 部分 ROM（MIUI / ColorOS）会把 USB 键盘的 KeyEvent 直接派发到当前焦点 Window，而不是先经过 Activity 的 `dispatchKeyEvent`。如果焦点不在 DecorView / GLSurfaceView 树里，`hideSoftInputFromWindow` 的 `token` 可能无效。
- 兜底方案：在 View 树根节点上注册 `OnUnhandledKeyEventListener`，对未处理 keyDown 也清焦点 + hide IME。本轮已实现。

### 路径 E：Activity / Window 级别的 `softInputMode`
- `onResume()` 里设置 `SOFT_INPUT_STATE_ALWAYS_HIDDEN | SOFT_INPUT_ADJUST_NOTHING`。
- 但 `setTextInputActive(true)` 时改成 `SOFT_INPUT_ADJUST_NOTHING`（不主动 SHOW），`false` 时改回 `ALWAYS_HIDDEN`。
- 如果 `setTextInputActive(true)` 调用后没成对调用 `false`，softInputMode 会一直停留在"不调整"状态，新的物理键事件可能被系统解释为"开始编辑"。本轮已在 Manifest 中给 `AndroidLauncher` 写入 `stateAlwaysHidden|adjustNothing`，作为窗口级默认值。

---

## 4. 待验证点

按性价比从高到低排：

1. **在 `dispatchKeyEvent` 中同时调用 `clearFocus()`**——已落地为事件交给 libGDX 后异步清除残留焦点，避免抢掉当前按键。
2. **给 DecorView 注册 `setOnUnhandledKeyEventListener`**，作为 `dispatchKeyEvent` 之外的第二道防线。已落地。
3. **在 select / decide 界面的 onResume 或 onShow 中也调用一次 `setOnscreenKeyboardFocusable(false)`**，把 GLSurfaceView 的 focusable 残留关掉。已在 `onResume()`、窗口重新获得焦点和状态切换兜底。
4. **在 `outAttrs` 中加 `IME_FLAG_NO_FULLSCREEN`**，至少让弹出的键盘不抢全屏。已落地。
5. **加一个更密集的 log**：在 `OnGlobalFocusChangeListener` 中把 `oldFocus` / `newFocus` 的类名 + `isTextInputActive` 一并打印，确认是哪个 View 在偷焦点。

---

## 5. 需进一步调研的疑问（Open Questions）

> 这部分是研究过程中尚未确认的"是否要换方案 / 是否要查官方说明"的问题清单。**先列出来，不在本轮排障中给出最终结论。**

### Q1. libGDX 是否需要换成 1.14.1 或更新版本？

- 资讯：<https://libgdx.com/news/2026/05/gdx-1-14-1>
- Release notes：<https://github.com/libgdx/libgdx/releases/tag/1.14.1>
- 现状：`gradle.properties` 中 `gdxVersion=1.14.0`（合并 `codex/fix-command-folder-scoredb` 时被回退，目的是与 `TextField.OnscreenKeyboard.show(boolean)` 的 1.14.x 时期签名对齐），`gdxVideoVersion=1.3.3`。**当前 1.14.0 仍 ≥ 1.14.0，不需要升到 1.14.1**；1.14.0 之后版本对本 bug 的修复点没有显著变更说明。
- 待查：
  1. 1.14.0 / 1.14.1 / 最新版在 `DefaultAndroidInput` 中对 USB 键盘 / IME 焦点处理有什么变更？（看 changelog 与 issue 列表）
  2. 是否有 issue 专门跟踪"USB 键盘按物理键时 IME 弹出"？关键词："USB keyboard focus"、"onCheckIsTextEditor"、"AndroidOnscreenKeyboard focus stealing"。
  3. `AndroidOnscreenKeyboard` 类（libGDX 内置的 EditText）在 1.14.0 中是否还有被 `setFocusable(true)` 强写的代码？本地 `Gdx.input.setOnscreenKeyboardVisible(false)` 之后是否真的能恢复 focusable=false？
- 可能的结论：当前版本对本 bug 没有"已知修复点"；**真正解药在自定义 `onCheckIsTextEditor` + 主动 `clearFocus` + 焦点兜底监听器**，与 1.14.x 系列中任何具体版本无关。

### Q2. libgdx 官方对键盘输入检测的说明

- 链接：<https://libgdx.com/wiki/input/mouse-touch-and-keyboard>
- 关键点（按页面章节抓取并对照代码）：
  - 键盘输入通过 `Gdx.input.isKeyPressed()` 轮询，或 `InputProcessor.keyDown()` / `keyUp()` 回调。
  - 软键盘通过 `Gdx.input.setOnscreenKeyboardVisible(true)` 显示；该调用同时会让 libGDX 内部把 `GLSurfaceView` 的 focusable 打开（这是问题根源之一）。
  - `OnscreenKeyboardType` 用于选择键盘类型（Default / Number / Phone 等）。
  - 文档本身没有给出"如何彻底禁用软键盘"的 API，**官方推荐做法是：要么在需要时显式 `setOnscreenKeyboardVisible(true)`，要么通过原生 Android 手段隐藏 IME**。
- 待查：
  1. 文档是否提到 `setOnscreenKeyboardVisible(false)` 之后物理键盘事件的行为？
  2. 文档对 `OnscreenKeyboardType.Default` 与 `onCreateInputConnection` 的关系有没有更明确的解释？（我们当前覆盖的就是这条路径）
- 当前代码已经与文档"显式管理 + 原生 Android 隐藏"的双轨思路一致，**没有发现文档和实现之间的偏差**。

### Q3. 关于禁用屏幕键盘的问答

- 链接：<https://stackoverflow.com/questions/46303119/how-to-disable-onscreenkeyboard-in-libgdx-android>
- 主流答案方向：
  1. **不要调用** `Gdx.input.setOnscreenKeyboardVisible(true)`，并且 **在 AndroidManifest 的 activity 上加 `android:windowSoftInputMode="stateAlwaysHidden"`**。
  2. 用原生 Android 的 `InputMethodManager.hideSoftInputFromWindow()` + 显式 `clearFocus()` 兜底。
  3. 自定义 `GLSurfaceView` 时 override `onCheckIsTextEditor()` 返回 `false`。
- 本项目做法对照：
  - `AndroidManifest.xml` 已对 `AndroidLauncher` 显式声明 `windowSoftInputMode="stateAlwaysHidden|adjustNothing"`；`onResume()` / `setTextInputActive(false)` 中仍通过 `getWindow().setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_HIDDEN | SOFT_INPUT_ADJUST_NOTHING)` 兜底（✅ 采纳答案 1）。
  - 自定义 `GLSurfaceView20` 的 `onCheckIsTextEditor()` 返回 `false`（✅ 采纳答案 3）。
  - `InputMethodManager.hideSoftInputFromWindow()` 已在多处调用（✅ 采纳答案 2）。
  - `clearFocus()` 兜底已集中到 `suppressImeForGameInput()`（✅ 采纳答案 2）。
- 待查：
  1. 该问答的高赞答案有没有提到 `View.setImportantForAutofill` / `WindowInsetsController.hide(ime())` 等更现代的 API？Android 11+ 推荐用 `WindowInsetsController` 而非 `InputMethodManager`。
  2. Android 15 在 focus stealing 上有没有新的限制？我们的 `targetSdk = 36`，可能踩到新行为。

---

## 6. 修复落点

2026-06-10 已按上面的高性价比顺序修复：

- `AndroidLauncher.dispatchKeyEvent()`：物理键事件先交给 libGDX，再 `post` 到 UI 队列执行 IME 抑制，避免清焦点影响当前键。
- `AndroidLauncher.installUnhandledKeyEventGuard()`：API 28+ 在 DecorView 上兜底未处理的物理键事件。
- `AndroidLauncher.suppressImeForGameInput()`：非文本输入态统一 `setOnscreenKeyboardFocusable(false)`、隐藏 IME、清除当前焦点。
- `AndroidLauncher.onCreateInputConnection()`：增加 `IME_FLAG_NO_FULLSCREEN`。
- `AndroidManifest.xml`：`AndroidLauncher` 声明 `android:windowSoftInputMode="stateAlwaysHidden|adjustNothing"`。

仍需真机复测：连接 USB/OTG 键盘，在 select / decide / play 连续按键不弹 IME；点击搜索框仍能正常弹出 IME，点击外部和回车后正常收起。

## 7. 临时日志补丁建议（用于后续排查）

为了更快定位路径 A–E 中具体是哪条，建议在 `AndroidLauncher` 中加入下面几行临时日志（**不要直接提交到生产**，调试完要回滚）：

```java
// dispatchKeyEvent 内
if (!isTextInputActive && event.getAction() == KeyEvent.ACTION_DOWN) {
    Log.d(TAG, "keyDown from keyboard: code=" + event.getKeyCode()
        + " hasFocus=" + hasWindowFocus()
        + " currentFocus=" + (getCurrentFocus() == null ? "null" : getCurrentFocus().getClass().getName()));
    runOnUiThread(() -> {
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        }
        View cur = getCurrentFocus();
        if (cur != null) cur.clearFocus();   // ← 顺手加的验证点 1
    });
}

// OnGlobalFocusChangeListener 内
Log.d(TAG, "focus change: old=" + (oldFocus == null ? "null" : oldFocus.getClass().getName())
    + " new=" + (newFocus == null ? "null" : newFocus.getClass().getName())
    + " isTextInputActive=" + isTextInputActive);
```

抓到日志后再判断是 `AndroidOnscreenKeyboard` EditText 在偷焦点，还是 GLSurfaceView 在偷焦点。

---

## 8. 待办（按依赖关系排）

- [x] 按当前诊断先修 `clearFocus` / 焦点兜底 / `setOnscreenKeyboardFocusable` 在非文本态调用 / Manifest 写 `stateAlwaysHidden`
- [ ] 真机复测：插 USB 键盘按几下 → 确认 select / decide / play 不弹 IME，搜索框仍可主动弹 IME
- [ ] 同步翻 `libgdx 1.14.2` 源码 `DefaultAndroidInput.java` 中 `setOnscreenKeyboardVisible` 的实现，确认是否有未文档化的副作用
- [ ] 验证 Android 15 / 16 上 `WindowInsetsController` 路径是否比 `InputMethodManager` 更可靠

---

## 9. 相关文件索引

- `android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java`
- `core/src/main/java/bms/player/beatoraja/select/SearchTextField.java`
- `core/src/main/java/bms/player/beatoraja/input/KeyBoardInputProcesseor.java`
- `android/build.gradle`、`gradle.properties`（版本：`gdxVersion=1.14.0`、`gdxVideoVersion=1.3.3`；2026-06-10 由 `codex/fix-command-folder-scoredb` 分支从 1.14.2/1.3.4 降级）
- `android/AndroidManifest.xml`
- libGDX issue #7754：<https://github.com/libgdx/libgdx/issues/7754>

---

## 10. 2026-06-10 变更与本排查的关联（`codex/fix-command-folder-scoredb` 合并后）

合并 `ed81d363` "Fix Android command folders after score DB merge" 后，与本排查相关的变化：

1. **`SearchTextField.java` 的 `OnscreenKeyboard` 回调签名变了**（2.2 节已更新）：从 `show(TextField) / close()` 二方法改为 `show(boolean visible)` 单方法。合并前 1.14.2 上的旧写法 `textField.getOnscreenKeyboard().close()` 已被 `textField.getOnscreenKeyboard().show(false)` 替代。
2. **libGDX 1.14.2 → 1.14.0 降级**：是上面的签名对齐所必需；本排查中所有关于"libGDX 1.14.x 内部行为"的描述都需要以 1.14.0 为基线（Q1 已更新）。
3. **命令文件夹 `CommandBar` / `RandomCourseData` 的 scoreDB 路径统一**（与本排查无关，列出仅为完整性）：原来传 `score.db` + `scorelog.db` 两个独立文件，现在两个参数都传 `score.db`，由 `AndroidSQLiteSongDatabaseAccessor` 内部从同一文件取 `score` 和 `scorelog` 表。

**对本排查的影响**：
- §3 路径 B 中关于"`DefaultAndroidInput$4` 在 1.14 时会强写 `focusable=true`"的注释需要复核——是 1.14.0 还是 1.14.2 行为？建议同步翻 `libgdx 1.14.0` 源码确认。
- §4 待验证点 1（`dispatchKeyEvent` 补 `clearFocus()`）和 §6 日志补丁**不受合并影响**，仍是最高性价比的下一步动作。
