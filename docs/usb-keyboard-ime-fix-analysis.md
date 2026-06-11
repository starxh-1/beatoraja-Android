# USB 键盘硬件键误弹 IME 排查与修复(2026-06)

> 状态:已落地,待真机复测
> 设备:小米/红米 (MIUI + Sogou 搜狗输入法) + OTG/USB 物理键盘
> 涉及项目:beatoraja-Android(参考项目:Unciv,同样 libGDX 1.14.0)
> 关键文件:`android/AndroidManifest.xml`、`android/src/main/java/.../AndroidLauncher.java`、`core/src/main/java/.../select/SearchTextField.java`

---

## 1. 问题

| 现象 | 描述 |
| --- | --- |
| 触发 | 插入 USB 键盘,在 select / decide / play 等非搜索框界面按任意键 |
| 结果 | 屏幕底部 IME 被误弹出,遮挡游戏画面;部分情况下 libGDX 收不到硬件键 |
| 对比 | Unciv(同 libGDX 1.14.0)在同样场景下,logcat 完全没有 `show(ime(), fromIme=true)` 事件 |

---

## 2. 调查方法

### 2.1 抓 log 对比

抓 beatoraja-Android 在 select 界面按一次 USB 键的 logcat:

```
22:36:42.616 InsetsController   show(ime(), fromIme=true)        ← Sogou 自己请求
22:36:42.617 ViewRootImplStubImpl requestedTypes: 8              ← animation start
22:36:42.620 ViewRootImplStubImpl onAnimationStart
22:36:42.621-43.062 ViewRootImplStubImpl onAnimationUpdate 0.0→1.0   ← 完整 show 动画
22:36:43.063 ViewRootImplStubImpl onAnimationEnd, canceled: false
22:36:43.066 ImeTracker         com.sohu.inputmethod.sogou.xiaomi: onShown
```

抓 Unciv 同样场景的 logcat(已通过对比验证):

```
17.995 InsetsController   hide: statusBars          ← useImmersiveMode
17.995 requestedTypes: 1                            ← statusBars
18.023 InsetsController   hide(ime(), fromIme=false)
18.023 ImeTracker         onCancelled at PHASE_CLIENT_ALREADY_HIDDEN
... 之后无任何 IME show 事件
```

**核心差异**:beatoraja-Android 的 Sogou 走 `fromIme=true` 主动 show 路径,Unciv 完全不触发。

### 2.2 对比两份 manifest

| 项目 | `windowSoftInputMode` |
| --- | --- |
| beatoraja-Android | `stateAlwaysHidden\|adjustNothing` |
| Unciv | (不设,系统默认) |

### 2.3 对比 Android 启动代码

| 维度 | Unciv | beatoraja-Android(修复前) |
| --- | --- | --- |
| `createGraphics()` override | 无 | 自定义 `GLSurfaceView20` 子类,重写 `onCheckIsTextEditor()` 返回 `false`、重写 `onCreateInputConnection()` |
| `dispatchKeyEvent()` override | 无 | 多次重写,加 IME 抑制分支 |
| `OnGlobalFocusChangeListener` | 无 | 有,焦点变化时清焦点 + hide IME |
| `installUnhandledKeyEventGuard()` | 无 | 有,API 28+ 兜底 |
| `setTextInputActive()` | 无反射调用 | core 通过反射调用,内部改 `softInputMode` + 焦点 |
| IME 抑制代码总量 | 0 行 | 约 160 行 |

### 2.4 对比 TextField 输入处理

**Unciv** (`Unciv/.../TextFieldWithFixes.kt:35-46`):

```kotlin
init {
    onscreenKeyboard = OnscreenKeyboard { visible ->
        Gdx.input.setOnscreenKeyboardVisible(visible, ...)
    }
    addListener(object : FocusListener() {
        override fun keyboardFocusChanged(event: FocusEvent, actor: Actor?, focused: Boolean) {
            if (!focused && event.relatedActor is TextFieldWithFixes) return
            onscreenKeyboard.show(focused)
        }
    })
}
```

启动后无 TextField 焦点 → 不调 `setOnscreenKeyboardVisible(true)` → IMM 无绑定 → Sogou 无目标可弹。

**beatoraja-Android** (`SearchTextField.java:128-138`,修复前):

```java
// 覆盖 OnscreenKeyboard,防止 libGDX 的 DefaultAndroidInput 独立控制 IME。
search.setOnscreenKeyboard(new TextField.OnscreenKeyboard() {
    @Override
    public void show(boolean visible) {
        if (!visible) {
            Gdx.input.setOnscreenKeyboardVisible(false);
        }
    }
});
```

**吞掉 `show(true)`** → IMM 永远不绑 → 也不调 `setOnscreenKeyboardVisible(true)`。

