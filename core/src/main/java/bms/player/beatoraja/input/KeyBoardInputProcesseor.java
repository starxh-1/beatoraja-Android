package bms.player.beatoraja.input;

import java.util.Arrays;

import bms.player.beatoraja.PlayModeConfig.KeyboardConfig;
import bms.player.beatoraja.Resolution;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.Input.Keys;

/**
 * キーボード入力処理用クラス
 *
 * @author exch
 */
public class KeyBoardInputProcesseor extends BMSPlayerInputDevice implements InputProcessor {

	public static final int MASK_SHIFT = 1 << 0;
	public static final int MASK_CTRL = 1 << 1;
	public static final int MASK_ALT = 1 << 2;

	private int[] keys = new int[] { Keys.Z, Keys.S, Keys.X, Keys.D, Keys.C, Keys.F, Keys.V, Keys.SHIFT_LEFT,
			Keys.CONTROL_LEFT, Keys.COMMA, Keys.L, Keys.PERIOD, Keys.SEMICOLON, Keys.SLASH, Keys.APOSTROPHE,
			Keys.BACKSLASH, Keys.SHIFT_RIGHT, Keys.CONTROL_RIGHT };
	private int[] control = new int[] { Keys.Q, Keys.W };

	private MouseScratchInput mouseScratchInput;

	private final IntArray reserved;
	/**
	 * 最後に押されたキー
	 */
	private int lastPressedKey = -1;

	private boolean textmode = false;

	private int lastTouchY = 0;
	private long lastTouchTime = 0;

	/**
	 * 画面の解像度。マウスの入力イベント処理で使用
	 */
	private Resolution resolution;

	/**
	 * 各キーのon/off状態
	 */
	private final boolean[] keystate = new boolean[256];
	/**
	 * 各キーの状態変化時間
	 */
	private final long[] keytime = new long[256];
	/**
	 * 各キーが最後に押されたときで押されてる修飾キー
	 */
	private final int[] keymodifiers = new int[256];
	/**
	 * キーの最少入力間隔(ms)
	 */
	private int duration;

	/**
	 * AndroidのBackキーが押されたかどうかのフラグ
	 * Escapeキーとして機能させるために使用
	 */
	private boolean androidBackPressed = false;

	/**
	 * 前フレームでAndroidのBackキーが検出されたかどうか
	 * フラグを1フレームだけ保持するために使用
	 */
	private boolean androidBackPressedLastFrame = false;

	// 触控手势检测相关变量
	private int touchStartX = 0;
	private int touchStartY = 0;
	private long touchStartTime = 0;
	private boolean isTouching = false;
	private boolean gestureDetected = false;

	// 手势模式：0=默认, 1=select界面, 2=result界面, 3=decide界面
	private int gestureMode = 0;

	public KeyBoardInputProcesseor(BMSPlayerInputProcessor bmsPlayerInputProcessor, KeyboardConfig config, Resolution resolution) {
		super(bmsPlayerInputProcessor, Type.KEYBOARD);
		this.mouseScratchInput = new MouseScratchInput(bmsPlayerInputProcessor, this, config);
		this.setConfig(config);
		this.resolution = resolution;

		reserved = new IntArray();
		// Reserve non-numeric ControlKeys so they can't be assigned as gameplay keys.
		// NUM0-NUM9 (number row) are excluded so users can bind them in keyconfig.
		Arrays.stream(ControlKeys.values())
			.filter(k -> k.keycode < Keys.NUM_0 || k.keycode > Keys.NUM_9)
			.forEach(keys -> reserved.add(keys.keycode));

		Arrays.fill(keytime, Long.MIN_VALUE);
	}

	public int[] getKeys() {
		return keys;
	}

	public void setConfig(KeyboardConfig config) {
		this.keys = config.getKeyAssign().clone();
		this.duration = config.getDuration();
		this.control = new int[] { config.getStart(), config.getSelect() };
		mouseScratchInput.setConfig(config);
	}

