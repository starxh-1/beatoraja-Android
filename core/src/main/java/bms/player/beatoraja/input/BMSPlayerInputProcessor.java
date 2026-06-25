package bms.player.beatoraja.input;

import bms.player.beatoraja.*;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.stream.Stream;

import bms.player.beatoraja.PlayModeConfig.*;
import bms.player.beatoraja.input.BMSPlayerInputDevice.Type;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.ControllerAdapter;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.utils.Array;

/**
 * キーボードやコントローラからの入力を管理するクラス
 *
 * @author exch
 */
public class BMSPlayerInputProcessor extends ControllerAdapter {

	private boolean enable = true;
	private Object mainController; // MainController reference (for coordinate conversion)

	private KeyBoardInputProcesseor kbinput;

	private volatile BMControllerInputProcessor[] bminput;

	private ControllerConfig[] currentControllerConfig;

	private MidiInputProcessor midiinput;

	private KeyLogger keylog = new KeyLogger();

	private PlayerConfig mainPlayerConfig;

	public BMSPlayerInputProcessor(Config config, PlayerConfig player) {
		this.mainPlayerConfig = player;
		Resolution resolution = config.getResolution();
		kbinput = new KeyBoardInputProcesseor(this, player.getMode14().getKeyboardConfig(), resolution);
		// Gdx.input.setInputProcessor(kbinput);
		this.bminput = new BMControllerInputProcessor[0];

		// Register controllers already connected at startup
		for (Controller controller : Controllers.getControllers()) {
			addController(controller, player);
		}

		// Attach this class to listen for connect/disconnect events (hotplug)
		Controllers.addListener(this);

		try {
			midiinput = new MidiInputProcessor(this);
			midiinput.open();
			midiinput.setConfig(new MidiConfig());
		} catch (NoClassDefFoundError | Exception e) {
			System.err.println("MIDI 系统不可用，已跳过初始化: " + e.getMessage());
			midiinput = null;
		}

		devices = new Array<BMSPlayerInputDevice>();
		devices.add(kbinput);
		for (BMControllerInputProcessor bm : bminput) {
			devices.add(bm);
		}
		if (midiinput != null) {
			devices.add(midiinput);
		}

		this.analogScroll = config.isAnalogScroll();
	}

	public  static final int KEYSTATE_SIZE = 256;
	/**
	 * 各キーのON/OFF状態
	 * 全モードの入力が収まる大きさにしておく
	 */
	private boolean[] keystate = new boolean[KEYSTATE_SIZE];
	/**
	 * 各キーの最終更新時間
	 */
	private long[] time = new long[KEYSTATE_SIZE];

    /**
     * 選曲バーとレーンカバーのアナログスクロール
     */
    private boolean analogScroll = true;
	/**
	 * 選曲バーのアナログスクロール
	 * (各キーのアナログ状態)
	 */
	private boolean[] isAnalog = new boolean[KEYSTATE_SIZE];
	private float[] lastAnalogValue = new float[KEYSTATE_SIZE];
	private float[] currentAnalogValue = new float[KEYSTATE_SIZE];
	private long[] analogLastResetTime = new long[KEYSTATE_SIZE];

	private BMSPlayerInputDevice lastKeyDevice;
	private Array<BMSPlayerInputDevice> devices;

	private long starttime;
	private long microMarginTime;

	int mousex;
	int mousey;
	int mousebutton;
	boolean mousepressed;
	boolean mousedragged;
	private boolean mouseMoved = false;

	float scrollX;
	float scrollY;

	private boolean startPressed;
	private boolean selectPressed;

	private Type type = Type.KEYBOARD;

	public void setKeyboardConfig(KeyboardConfig config) {
		kbinput.setConfig(config);
	}

	private void addController(Controller controller, PlayerConfig player) {
		Logger.getGlobal().info("控制器被检测到: " + controller.getName());
		ControllerConfig controllerConfig = Stream.of(player.getMode7().getController())
			.filter(m -> m.getName() != null && m.getName().equals(controller.getName()))
			.findFirst()
			.orElse(new ControllerConfig());

		if (controllerConfig.getName() == null) {
			controllerConfig = Stream.of(player.getMode7().getController())
				.filter(m -> {
					try {
						return m.getName() != null && m.getName().equals(new String(controller.getName().getBytes("EUC_JP"), "UTF-8"));
					} catch (Exception e) {
						return false;
					}
				}).findFirst()
				.orElse(new ControllerConfig());
		}
		int index = 1;
		String name = controller.getName();
		for(BMControllerInputProcessor bm : bminput) {
			if(bm.getName().equals(name)) {
				index++;
				name = controller.getName() + "-" + index;
			}
		}
		BMControllerInputProcessor bm = new BMControllerInputProcessor(this, name, controller, controllerConfig);
		final BMControllerInputProcessor[] updated = Arrays.copyOf(bminput, bminput.length + 1);
		updated[bminput.length] = bm;
		bminput = updated;
	}