但这留下了"曾经绑定过"的历史状态(可能源于旧版本),IMM 的 `fromIme=true` 请求是 IME 进程基于全局状态发起,跟 app 端有没有 View 拿到焦点无关。

---

## 3. 根因分析

### 3.1 真正的触发源:搜狗输入法 "硬件键自动弹"

- logcat `fromIme=true` 是**输入法进程**主动发起的 show 请求,**不是 app**。
- 触发逻辑:搜狗输入法监听 `InputManagerService` 的硬件键事件,有自己的"硬件键到来是否弹起"启发式。
- 该启发式与以下**都无关**:
  - `windowSoftInputMode`(`stateAlwaysHidden` 反而是"app 在主动控制 IME"的强信号,Sogou 看到后更愿意弹)
  - `onCheckIsTextEditor()` 返回值
  - GLSurfaceView 是否 focusable
  - IMM 是否有绑定
- **应用层没有 API 可以关闭这个 Sogou 行为**(关掉只能去 Sogou 设置里手动关"智能外设输入法")。

### 3.2 之前那些抑制代码为什么没解决问题

| 抑制点 | 失败原因 |
| --- | --- |
| `setOnscreenKeyboardFocusable(false)` | libGDX 1.14.0 没有 `AndroidOnscreenKeyboard` 类,`setOnscreenKeyboardFocusable` 在搜索框外的界面对 Sogou 无效 |
| `OnGlobalFocusChangeListener` 清焦点 | 焦点本身就在 GLSurfaceView,没偷走;Sogou 不通过焦点发请求 |
| `dispatchKeyEvent` 里 hide | 同步 hide 在 `super.dispatchKeyEvent` 之后,已经晚于 Sogou 的 show 动画启动 |
| `WindowInsetsAnimation.Callback.onStart` | 动画已经开始(进度 ~0.0),framework cancel 时已经能看到 ~12ms 闪烁 |
| `WindowInsetsAnimation.Callback.onPrepare` + `onStart` + `onProgress` 多重 hide | 在动画线程里反复塞 hide 请求,触发 `Can't change insets on an animation that is cancelled` 错误,反过来阻塞了 key event → "按键卡死" |

### 3.3 Unciv 真正"没踩坑"的原因(不是显式解决)

- Unciv 从未主动调过 `setOnscreenKeyboardVisible(true)`,IMM 一直无绑定。
- Sogou 监听硬件键后发起 show 请求,但因为 Unciv 没有任何 View 处于 text-editor 状态,framework 端走"无目标"路径,**没有真正进入 onAnimationStart 循环**。所以 log 干净。
- beatoraja-Android 历史上有过 `setOnscreenKeyboardVisible(true)` 调用(搜索框用过),即使被我们之前那版 SearchTextField 吞掉,IMM 服务端仍可能缓存了"这个 app 的窗口曾经是 IME 目标"的记忆 → Sogou 发起 show 时走"有目标"路径,framework 真正起动画。

---

## 4. 修复策略

**对齐 Unciv 的"什么都不做"模式**,但**保留一条 `onPrepare` 兜底**(用于 Sogou 弹起的最后一帧拦截)。

### 4.1 第一步(已做,有效):manifest `stateUnspecified`

```xml
<!-- android/AndroidManifest.xml:48 -->
<!-- 改前: -->
android:windowSoftInputMode="stateAlwaysHidden|adjustNothing"
<!-- 改后: -->
android:windowSoftInputMode="stateUnspecified|adjustNothing"
```

**效果**:`stateAlwaysHidden` 删掉后,系统不再把"app 主动控制 IME"信号发给 Sogou,降低 Sogou 误弹概率;`adjustNothing` 保留(不让 IME 推挤游戏画面)。

### 4.2 第二步(已否决):还原 libGDX 默认 GLSurfaceView