	public boolean keyDown(int keycode) {
		// Android 返回键重映射：将 code 4 (BACK) 映射为 code 111 (ESCAPE)
		if (keycode == Keys.BACK) {
			Gdx.app.log("InputDebug", "keyDown: BACK detected, remapping to ESCAPE");
			keycode = Keys.ESCAPE;
			// 触发一次模拟按键，确保 poll() 能够捕获到状态变化
			simulateKeyPress(Keys.ESCAPE);
		}

		Gdx.app.log("InputDebug", "keyDown START: " + keycode + " (" + Input.Keys.toString(keycode) + ")");
		setLastPressedKey(keycode);
		Gdx.app.log("InputDebug", "keyDown END: " + keycode);
		return true;
	}

	public boolean keyTyped(char keycode) {
		// Gdx.app.log("InputDebug", "keyTyped: " + (int)keycode + " ('" + keycode + "')");
		return false;
	}

	public boolean keyUp(int keycode) {
		// Gdx.app.log("InputDebug", "keyUp START: " + keycode + " (" + Input.Keys.toString(keycode) + ")");
		// Gdx.app.log("InputDebug", "keyUp END: " + keycode);
		return true;
	}

	public void clear() {
		Arrays.fill(keystate, false);
		Arrays.fill(keytime, Long.MIN_VALUE);
		Arrays.fill(keymodifiers, 0);
		Arrays.fill(pendingPressDeadline, 0); // 清理模拟按键保护，防止跨场次卡死
		lastPressedKey = -1;
		mouseScratchInput.clear();
	}

	public void poll(final long microtime) {
		clearPendingPresses();
		if (!textmode) {
			// 强制状态心跳：确保模拟按键状态始终覆盖物理检测结果
			for (int i = 0; i < pendingPressDeadline.length; i++) {
				if (pendingPressDeadline[i] == Long.MAX_VALUE) {
					// 如果处于模拟长按锁定中，强制保持 keystate 和核心层状态为 true
					if (!keystate[i]) {
						keystate[i] = true;
						this.bmsPlayerInputProcessor.setKeyState(i, true, microtime);
					}
				}
			}

			for (int i = 0; i < keys.length; i++) {
				if(keys[i] < 0 || (keys[i] < pendingPressDeadline.length && pendingPressDeadline[keys[i]] > 0)) {
					continue;
				}
				final boolean pressed = Gdx.input.isKeyPressed(keys[i]);
				if (pressed != keystate[keys[i]] && microtime >= keytime[keys[i]] + duration * 1000) {
					keystate[keys[i]] = pressed;
					keytime[keys[i]] = microtime;
					this.bmsPlayerInputProcessor.keyChanged(this, microtime, i, pressed);
					this.bmsPlayerInputProcessor.setAnalogState(i, false, 0);
				}
			}

			final boolean startpressed = Gdx.input.isKeyPressed(control[0]);
			if (pendingPressDeadline[control[0]] <= 0 && startpressed != keystate[control[0]]) {
				keystate[control[0]] = startpressed;
				this.bmsPlayerInputProcessor.startChanged(startpressed);
			}
			final boolean selectpressed = Gdx.input.isKeyPressed(control[1]);
			if (pendingPressDeadline[control[1]] <= 0 && selectpressed != keystate[control[1]]) {
				keystate[control[1]] = selectpressed;
				this.bmsPlayerInputProcessor.setSelectPressed(selectpressed);
			}
		}

		for (ControlKeys key : ControlKeys.values()) {
			// シミュレートキーが期限内なら poll() で上書きしない
			if (pendingPressDeadline[key.keycode] > 0) {
				continue;
			}
			// Escape键特殊处理：同时检查物理按键和Android返回键标志
			final boolean pressed;
			if (key == ControlKeys.ESCAPE) {
				// 检测androidBackPressed标志，并在检测后清除（保持一帧）
				boolean androidBackDetected = androidBackPressed && !androidBackPressedLastFrame;
				pressed = Gdx.input.isKeyPressed(key.keycode) || androidBackDetected;
				// 更新上一帧状态
				androidBackPressedLastFrame = androidBackPressed;
			} else {
				pressed = Gdx.input.isKeyPressed(key.keycode);
			}
			if (!(textmode && key.text) && pressed != keystate[key.keycode]) {
				keystate[key.keycode] = pressed;
				keytime[key.keycode] = microtime;
				keymodifiers[key.keycode] = pressed ? currentlyHeldModifiers() : 0;
				if (key == ControlKeys.ESCAPE) {
					Gdx.app.log("AndroidBack", "ESCAPE state changed to " + pressed + " with keytime=" + keytime[key.keycode]);
				}
			}
		}

		mouseScratchInput.poll(microtime);
	}