	private void removeController(Controller controller) {
		BMControllerInputProcessor target = null;
		for (BMControllerInputProcessor bm : bminput) {
			if (bm.getController() == controller) {
				target = bm;
				break;
			}
		}
		if (target == null) return;
		final BMControllerInputProcessor[] updated = new BMControllerInputProcessor[bminput.length - 1];
		int j = 0;
		for (BMControllerInputProcessor bm : bminput) {
			if (bm != target) updated[j++] = bm;
		}
		bminput = updated;
	}

	@Override
	public void connected(Controller controller) {
		String name = controller.getName();
		addController(controller, mainPlayerConfig);
		if (currentControllerConfig != null) {
			setControllerConfig(currentControllerConfig);
		}
		Logger.getGlobal().info("Controller connected: " + name);
	}

	@Override
	public void disconnected(Controller controller) {
		String name = controller.getName();
		removeController(controller);
		if (currentControllerConfig != null) {
			setControllerConfig(currentControllerConfig);
		}
		Logger.getGlobal().info("Controller disconnected: " + name);
	}

	public void setControllerConfig(ControllerConfig[] configs) {
		this.currentControllerConfig = configs;
		boolean[] b = new boolean[configs.length];
		for (BMControllerInputProcessor controller : bminput) {
			controller.setEnable(false);
			for(int i = 0;i < configs.length;i++) {
				if(b[i]) {
					continue;
				}
				if(configs[i].getName() == null || configs[i].getName().length() == 0) {
					configs[i].setName(controller.getName());
				}
				if(controller.getName().equals(configs[i].getName()) ||
					(controller.getName().contains("Xbox") && configs[i].getName().contains("Xbox"))) {
					controller.setConfig(configs[i]);
					controller.setEnable(true);
					b[i] = true;
					break;
				}
			}
		}
	}

	public void setMidiConfig(MidiConfig config) {
		if (midiinput != null) {
			midiinput.setConfig(config);
		}
	}

	public void setStartTime(long starttime) {
		this.starttime = starttime;
		if (starttime != 0) {
			resetAllKeyChangedTime();
			keylog.clear();
			kbinput.clear();
			for (BMControllerInputProcessor bm : bminput) {
				bm.clear();
			}
		}
		if (midiinput != null) {
			midiinput.setStartTime(starttime);
		}
	}

	public void setKeyLogMarginTime(long milliMarginTime) {
		microMarginTime = milliMarginTime * 1000;
	}

	public long getStartTime() {
		return starttime;
	}

	/**
	 * 指定のキーIDのキー状態を返す
	 * @param id キーID
	 * @return 押されていればtrue
	 */
	public boolean getKeyState(int id) {
		return id >= 0 && id < keystate.length ? keystate[id] : false;
	}

	/**
	 * 指定のキーIDのキー状態を設定する
	 * @param id キーID
	 * @param pressed キー状態
	 * @param time キー状態の変更時間
	 */
	public void setKeyState(int id, boolean pressed, long time) {
		if(id >= 0 && id < keystate.length) {
			keystate[id] = pressed;
			this.time[id] = time;
		}
	}

	/**
	 * 模拟按键变更，并记录到 keylog 中（用于触屏按键等虚拟输入）
	 * @param id 逻辑按键 ID
	 * @param pressed 是否按下
	 * @param microtime 游戏内微秒时间戳（相对于歌曲开始）
	 */
	public void setKeyChanged(int id, boolean pressed, long microtime) {
		keyChanged(null, microtime, id, pressed);
	}

	/**
	 * 指定のキーIDのキー状態変更時間を返す
	 * @param id キーID
	 * @return キー状態の変更時間。変更されていない場合はLong.MIN_VALUE
	 */
	public long getKeyChangedTime(int id) {
		return id >= 0 && id < time.length ? time[id] : Long.MIN_VALUE;
	}