如果删 `createGraphics()` 的 `onCheckIsTextEditor()=false` / `onCreateInputConnection` 重写,libGDX 默认会设 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` 标志,本可以借"密码字段不弹 IME"保护。

**否决原因**:password flag 也会让 Android 拒绝屏幕录制和截图(参考 [libgdx/issues/7754](https://github.com/libgdx/libgdx/issues/7754))。且这个保护**只在 Android 新系统上有效**,对老系统不可靠。

### 4.3 第三步(已做,有效):清掉所有 IME 抑制代码

`AndroidLauncher.java` 中删除:
- `OnGlobalFocusChangeListener focusChangeListener` 字段及其注册
- `installUnhandledKeyEventGuard()` / `shouldSuppressImeForKeyEvent()` / `postSuppressImeForGameInput()` / `suppressImeForGameInput()` / `prepareHardwareKeyboardTarget()` / `focusGameSurfaceView()` / `isGameSurfaceView()` / `hideImeFromWindow()` / `setOnscreenKeyboardFocusable()` / `applyFocusableToEditTexts()` / `isHardwareKeyboardEvent()` 全部方法
- `onWindowFocusChanged()` 的 IME 抑制分支
- `onResume()` 的 `setSoftInputMode` 调用与 `suppressImeForGameInput` 调用
- `dispatchKeyEvent()` 的 IME 分支
- `setTextInputActive()` 中的 `softInputMode` / `setOnscreenKeyboardFocusable` / `postSuppressImeForGameInput` 调用(简化为只翻 `isTextInputActive` + keep-alive)
- 字段 `isClearingFocus`(原 listener 唯一使用者)
- 4 个无用 import:`KeyCharacterMap`、`ViewTreeObserver`、`EditText`

**效果**:`AndroidLauncher` 缩短 163 行,只保留 core 反射调用的 `setTextInputActive(boolean)`(只翻 `isTextInputActive` + keep-alive,不改 softInputMode、不改焦点)。

### 4.4 第四步(已做,有效):`SearchTextField` 改用 Unciv 模式

```java
// core/src/main/java/bms/player/beatoraja/select/SearchTextField.java
// 改前(吞掉 show(true)):
search.setOnscreenKeyboard(new TextField.OnscreenKeyboard() {
    @Override
    public void show(boolean visible) {
        if (!visible) {
            Gdx.input.setOnscreenKeyboardVisible(false);
        }
    }
});