	private int currentlyHeldModifiers() {
		boolean shift = Gdx.input.isKeyPressed(Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Keys.SHIFT_RIGHT);
		boolean ctrl = Gdx.input.isKeyPressed(Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Keys.CONTROL_RIGHT);
		boolean alt = Gdx.input.isKeyPressed(Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Keys.ALT_RIGHT);
		return (shift ? MASK_SHIFT : 0) | (ctrl ? MASK_CTRL : 0) | (alt ? MASK_ALT : 0);
	}


	public boolean getKeyState(int keycode) {
		if (keycode < 0 || keycode >= keystate.length) return false;
		return keystate[keycode];
	}

	public void setKeyState(int keycode, boolean pressed) {
		if (keycode < 0 || keycode >= keystate.length) return;
		keystate[keycode] = pressed;
	}

	/**
	 * AndroidのBackキー押し状態を設定
	 * このメソッドは押しイベントのみを処理し、poll()メソッドが自動的にフラグをクリアする
	 * @param pressed 押し状態（true=押し、false=解除。ただしfalseは無視される）
	 */
	public void setAndroidBackPressed(boolean pressed) {
		if (pressed) {
			this.androidBackPressed = true;
			Gdx.app.log("AndroidBack", "setAndroidBackPressed(true) - will be detected in next poll()");
		}
		// falseの呼び出しは無視：フラグはpoll()で自動的にクリアされる
	}

	public boolean isKeyPressed(int keycode) {
		if (keycode < 0 || keycode >= keystate.length) return false;
		boolean result = keystate[keycode] && keytime[keycode] != Long.MIN_VALUE;
		if(result) {
			keytime[keycode] = Long.MIN_VALUE;
			// シミュレートキーが消費されたらdeadlineをクリア
			// 修正：如果是“永久锁定”（TouchKey长按），则不要清除 deadline，防止被 poll() 误杀状态
			if (pendingPressDeadline[keycode] > 0 && pendingPressDeadline[keycode] != Long.MAX_VALUE) {
				pendingPressDeadline[keycode] = 0;
			}
		}
		return result;
	}

	public boolean isKeyPressed(int keycode, int heldModifiers, int... notHeldModifiers) {
		if(keystate[keycode] && keytime[keycode] != Long.MIN_VALUE) {
			int modifiers = keymodifiers[keycode];
			if ((modifiers & heldModifiers) != heldModifiers) return false;
			for (int i = 0; i < notHeldModifiers.length; i++) {
				if ((modifiers & notHeldModifiers[i]) == notHeldModifiers[i]) return false;
			}
			keytime[keycode] = Long.MIN_VALUE;
			return true;
		}
		return false;
	}

	public boolean mouseMoved(int x, int y) {
		this.bmsPlayerInputProcessor.setMouseMoved(true);
		int gameX = bmsPlayerInputProcessor.convertScreenX(x);
		int gameY = bmsPlayerInputProcessor.convertScreenY(y);
		this.bmsPlayerInputProcessor.mousex = gameX;
		this.bmsPlayerInputProcessor.mousey = resolution.height - gameY;
		return false;
	}

