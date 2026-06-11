
# QWERTY 实体键盘触发屏幕键盘 Bug 分析

## 1. 问题现象

连接 QWERTY 实体键盘后，按下键盘按键尝试操作游戏时，Android 系统自动弹出屏幕键盘（IME）。

## 2. 对比分析：Unciv vs beatoraja

两者都基于 libGDX 1.14.0，但 Unciv 无此问题。

### 2.1 Unciv 的输入架构（正常）

Unciv 的 `AndroidLauncher.kt` 非常简洁：

```kotlin
open class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... 配置初始化 ...
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = settings.androidHideSystemUi
        }
        game = AndroidGame(this)
        initialize(game, config)   // <-- 标准 libGDX 初始化，无任何自定义
        // ... 其他设置 ...
    }
    // 没有重写 dispatchKeyEvent
    // 没有重写 onCheckIsTextEditor
    // 没有重写 onCreateInputConnection
    // 没有重写 createGraphics
    // 没有任何 IME 管理代码
    // 没有任何 focus 管理代码
}
```

关键特征：
- **零干预**：完全依赖 libGDX 默认行为，不添加任何 IME/焦点管理逻辑
- **无 focus 操作**：从不主动调用 `requestFocus()` 给 GLSurfaceView
- **物理键盘事件**：通过 Android 标准事件分发链 → libGDX `DefaultAndroidInput.onKey()` 被调用
- **IME 触发**：仅当用户**显式点击文本输入框**时，游戏逻辑调用 `Gdx.input.setOnscreenKeyboardVisible(true)`
- **Manifest**：`configChanges="keyboard|keyboardHidden|orientation|screenSize"`，无 `windowSoftInputMode`

### 2.2 beatoraja 的输入架构（有 Bug）

当前 `AndroidLauncher.java` 存在大量自定义 IME/焦点管理代码：

```java
// 重写了 createGraphics，自定义 GLSurfaceView20
@Override
protected AndroidGraphics createGraphics(AndroidApplicationConfiguration config) { ... }

// 重写了 dispatchKeyEvent
@Override
public boolean dispatchKeyEvent(KeyEvent event) {
    if (isHardwareKeyboardEvent(event) && !isTextInputActive) {
        prepareHardwareKeyboardTarget();  // ⚠️ 根因之一
    }
    boolean handled = super.dispatchKeyEvent(event);
    if (shouldSuppressImeForKeyEvent(event)) {
        postSuppressImeForGameInput();    // ⚠️ 治标不治本
    }
    return handled;
}

// 安装了 UnhandledKeyEvent 守卫
installUnhandledKeyEventGuard();          // ⚠️ 额外的兜底，说明根本没防住

// 重写了 onWindowFocusChanged
@Override
public void onWindowFocusChanged(boolean hasFocus) {
    if (hasFocus && !isTextInputActive) suppressImeForGameInput();
}

// 重写了 onResume
@Override
protected void onResume() {
    // 设置 stateAlwaysHidden + suppressImeForGameInput
}
```

## 3. 根因分析

### 根因 1（主要）：`prepareHardwareKeyboardTarget()` → `focusGameSurfaceView()` → `surfaceView.requestFocus()`

```java
// AndroidLauncher.java:945-947
if (isHardwareKeyboardEvent(event) && !isTextInputActive) {
    prepareHardwareKeyboardTarget();
}

// AndroidLauncher.java:1028-1034
private void prepareHardwareKeyboardTarget() {
    if (isTextInputActive) return;
    setOnscreenKeyboardFocusable(false);
    View currentFocus = getCurrentFocus();
    if (currentFocus instanceof EditText) currentFocus.clearFocus();
    focusGameSurfaceView();  // <-- 主动让 SurfaceView 获取焦点
}

// AndroidLauncher.java:1036-1048
private void focusGameSurfaceView() {
    View surfaceView = findSurfaceView(getWindow().getDecorView());
    if (surfaceView == null) return;
    surfaceView.setFocusable(true);
    surfaceView.setFocusableInTouchMode(true);
    if (!surfaceView.hasFocus()) {
        surfaceView.requestFocus();  // <-- 此处触发焦点变更
    }
}
```

**触发链路**：
1. 物理键盘按键 → `dispatchKeyEvent()` 被调用
2. `prepareHardwareKeyboardTarget()` 执行 → `surfaceView.requestFocus()`
3. GLSurfaceView20 获得焦点
4. Android InputMethodManager 检测到：具有焦点的 View 提供了 `InputConnection`（来自 `onCreateInputConnection`）
5. 系统启动 IME 连接流程 → 屏幕键盘弹出

**为什么 `onCheckIsTextEditor()=false` 没有阻止？**

虽然 GLSurfaceView20 重写了 `onCheckIsTextEditor()` 返回 `false`，但：
- `onCheckIsTextEditor()` 影响的是 `InputMethodManager.showSoftInput()` 的检查逻辑
- 物理键盘事件处理中，Android 框架在 `ViewRootImpl.processKeyEvent()` 路径上还有其他触发 IME 的代码路径
- 在某些 ROM（尤其国产 ROM）上，焦点变更到有 `InputConnection` 的 View 时，会触发额外的 IME 显示逻辑