// 改后(等价 Unciv TextFieldWithFixes):
search.setOnscreenKeyboard(new TextField.OnscreenKeyboard() {
    @Override
    public void show(boolean visible) {
        Gdx.input.setOnscreenKeyboardVisible(visible);
    }
});
```

**效果**:
- 搜索框 focus → `show(true)` → `setOnscreenKeyboardVisible(true)` → 弹 IME(预期)
- 搜索框 blur / Enter / 外部点击 → `show(false)` → `setOnscreenKeyboardVisible(false)` → 收 IME
- 启动后无 TextField 焦点 → 不调 `setOnscreenKeyboardVisible(true)` → IMM 无绑定(对齐 Unciv)

### 4.5 第五步(已做,有效):启动时主动清残留绑定

```java
// AndroidLauncher.java,onCreate 内
Gdx.input.setOnscreenKeyboardVisible(false);
if (inputMethodManager != null) {
    inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
}
```

**效果**:`initialize()` 之后立刻把 libGDX 在构造期可能短暂建立的 IME 状态清掉,把 IMM 对本窗口的"曾经是 IME 目标"记忆擦掉。

### 4.6 第六步(本次新增):`onPrepare` 拦截 Sogou 误弹

```java
private void installImeShowGuard() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
    try {
        getWindow().getDecorView().setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(
                        WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override
                    public void onPrepare(WindowInsetsAnimation animation) {
                        try {
                            if (!isTextInputActive
                                    && (animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                                WindowInsetsController ctrl = getWindow().getInsetsController();
                                if (ctrl != null) {
                                    ctrl.hide(WindowInsets.Type.ime());
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
    } catch (Throwable t) {
        Log.w(TAG, "installImeShowGuard fail: " + t.getMessage());
    }
}
```

**为什么用 `onPrepare` 而不用 `onStart`/`onProgress`**:
- `onPrepare` 在动画**尚未启动**前就触发,framework 收到 `hide(ime())` 后直接取消 show 动画,**不进 0→1 循环**。
- `onStart` 是动画已经启动后调用,会有 ~12ms 闪烁。
- `onProgress` 在动画线程里反复塞 hide 会触发 `Can't change insets on an animation that is cancelled` 错误,反过来阻塞 key event → "按键卡死"。
- `DISPATCH_MODE_CONTINUE_ON_SUBTREE` 不影响其他 callback,搜索框真正要弹 IME 时不会被打断(`isTextInputActive=true` 时本回调不动作)。

**与之前 `installImeShowGuard` 的区别**:
- 旧版:`onStart` + `onProgress` 双重拦截 + `suppressImeForGameInput()` 调用 → 12ms 闪 + 按键卡死
- 新版:只 `onPrepare` + 单次 `hide(ime())` → 无闪,无 key event 阻塞

---

## 5. 最终改动汇总

| 文件 | 改动 |
| --- | --- |
| `android/AndroidManifest.xml` | `stateAlwaysHidden` → `stateUnspecified`(单字符) |
| `AndroidLauncher.java` | -163 行抑制代码;`setTextInputActive` 简化为只翻 `isTextInputActive`;`onCreate` 加 `setOnscreenKeyboardVisible(false)` + IMM hide + `installImeShowGuard()`;新增 `installImeShowGuard()` 方法,只走 `onPrepare` |
| `SearchTextField.java` | OnscreenKeyboard 改为显式转发到 `Gdx.input.setOnscreenKeyboardVisible(visible)`(等价 Unciv 的 TextFieldWithFixes) |

`createGraphics()` 完整保留(password flag 修复不被破坏,录屏/截图继续可用)。

---

## 6. 真机复测 checklist

- [ ] 插 USB 键盘按 W/S/X/D → logcat 还会出现 `show(ime(), fromIme=true)` 吗?
- [ ] 出现的话,后续还有 `onAnimationUpdate 0.0→1.0` 完整播放吗?还是直接 `onCancelled` 砍掉?
- [ ] 重点:**按键卡死**是否复现(不按就亮)?
- [ ] 方向键 / Enter / Esc 游戏键位是否都正常?
- [ ] 点搜索框 → IME 还能正常弹起?
- [ ] 搜索框回车 → IME 收尾正常?
- [ ] 搜索框外部点击 → IME 收尾正常?
- [ ] 多按几次不同键 → 没有累积的延迟/卡顿?

---

## 7. 仍未完全解决的"应用层无解"问题

Sogou 搜狗输入法的"硬件键到来自动弹 IME"行为是**输入法进程内的策略**,应用层无法关闭。Sogou 设置里可以关闭"智能外设输入法"或"按键时显示输入面板",但那需要用户手动去设置。

如果 `onPrepare` 拦截仍不能 100% 消除 Sogou 的弹起,可以考虑:
1. **降低 Sogou 优先级**:在系统设置里切换到 Gboard / Latin IME(无此行为)
2. **提示用户在 Sogou 设置里关掉"按键时显示输入面板"**:在 `SettingsActivity` 加一个引导文案
3. **接受残留弹起**:搜索框外用 12ms 闪一下,然后被 `onPrepare` 立即收回——视觉上几乎不可见

---

## 8. 排查过程中接触到的"看起来相关但其实无关"的点

- `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` flag(libGDX 默认):在 beatoraja-Android 是**主动去掉的**(issue #7754 修复),保住了录屏。如果保留,确实有"密码字段不弹 IME"的副作用,但**录屏/截图会被禁止**,不能为这个副作用丢掉录屏。
- `onCheckIsTextEditor()=false`:之前是"防止 IME 误弹"的尝试,实际上是反向作用——让 framework / Sogou 看不到这是个 text editor,反而更愿意弹。
- `Gdx.input.setOnscreenKeyboardVisible(false)` 在启动期调:能清 IMM 状态,但对 Sogou 后续的 `fromIme=true` 请求无效(Sogou 是基于全局状态判断,不是基于当前 IMM 绑定)。
- 焦点管理(`View.requestFocus()` / `clearFocus()`):GLSurfaceView 始终有焦点,EditText 不会偷偷抢走——这一点不是 Sogou 弹起的根因。

---

## 9. 关键 log 索引

| log | 含义 |
| --- | --- |
| `InsetsController show(ime(), fromIme=true)` | Sogou IME 进程主动发起的弹起请求 |
| `InsetsController hide(ime(), fromIme=false)` | 应用层(我们)发起的收起请求 |
| `InsetsController hide(ime(), fromIme=true)` | Sogou 自己发起的收起(可能因为动画被取消) |
| `ViewRootImplStubImpl requestedTypes: 8` | `WindowInsets.Type.ime()` = 8,动画类型 |
| `ViewRootImplStubImpl onAnimationStart` | 动画真正开始(此条出现说明已经从 onPrepare 走到 onStart,撤回时机稍晚) |
| `ViewRootImplStubImpl onAnimationUpdate 0.0→1.0` | 动画进度,0=完全隐藏,1=完全显示 |
| `ImeTracker onCancelled at PHASE_CLIENT_ANIMATION_CANCEL` | 动画被中途取消(说明我们的拦截生效) |
| `ImeTracker onCancelled at PHASE_CLIENT_APPLY_ANIMATION` | 动画在 apply 阶段被取消(更早) |
| `ImeTracker onShown` | Sogou IME 完全显示(最不希望看到的) |
| `ImeTracker onHidden` | Sogou IME 完全隐藏(目标状态) |
| `Can't change insets on an animation that is cancelled` | framework 拒绝在已取消的动画上再塞请求(过度拦截的副作用) |

---

## 10. 相关文件索引

- `android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java`
- `core/src/main/java/bms/player/beatoraja/select/SearchTextField.java`
- `core/src/main/java/bms/player/beatoraja/input/KeyBoardInputProcesseor.java`(反射调 `setTextInputActive`)
- `android/AndroidManifest.xml`
- `Unciv/android/src/com/unciv/app/AndroidLauncher.kt`(参考实现)
- `Unciv/core/src/com/unciv/ui/components/widgets/TextFieldWithFixes.kt`(参考实现)
- libgdx issue #7754:https://github.com/libgdx/libgdx/issues/7754
- Android `WindowInsetsAnimation` API:https://developer.android.com/reference/android/view/WindowInsetsAnimation.Callback