	/**
	 * 旧InputProcessorのメソッド
	 * libGDX更新時に削除
	 */
	public boolean scrolled(int amount) {
		return scrolled(0, amount);
	}

	public boolean scrolled(float amountX, float amountY) {
		this.bmsPlayerInputProcessor.scrollX += amountX;
		this.bmsPlayerInputProcessor.scrollY += amountY;
		return false;
	}

	public boolean touchDown(int x, int y, int point, int button) {
		int gameX = bmsPlayerInputProcessor.convertScreenX(x);
		int gameY = bmsPlayerInputProcessor.convertScreenY(y);
		this.bmsPlayerInputProcessor.mousebutton = button;
		this.bmsPlayerInputProcessor.mousex = gameX;
		this.bmsPlayerInputProcessor.mousey = resolution.height - gameY;
		this.bmsPlayerInputProcessor.mousepressed = true;

		// 记录触控起始位置和用于手势检测
		lastTouchY = y;
		lastTouchTime = System.currentTimeMillis();
		touchStartX = x;
		touchStartY = y;
		touchStartTime = lastTouchTime;
		isTouching = true;
		gestureDetected = false;
		return false;
	}

	public boolean touchDragged(int x, int y, int point) {
		int gameX = bmsPlayerInputProcessor.convertScreenX(x);
		int gameY = bmsPlayerInputProcessor.convertScreenY(y);
		this.bmsPlayerInputProcessor.mousex = gameX;
		this.bmsPlayerInputProcessor.mousey = resolution.height - gameY;
		this.bmsPlayerInputProcessor.mousedragged = true;

		// 处理滑动滚动（用于皮肤选择界面的选项下拉）
		long now = System.currentTimeMillis();
		if (now - lastTouchTime > 30) {
			int deltaY = y - lastTouchY;
			if (Math.abs(deltaY) > 5) {
				// 向上滑动=scrollY增加（选项向下滚动），向下滑动=scrollY减少（选项向上滚动）
				float scrollAmount = -deltaY / 20f;
				this.bmsPlayerInputProcessor.scrollY += scrollAmount;
				lastTouchY = y;
				lastTouchTime = now;
			}
		}
		return false;
	}