	/**
	 * 指定のキーIDのキー状態変更時間をリセットする
	 * @param id キーID
	 * @return キー状態の変更時間が設定されていればtrue
	 */
	public boolean resetKeyChangedTime(int id) {
		if(id >= 0 && id < time.length) {
			boolean result = time[id] != Long.MIN_VALUE;
			time[id] = Long.MIN_VALUE;
			return result;
		}
		return false;
	}

	/**
	 * 全てのキー状態をリセットする
	 */
	public void resetAllKeyState() {
		Arrays.fill(keystate, false);
		Arrays.fill(time, Long.MIN_VALUE);
		if (kbinput != null) {
			kbinput.clear();
		}
	}

	/**
	 * 全てのキー状態変更時間をリセットする
	 */
	public void resetAllKeyChangedTime() {
		Arrays.fill(time, Long.MIN_VALUE);
	}

	public BMSPlayerInputDevice getLastKeyChangedDevice() {
		return lastKeyDevice;
	}

	public int getNumberOfDevice() {
		return bminput.length + 1;
	}

	public void setPlayConfig(PlayModeConfig playconfig) {
		// 核心修复：打歌开始前刷新一次控制器列表，确保 2.4G/有线手柄在延迟识别后能被正确加载
		updateControllers(mainPlayerConfig);

		// KB, コントローラー, Midiの各ボタンについて排他的処理を実施
		int[] kbkeys = playconfig.getKeyboardConfig().getKeyAssign();
		boolean[] exclusive = new boolean[kbkeys.length];
		for(int i = kbkeys.length;i < keystate.length;i++) {
			keystate[i] = false;
			time[i] = Long.MIN_VALUE;
		}

		int kbcount = setPlayConfig0(kbkeys,  exclusive);

		int[][] cokeys = new int[playconfig.getController().length][];
		int cocount = 0;
		for(int i = 0;i < cokeys.length;i++) {
			cokeys[i] = playconfig.getController()[i].getKeyAssign();
			cocount += setPlayConfig0(cokeys[i],  exclusive);
		}

		MidiConfig.Input[] mikeys  = playconfig.getMidiConfig().getKeys();
		int micount = 0;
		for(int i = 0;i < mikeys.length;i++) {
			if(exclusive[i]) {
				mikeys[i] = null;
			} else {
				exclusive[i] = true;
				micount++;
			}
		}

		// 各デバイスにキーコンフィグをセット
		kbinput.setConfig(playconfig.getKeyboardConfig());
		setControllerConfig(playconfig.getController());
		if (midiinput != null) {
			midiinput.setConfig(playconfig.getMidiConfig());
		}

		if(kbcount >= cocount && kbcount >= micount) {
			type = Type.KEYBOARD;
		} else if(cocount >= kbcount && cocount >= micount) {
			type = Type.BM_CONTROLLER;
		} else {
			type = Type.MIDI;
		}
	}

	public BMSPlayerInputDevice.Type getDeviceType() {
		return type;
	}

	private int setPlayConfig0(int[] keys, boolean[] exclusive) {
		int count = 0;
		for(int i = 0;i < keys.length;i++) {
			if (i >= exclusive.length) break; // safety
			if(exclusive[i]) {
				// 核心修复：如果该轨道（lane）已经被键盘或其他设备占据，则该设备的当前按键映射失效。
				// 但是，如果按键是“打包”的（packed keys），我们需要确保不破坏它，或者正确处理。
				// 原逻辑：keys[i] = -1;
				// 既然我们要支持多键位，这里如果简单置为 -1 会导致该槽位彻底失效。
				// 我们暂时注释掉排他性覆盖，允许不同设备映射到同一轨道。
				// keys[i] = -1;
				count++;
			} else if(keys[i] != -1){
				exclusive[i] = true;
				count++;
			}
		}
		return count;
	}

	public void setEnable(boolean enable) {
		this.enable = enable;
		if(!enable) {
			resetAllKeyState();
			for (BMSPlayerInputDevice device : devices) {
				device.clear();
			}
		}
	}

	public boolean getControlKeyState(ControlKeys key) {
		return kbinput.getKeyState(key.keycode);
	}

	public boolean isControlKeyPressed(ControlKeys key) {
		return kbinput.isKeyPressed(key.keycode);
	}