### 根因 2：Suppress 时序滞后

```java
// AndroidLauncher.java:951-953
if (shouldSuppressImeForKeyEvent(event)) {
    postSuppressImeForGameInput();  // 使用 post() 延迟到下一帧
}
```

`postSuppressImeForGameInput()` 通过 `view.post()` 延迟执行 `hideSoftInputFromWindow()`。此时 IME 的 show 动画已经在 InputMethodManager 的 message queue 中排队。时序上形成 **show → hide 竞争**，在某些设备上 IME 会闪现甚至持续显示。

### 根因 3：过度防御导致的自我伤害

整体架构困境：
- 为了**搜索框等文本输入场景**能正常工作，GLSurfaceView20 必须有 `onCreateInputConnection`
- 为了**防止截图/录屏被阻止**，不能设置 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
- 为了**防止 IME 弹出**，加了 `onCheckIsTextEditor()=false`
- 但 `focusGameSurfaceView()` 又主动给 GLSurfaceView 焦点

这形成了一个矛盾：GLSurfaceView 既有 `InputConnection`（为了文本输入）又被主动聚焦（为了键盘事件）→ Android 系统认为该显示 IME。

## 4. 为什么 Unciv 不需要这些代码

libGDX 的 `DefaultAndroidInput` 通过以下方式接收物理键盘事件：
1. **通过 View 层级的 KeyListener**：`DefaultAndroidInput` 注册为 GLSurfaceView 的 `OnKeyListener`
2. **OnKeyListener 不依赖 View 焦点**：只要 View 可见且有 KeyListener，就能收到事件
3. **Activity.dispatchKeyEvent → super.dispatchKeyEvent → View.dispatchKeyEvent → OnKeyListener.onKey**

因此，GLSurfaceView 根本不需要焦点就能接收物理键盘输入。libGDX 已经处理好了这一切。

## 5. 修复方案

### 方案：移除焦点管理代码，简化 `dispatchKeyEvent`

**原则**：像 Unciv 一样，不干预 Android 的焦点/IME 管理。物理键盘事件走标准 Android + libGDX 路径。

#### 具体改动

**1. 简化 `dispatchKeyEvent()`**（AndroidLauncher.java）

```java
@Override
public boolean dispatchKeyEvent(KeyEvent event) {
    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
        lastUserTouchTime = SystemClock.uptimeMillis();
        if (instance != null) {
            setAndroidBackPressedFlag();
            return true;
        }
    }
    // 移除 prepareHardwareKeyboardTarget() 调用
    // 移除 postSuppressImeForGameInput() 调用
    // 直接走标准 libGDX 事件分发
    return super.dispatchKeyEvent(event);
}
```

**2. 删除以下不再需要的方法**（AndroidLauncher.java）
- `installUnhandledKeyEventGuard()` 及其调用
- `shouldSuppressImeForKeyEvent()`
- `isHardwareKeyboardEvent()`
- `postSuppressImeForGameInput()`
- `prepareHardwareKeyboardTarget()`
- `focusGameSurfaceView()`
- `isGameSurfaceView()`

**3. 保留但简化**
- 保留 `onCheckIsTextEditor()` 返回 `false`（防御性，无害）
- 保留 `onCreateInputConnection` 自定义（文本输入仍需要）
- 保留 `setTextInputActive()` 中的 IME 模式切换
- 保留 `suppressImeForGameInput()`，但仅由 `setTextInputActive(false)` 和 `onResume()` 调用
- 保留 Manifest 中的 `windowSoftInputMode="stateAlwaysHidden|adjustNothing"`

**4. 移除 `focusChangeListener` 的注册**
- `OnGlobalFocusChangeListener` 中的 `suppressImeForGameInput()` 回调现在也不需要了

### 为什么这个方案安全

| 场景 | 修复后行为 |
|------|-----------|
| 物理键盘按键操作游戏 | GLSurfaceView 无需焦点，通过 OnKeyListener 接收事件，IME 不弹 |
| 点击搜索框输入文字 | `setTextInputActive(true)` → 启用 IME，正常弹出 |
| 关闭搜索框 | `setTextInputActive(false)` → `suppressImeForGameInput()`，IME 关闭 |
| 从后台恢复 | `onResume()` → `suppressImeForGameInput()` 确保 IME 隐藏 |
| 截图/录屏 | `onCreateInputConnection` 不设 PASSWORD flag，正常可用 |

## 6. 总结

**核心教训**：libGDX 已经正确实现了 Android 物理键盘输入。不需要、也不应该在 Activity 层添加焦点管理和 IME 抑制逻辑。多余的干预反而破坏了 Android 输入系统的正常行为。

Unciv 证明了"什么都不做"就是最好的做法。