	public boolean touchUp(int arg0, int arg1, int arg2, int arg3) {
		// 在触控释放时检测手势
		if (isTouching && !gestureDetected) {
			long touchDuration = System.currentTimeMillis() - touchStartTime;
			int deltaX = arg0 - touchStartX;
			int deltaY = arg1 - touchStartY;

			// 根据手势模式应用不同的手势映射
			if (gestureMode == 1) {
				// Select界面：检测从左向右滑动（左滑手势）- 映射为左方向键
				// 水平移动距离大于50像素，且水平移动大于垂直移动
				if (Math.abs(deltaX) > 50 && Math.abs(deltaX) > Math.abs(deltaY) && touchDuration < 500) {
					// 从左向右滑动，模拟左方向键按下
					simulateKeyPress(Keys.LEFT);
					Gdx.app.log("Gesture", "Select mode: Left-to-right swipe detected -> LEFT key");
					gestureDetected = true;
				}
			} else if (gestureMode == 2) {
				// Result界面：检测点击（短促触控，移动距离小）- 映射为Escape键
				if (touchDuration < 300 && Math.abs(deltaX) < 30 && Math.abs(deltaY) < 30) {
					// 短促点击，模拟Escape键按下
					simulateKeyPress(Keys.ESCAPE);
					Gdx.app.log("Gesture", "Result mode: Tap detected -> ESCAPE key");
					gestureDetected = true;
				}
			} else if (gestureMode == 3) {
				// Decide界面：检测点击（短促触控，移动距离小）- 映射为Enter键
				if (touchDuration < 300 && Math.abs(deltaX) < 30 && Math.abs(deltaY) < 30) {
					// 短促点击，模拟Enter键按下
					simulateKeyPress(Keys.ENTER);
					Gdx.app.log("Gesture", "Decide mode: Tap detected -> ENTER key");
					gestureDetected = true;
				}
			}
		}
		isTouching = false;
		return false;
	}

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        return false;
    }
	/**
	 * 消费指定按键的按下状态，使其在后续帧中不会被 isKeyPressed/isControlKeyPressed 检测到，
	 * 直到该按键被物理释放并重新按下。
	 * 用于防止在 KeyConfig 中分配按键后，同一按键触发了 NUM 控制功能。
	 */
	public void consumeKeyPress(int keycode) {
		if (keycode >= 0 && keycode < keytime.length) {
			keytime[keycode] = Long.MIN_VALUE;
		}
	}

	public int getLastPressedKey() {
		return lastPressedKey;
	}

	public void setLastPressedKey(int lastPressedKey) {
		this.lastPressedKey = lastPressedKey;
	}

	/**
	 * シミュレートされたキーの有効期限（マイクロ秒）。
	 * 0 = 非アクティブ。poll()は期限が切れるまでkeystateを上書きしない。
	 * ゲームのレンダリングスレッド（60fps≒16ms間隔）が確実に読み取れるよう、
	 * デフォルトで50msの猶予を持たせる。
	 */
	private final long[] pendingPressDeadline = new long[256];
	private static final long SIMULATED_KEY_DURATION = 50000; // 50ms (microseconds)

	/**
	 * キー押下をシミュレート。
	 * keystate/keytimeを直接設定し、deadlineを記録。
	 * poll()ControlKeysループはdeadline期限内のキーを上書きしない。
	 * isKeyPressed()で消費されるとdeadlineは即時クリアされる。
	 */
	/**
	 * 设置模拟按键状态（用于触摸按键映射）
	 * @param keycode LibGDX Keys
	 * @param pressed 是否按下
	 */
	public void setSimulatedKeyState(int keycode, boolean pressed) {
		if (keycode < 0 || keycode >= keystate.length) return;

		long now;
		if (this.bmsPlayerInputProcessor != null && this.bmsPlayerInputProcessor.getStartTime() != 0) {
			now = System.nanoTime() / 1000 - this.bmsPlayerInputProcessor.getStartTime();
		} else {
			now = System.nanoTime() / 1000;
		}

		// 改进：如果 pressed 为 true 且之前状态不一致才更新；释放时（pressed=false）始终重置状态
		if (!keystate[keycode] || pressed) {
			keystate[keycode] = pressed;
			keytime[keycode] = pressed ? now : Long.MIN_VALUE;

			// 同步到核心层
			this.bmsPlayerInputProcessor.setKeyState(keycode, pressed, keytime[keycode]);

			// 触发 keyChanged
			for (int i = 0; i < keys.length; i++) {
				if (keys[i] == keycode) {
					this.bmsPlayerInputProcessor.keyChanged(this, now, i, pressed);
					this.bmsPlayerInputProcessor.setAnalogState(i, false, 0);
					break;
				}
			}
		}
		// release（pressed=false）时无条件清 keystate，防止粘滞
		if (!pressed) {
			keystate[keycode] = false;
			keytime[keycode] = Long.MIN_VALUE;
			pendingPressDeadline[keycode] = 0;
		} else {
			pendingPressDeadline[keycode] = Long.MAX_VALUE;
		}
	}

	public void simulateKeyPress(int keycode) {
		if (keycode < 0 || keycode >= keystate.length) return;
		final long now = System.nanoTime() / 1000;
		keystate[keycode] = true;
		keytime[keycode] = now;
		keymodifiers[keycode] = 0;
		pendingPressDeadline[keycode] = now + SIMULATED_KEY_DURATION;
		Gdx.app.log("KeySim", "simulateKeyPress: " + keycode + " (deadline=" + pendingPressDeadline[keycode] + ")");
	}

	/**
	 * poll()から呼ばれる: 期限切れのシミュレートキーを解放。
	 * 期限内のキーは poll() の ControlKeys ループで上書きされない（continueで保護）。
	 */
	public void clearPendingPresses() {
		long now = System.nanoTime() / 1000;
		for (int i = 0; i < pendingPressDeadline.length; i++) {
			// 仅清理有期限的模拟按键（Long.MAX_VALUE 代表TouchKey长按，不在此清理）
			if (pendingPressDeadline[i] > 0 && pendingPressDeadline[i] != Long.MAX_VALUE) {
				if (now > pendingPressDeadline[i]) {
					pendingPressDeadline[i] = 0;
				}
			}
		}
	}

	public MouseScratchInput getMouseScratchInput() {
		return mouseScratchInput;
	}

	public void setTextInputMode(boolean textmode) {
		this.textmode = textmode;

		// 通知 Android 端文本输入状态变化（通过反射调用，避免 core 模块依赖 android 模块）
		if (textmode) {
			try {
				// 尝试调用 AndroidLauncher 的 setTextInputActive 方法
				com.badlogic.gdx.Application app = com.badlogic.gdx.Gdx.app;
				if (app != null) {
					java.lang.reflect.Method method = app.getClass().getMethod("setTextInputActive", boolean.class);
					method.invoke(app, true);
				}
			} catch (Exception e) {
				// 在非 Android 平台或方法不存在时忽略异常
			}
		} else {
			// 文本输入结束时也通知隐藏软键盘
			try {
				com.badlogic.gdx.Application app = com.badlogic.gdx.Gdx.app;
				if (app != null) {
					java.lang.reflect.Method method = app.getClass().getMethod("setTextInputActive", boolean.class);
					method.invoke(app, false);
				}
			} catch (Exception e) {
				// 在非 Android 平台或方法不存在时忽略异常
			}
		}
	}

	/**
	 * 设置手势模式
	 * @param mode 0=默认, 1=select界面, 2=result界面, 3=decide界面
	 */
	public void setGestureMode(int mode) {
		this.gestureMode = mode;
	}

	public Object getMainController() {
		return bmsPlayerInputProcessor.getMainController();
	}

	public boolean isReservedKey(int key) {
		return reserved.contains(key);
	}

	public enum ControlKeys {
		NUM0(0, Keys.NUM_0, true),
		NUM1(1, Keys.NUM_1, true),
		NUM2(2, Keys.NUM_2, true),
		NUM3(3, Keys.NUM_3, true),
		NUM4(4, Keys.NUM_4, true),
		NUM5(5, Keys.NUM_5, true),
		NUM6(6, Keys.NUM_6, true),
		NUM7(7, Keys.NUM_7, true),
		NUM8(8, Keys.NUM_8, true),
		NUM9(9, Keys.NUM_9, true),

		F1(10, Keys.F1, false),
		F2(11, Keys.F2, false),
		F3(12, Keys.F3, false),
		F4(13, Keys.F4, false),
		F5(14, Keys.F5, false),
		F6(15, Keys.F6, false),
		F7(16, Keys.F7, false),
		F8(17, Keys.F8, false),
		F9(18, Keys.F9, false),
		F10(19, Keys.F10, false),
		F11(20, Keys.F11, false),
		F12(21, Keys.F12, false),

		UP(22, Keys.UP, false),
		DOWN(23, Keys.DOWN, false),
		LEFT(24, Keys.LEFT, false),
		RIGHT(25, Keys.RIGHT, false),

		ENTER(26, Keys.ENTER, false),
		DEL(27, Keys.FORWARD_DEL, false),
		ESCAPE(28, Keys.ESCAPE, false),
		;

		public final int id;

		public final int keycode;

		public final boolean text;

		private ControlKeys(int id, int keycode, boolean text) {
			this.id = id;
			this.keycode = keycode;
			this.text = text;
		}
	}

}