	public boolean isControlKeyPressed(ControlKeys key, int heldModifiers, int... notHeldModifiers) {
		return kbinput.isKeyPressed(key.keycode, heldModifiers, notHeldModifiers);
	}

	public void setControlKeyPressed(ControlKeys key, boolean pressed) {
		kbinput.setSimulatedKeyState(key.keycode, pressed);
	}

	protected void keyChanged(BMSPlayerInputDevice device, long presstime, int i, boolean pressed) {
		if (!enable) {
			return;
		}
		if (keystate[i] != pressed) {
			keystate[i] = pressed;
			time[i] = presstime;
			lastKeyDevice = device;
			if (starttime != 0) {
				keylog.add(presstime - microMarginTime, i, pressed);
			}
		}
	}

	public void setAnalogState(int i, boolean _isAnalog, float _analogValue) {
		if (!enable) {
			return;
		}
		if (analogScroll) {
			isAnalog[i] = _isAnalog;
			currentAnalogValue[i] = _analogValue;
		} else {
			isAnalog[i] = false;
			currentAnalogValue[i] = 0;
		}
	}

	public void resetAnalogInput(int i) {
		lastAnalogValue[i] = currentAnalogValue[i];
		analogLastResetTime[i] = System.currentTimeMillis();
	}

	public long getTimeSinceLastAnalogReset(int i) {
		return System.currentTimeMillis() - analogLastResetTime[i];
	}

	public int getAnalogDiff(int i) {
		return BMControllerInputProcessor.computeAnalogDiff(lastAnalogValue[i], currentAnalogValue[i]);
	}

	public boolean isAnalogInput(int i) {
		return isAnalog[i];
	}

	public int getAnalogDiffAndReset(int i, int msTolerance) {
		int dTicks = 0;
        if (getTimeSinceLastAnalogReset(i) <= msTolerance) {
            dTicks = Math.max(0,getAnalogDiff(i));
        }
        resetAnalogInput(i);
        return dTicks;
	}

	public KeyInputLog[] getKeyInputLog() {
		return keylog.toArray();
	}

	public void startChanged(boolean pressed) {
		startPressed = pressed;
	}

	public boolean startPressed() {
		return startPressed;
	}

	public boolean isActivated(KeyCommand key) {
		final int MASK_CTRL = KeyBoardInputProcesseor.MASK_CTRL;
		final int MASK_CTRL_SHIFT = KeyBoardInputProcesseor.MASK_CTRL|KeyBoardInputProcesseor.MASK_SHIFT;

		switch(key) {
		case SHOW_FPS:
			return isControlKeyPressed(ControlKeys.F1);
		case UPDATE_FOLDER:
			return isControlKeyPressed(ControlKeys.F2);
		case OPEN_EXPLORER:
			return isControlKeyPressed(ControlKeys.F3, 0, MASK_CTRL, MASK_CTRL_SHIFT);
		case COPY_SONG_MD5_HASH:
			return isControlKeyPressed(ControlKeys.F3, MASK_CTRL, MASK_CTRL_SHIFT);
		case COPY_SONG_SHA256_HASH:
			return isControlKeyPressed(ControlKeys.F3, MASK_CTRL_SHIFT);
		case SWITCH_SCREEN_MODE:
			return isControlKeyPressed(ControlKeys.F4);
		case SAVE_SCREENSHOT:
			return isControlKeyPressed(ControlKeys.F6);
		case POST_TWITTER:
			return isControlKeyPressed(ControlKeys.F7);
		case ADD_FAVORITE_SONG:
			return isControlKeyPressed(ControlKeys.F8);
		case ADD_FAVORITE_CHART:
			return isControlKeyPressed(ControlKeys.F9);
		case AUTOPLAY_FOLDER:
			return isControlKeyPressed(ControlKeys.F10);
		case OPEN_IR:
			return isControlKeyPressed(ControlKeys.F11);
		case OPEN_SKIN_CONFIGURATION:
			return isControlKeyPressed(ControlKeys.F12);
		}
		return false;
	}

	public boolean isSelectPressed() {
		return selectPressed;
	}

	public void setSelectPressed(boolean selectPressed) {
		this.selectPressed = selectPressed;
	}

	public KeyBoardInputProcesseor getKeyBoardInputProcesseor() {
		return kbinput;
	}

	public BMControllerInputProcessor[] getBMInputProcessor() {
		return bminput;
	}

	public MidiInputProcessor getMidiInputProcessor() {
		return midiinput;
	}

	public int getMouseX() {
		return mousex;
	}

	public int getMouseY() {
		return mousey;
	}

	public int getMouseButton() {
		return mousebutton;
	}

	public boolean isMousePressed() {
		return mousepressed;
	}

	public void setMousePressed() {
		mousepressed = false;
	}

	public boolean isMouseDragged() {
		return mousedragged;
	}

	public void setMouseDragged() {
		mousedragged = false;
	}

	public void setMainController(Object mc) {
		this.mainController = mc;
	}

	public Object getMainController() {
		return mainController;
	}

	// Helper to convert screen coordinates using MainController's viewport info
	public int convertScreenX(int screenX) {
		if (mainController != null) {
			try {
				java.lang.reflect.Method method = mainController.getClass().getMethod("screenToGameX", int.class);
				return (Integer) method.invoke(mainController, screenX);
			} catch (Exception e) {
				// Fall through
			}
		}
		return screenX;
	}

	public int convertScreenY(int screenY) {
		if (mainController != null) {
			try {
				java.lang.reflect.Method method = mainController.getClass().getMethod("screenToGameY", int.class);
				return (Integer) method.invoke(mainController, screenY);
			} catch (Exception e) {
				// Fall through
			}
		}
		return screenY;
	}

	public boolean isMouseMoved() {
		return mouseMoved;
	}

	public void setMouseMoved(boolean mouseMoved) {
		this.mouseMoved = mouseMoved;
	}

	public int getScroll() {
		return (int) -scrollY;
	}

	public float getScrollX() {
		return scrollX;
	}

	public float getScrollY() {
		return scrollY;
	}

	public void resetScroll() {
		scrollX = scrollY = 0;
	}

	public void updateControllers(PlayerConfig player) {
		this.mainPlayerConfig = player;
		this.bminput = new BMControllerInputProcessor[0];
		for (Controller controller : Controllers.getControllers()) {
			addController(controller, player);
		}
		// Apply the current controller config if set
		if (currentControllerConfig != null) {
			setControllerConfig(currentControllerConfig);
		}
		// 同步更新设备轮询列表
		devices.clear();
		devices.add(kbinput);
		for (BMControllerInputProcessor bm : bminput) {
			devices.add(bm);
		}
		if (midiinput != null) {
			devices.add(midiinput);
		}
		Logger.getGlobal().info("控制器列表已更新，当前数量: " + bminput.length);
	}

	public void poll() {
		final long now = System.nanoTime() / 1000 - starttime;
		kbinput.poll(now);
		// 触摸检测：仅检测是否有触摸事件发生（用于决定是否跳过控制器轮询）
		final boolean isTouched = com.badlogic.gdx.Gdx.input.isTouched();
		// 如果正在触摸，跳过控制器轮询以避免触摸按键和控制器按键冲突
		if (!isTouched) {
			for (BMControllerInputProcessor controller : bminput) {
				controller.poll(now);
			}
		}
	}

	public void dispose() {
		Controllers.removeListener(this);
		if (midiinput != null) {
			midiinput.close();
		}
	}

	/**
	 * キーロガー
	 *
	 * @author exch
	 */
	private static class KeyLogger {

		public static final int INITIAL_LOG_COUNT = 10000;

		private final Array<KeyInputLog> keylog;

		private final KeyInputLog[] logpool;
		private int poolindex;

		public KeyLogger() {
			keylog = new Array<KeyInputLog>(INITIAL_LOG_COUNT);
			logpool = new KeyInputLog[INITIAL_LOG_COUNT];
			clear();
		}

		/**
		 * キー入力ログを追加する
		 *
		 * @param presstime キー入力時間(us)
		 * @param keycode キーコード
		 * @param pressed 押されたかどうか
		 */
		public void add(long presstime, int keycode, boolean pressed) {
			final KeyInputLog log = poolindex < logpool.length ? logpool[poolindex] : new KeyInputLog();
			poolindex++;
			log.setData(presstime, keycode, pressed);
			keylog.add(log);
		}

		/**
		 * キーログをクリアする
		 */
		public void clear() {
			keylog.clear();
			for(int i = 0;i < logpool.length;i++) {
				logpool[i] = new KeyInputLog();
			}
		}

		/**
		 *
		 * @return
		 */
		public KeyInputLog[] toArray() {
			return keylog.toArray(KeyInputLog.class);
		}
	}
}
