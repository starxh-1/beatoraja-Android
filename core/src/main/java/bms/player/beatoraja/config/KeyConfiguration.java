package bms.player.beatoraja.config;

import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.SkinType;

import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.utils.GdxRuntimeException;

import bms.model.Mode;
import bms.player.beatoraja.*;
import bms.player.beatoraja.PlayModeConfig.*;
import bms.player.beatoraja.input.*;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;

/**
 * キーコンフィグ画面
 *
 * @author exch
 */
public class KeyConfiguration extends MainState {

	// TODO スキンベースへ移行

	private BitmapFont titlefont;

	private static final String[] MODE = { "5 KEYS", "7 KEYS", "9 KEYS", "10 KEYS", "14 KEYS", "24 KEYS", "24 KEYS DOUBLE" };
	private static final Mode[] MODE_HINT = { Mode.BEAT_5K,Mode.BEAT_7K, Mode.POPN_9K, Mode.BEAT_10K, Mode.BEAT_14K, Mode.KEYBOARD_24K,
			Mode.KEYBOARD_24K_DOUBLE };

	private static final String[][] KEYS = {
			{ "1 KEY", "2 KEY", "3 KEY", "4 KEY", "5 KEY", "F-SCR", "R-SCR", "START", "SELECT" },
			{ "1 KEY", "2 KEY", "3 KEY", "4 KEY", "5 KEY", "6 KEY", "7 KEY", "F-SCR", "R-SCR", "START", "SELECT" },
			{ "1 KEY", "2 KEY", "3 KEY", "4 KEY", "5 KEY", "6 KEY", "7 KEY", "8 KEY", "9 KEY", "START", "SELECT" },
			// 10 KEYS: 1P/2P 各 7 槽 + START + SELECT = 16 项,必须与 KEYSA[3] 长度一致
			{ "1P-1 KEY", "1P-2 KEY", "1P-3 KEY", "1P-4 KEY", "1P-5 KEY", "1P-F-SCR",
				"1P-R-SCR", "2P-1 KEY", "2P-2 KEY", "2P-3 KEY", "2P-4 KEY", "2P-5 KEY",
				"2P-F-SCR", "2P-R-SCR", "START", "SELECT" },
			// 14 KEYS: 1P/2P 各 9 槽 + START + SELECT = 20 项,必须与 KEYSA[4] 长度一致
			{ "1P-1 KEY", "1P-2 KEY", "1P-3 KEY", "1P-4 KEY", "1P-5 KEY", "1P-6 KEY", "1P-7 KEY", "1P-F-SCR",
					"1P-R-SCR", "2P-1 KEY", "2P-2 KEY", "2P-3 KEY", "2P-4 KEY", "2P-5 KEY", "2P-6 KEY", "2P-7 KEY",
					"2P-F-SCR", "2P-R-SCR", "START", "SELECT" },
			{ "C1", "C#1", "D1", "D#1", "E1", "F1", "F#1", "G1", "G#1", "A1", "A#1", "B1", "C2", "C#2", "D2", "D#2",
					"E2", "F2", "F#2", "G2", "G#2", "A2", "A#2", "B2", "WHEEL-UP", "WHEEL-DOWN", "START", "SELECT" },
			{ "1P-C1", "1P-C#1", "1P-D1", "1P-D#1", "1P-E1", "1P-F1", "1P-F#1", "1P-G1", "1P-G#1", "1P-A1", "1P-A#1",
					"1P-B1", "1P-C2", "1P-C#2", "1P-D2", "1P-D#2", "1P-E2", "1P-F2", "1P-F#2", "1P-G2", "1P-G#2",
					"1P-A2", "1P-A#2", "1P-B2", "1P-WHEEL-UP", "1P-WHEEL-DOWN", "2P-C1", "2P-C#1", "2P-D1", "2P-D#1",
					"2P-E1", "2P-F1", "2P-F#1", "2P-G1", "2P-G#1", "2P-A1", "2P-A#1", "2P-B1", "2P-C2", "2P-C#2",
					"2P-D2", "2P-D#2", "2P-E2", "2P-F2", "2P-F#2", "2P-G2", "2P-G#2", "2P-A2", "2P-A#2", "2P-B2",
					"2P-WHEEL-UP", "2P-WHEEL-DOWN", "START", "SELECT" } };;
	private static final int[][] KEYSA = {
			// 5/7/9 KEYS: 单人模式,无 2P 区分
			{ 0, 1, 2, 3, 4, 5, 6, -1, -2 },
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, -1, -2 },
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, -1, -2 },
			// 10 KEYS: 仅 1P (0..13),不暴露 2P 槽位
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, -1, -2 },
			// 14 KEYS: 仅 1P (0..17),不暴露 2P 槽位
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, -1, -2 },
			// 24 KEYS (单玩家模式): 仅 1P (0..25),不暴露 2P 槽位
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -2 },
			// 24 KEYS DOUBLE: 共享同一 controllerConfigs[0],1P (0..25) + 2P (26..51)
			{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
					29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1,
					-2 } };

	private static final String[] SELECTKEY = { "2dx sp", "popn", "2dx dp" };

	private int cursorpos = 0;
	private int scrollpos = 0;
	private boolean keyinput = false;

	private int mode = 0;

	private ShapeRenderer shape;

	private BMSPlayerInputProcessor input;
	private KeyBoardInputProcesseor keyboard;
	private BMControllerInputProcessor[] controllers;
	private MidiInputProcessor midiinput;

	private PlayerConfig config;
	private PlayModeConfig pc;
	private KeyboardConfig keyboardConfig;
	private ControllerConfig[] controllerConfigs;
	private MidiConfig midiconfig;

	private boolean deletepressed = false;

	// 当前模式的keys和keysa，供renderContent使用
	private String[] currentKeys;
	private int[] currentKeysa;

	public KeyConfiguration(MainController main) {
		super(main);

	}

	public void create() {
		loadSkin(SkinType.KEY_CONFIG);
		// Android: 禁用 KeyConfig 界面的 Stage 触摸，防止与 FloatingMenu 冲突
		setStage(null);
		if(getSkin() == null) {
			SkinHeader header = new SkinHeader();
			header.setSourceResolution(Resolution.HD);
			header.setDestinationResolution(main.getConfig().getResolution());
			this.setSkin(new KeyConfigurationSkin(header));
		}

		try {
			FreeTypeFontGenerator generator = new FreeTypeFontGenerator(
					MainController.resolveFontFileHandle(main.getConfig().getSystemfontpath()));
			FreeTypeFontParameter parameter = new FreeTypeFontParameter();
			parameter.size = (int) (20 * getSkin().getScaleY());
			titlefont = generator.generateFont(parameter);
			generator.dispose();
		} catch (GdxRuntimeException e) {
			Logger.getGlobal().severe("Font読み込み失敗");
		}

		shape = new ShapeRenderer();

		input = main.getInputProcessor();
		// 核心修复：进入 KeyConfig 时强制刷新控制器列表，解决 Android 枚举延迟问题
		input.updateControllers(main.getPlayerConfig());

		keyboard = input.getKeyBoardInputProcesseor();
		controllers = input.getBMInputProcessor();

		// 调试日志：检查控制器数组
		Gdx.app.log("KeyConfig", "Controllers array initialized, count: " + controllers.length);
		for (int i = 0; i < controllers.length; i++) {
			Gdx.app.log("KeyConfig", "  Controller " + i + ": " + controllers[i].getName());
		}

		for (BMControllerInputProcessor controller: controllers) {
			controller.setEnable(true);
		}
		midiinput = input.getMidiInputProcessor();
		setMode(0);
	}

	@Override
	public void input() {
		// 禁用界面原生触摸处理，仅允许通过 InputProcessor(FloatingMenu/Keyboard) 驱动
	}

	public void render() {
		final SpriteBatch sprite = main.getSpriteBatch();
		final float scaleX = (float) getSkin().getScaleX();
		final float scaleY = (float) getSkin().getScaleY();

		Gdx.gl.glClearColor(0, 0, 0, 1);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

		// 热插拔：每帧刷新控制器列表，确保新连接/断开的控制器能实时反映
		controllers = input.getBMInputProcessor();

		if (input.isControlKeyPressed(ControlKeys.LEFT)) {
			setMode((mode + KEYS.length - 1) % KEYS.length);
		}
		if (input.isControlKeyPressed(ControlKeys.RIGHT)) {
			setMode((mode + 1) % KEYS.length);
		}

		pollControllerNavShortcuts(!keyinput);
		if (!keyinput) {
			// 调试：追踪 keyinput 模式状态
		}

		currentKeys = KEYS[mode];
		currentKeysa = KEYSA[mode];

		if (keyinput) {
			if (keyinput && input.getKeyBoardInputProcesseor().getLastPressedKey() != -1) {
				setKeyboardKeyAssign(currentKeysa[cursorpos]);
				// 消费按键状态，防止同一按键在 keyinput=false 后触发 NUM 控制功能
				keyboard.consumeKeyPress(input.getKeyBoardInputProcesseor().getLastPressedKey());
				// System.out.println(input.getKeyBoardInputProcesseor().getLastPressedKey());
				keyinput = false;
			}
			if (keyinput && input.getKeyBoardInputProcesseor().getMouseScratchInput().getLastMouseScratch() != -1) {
				setMouseScratchKeyAssign(currentKeysa[cursorpos], input.getKeyBoardInputProcesseor());
				// System.out.println(input.getKeyBoardInputProcesseor().getLastMouseScratch());
				keyinput = false;
			}
			for (BMControllerInputProcessor bmc : controllers) {
				if (keyinput && bmc.getLastPressedButton() != -1) {
					setControllerKeyAssign(currentKeysa[cursorpos], bmc);
					// 显示配置信息
					String keyName = currentKeys[cursorpos >= 0 && cursorpos < currentKeys.length ? cursorpos : 0]; // 仅用于日志,cursorpos 已由 KEYSA.length 约束,越界时回退 0
					String buttonName = BMControllerInputProcessor.BMKeys.toString(bmc.getLastPressedButton());
					String controllerType = XboxControllerHelper.isXboxController(bmc.getName()) ? "XBOX" : "Gamepad";
					Gdx.app.log("KeyConfig", "Mapped " + controllerType + " [" + bmc.getName() + "] "
						+ buttonName + " -> " + keyName);
					keyinput = false;
					break;
				}
			}
			if (keyinput && midiinput != null && midiinput.hasLastPressedKey()) {
				setMidiKeyAssign(currentKeysa[cursorpos]);
				keyinput = false;
			}
			if (input.isControlKeyPressed(ControlKeys.DEL)) {
				deletepressed = true;
			}
		} else {
			if (input.isControlKeyPressed(ControlKeys.UP)) {
				cursorpos = (cursorpos + currentKeys.length - 1) % currentKeys.length;
			}
			if (input.isControlKeyPressed(ControlKeys.DOWN)) {
				cursorpos = (cursorpos + 1) % currentKeys.length;
			}
			if (input.isControlKeyPressed(ControlKeys.NUM1)) {
				config.setMusicselectinput((config.getMusicselectinput() + 1) % 3);
			}
			// change contronnler device 1
			if (input.isControlKeyPressed(ControlKeys.NUM2)) {
				if (controllers.length > 0) {
					int index = 0;
					for (; index < controllers.length; index++) {
						if (controllers[index].getName().equals(pc.getController()[0].getName())) {
							break;
						}
					}
					pc.getController()[0]
							.setName(controllers[(index + 1) % controllers.length].getName());
					pc.setController(pc.getController());

					// 显示切换提示
					String newName = pc.getController()[0].getName();
					String typeHint = XboxControllerHelper.isXboxController(newName) ? " [XBOX]" : " [Gamepad]";
					Gdx.app.log("KeyConfig", "Switched Controller 1 to: " + newName + typeHint);
				}
			}
			// change contronnler device 2
			if (input.isControlKeyPressed(ControlKeys.NUM3)) {
				if (controllers.length > 0 && pc.getController().length > 1) {
					int index = 0;
					for (; index < controllers.length; index++) {
						if (controllers[index].getName().equals(pc.getController()[1].getName())) {
							break;
						}
					}
					pc.getController()[1]
							.setName(controllers[(index + 1) % controllers.length].getName());
					pc.setController(pc.getController());

					// 显示切换提示
					String newName = pc.getController()[1].getName();
					String typeHint = XboxControllerHelper.isXboxController(newName) ? " [XBOX]" : " [Gamepad]";
					Gdx.app.log("KeyConfig", "Switched Controller 2 to: " + newName + typeHint);
				}
			}

			if (input.isControlKeyPressed(ControlKeys.NUM7)) {
				keyboardConfig.setKeyAssign(MODE_HINT[mode], true);
				keyboardConfig.getMouseScratchConfig().setKeyAssign(MODE_HINT[mode]);
				for (int i = 0; i < controllerConfigs.length; i++) {
					controllerConfigs[i].setKeyAssign(MODE_HINT[mode], i, false);
				}
				midiconfig.setKeyAssign(MODE_HINT[mode], false);
			}
			if (input.isControlKeyPressed(ControlKeys.NUM8)) {
				keyboardConfig.setKeyAssign(MODE_HINT[mode], false);
				keyboardConfig.getMouseScratchConfig().setKeyAssign(MODE_HINT[mode]);
				for (int i = 0; i < controllerConfigs.length; i++) {
					controllerConfigs[i].setKeyAssign(MODE_HINT[mode], i, true);
				}
				midiconfig.setKeyAssign(MODE_HINT[mode], false);
			}
			if (input.isControlKeyPressed(ControlKeys.NUM9)) {
				keyboardConfig.setKeyAssign(MODE_HINT[mode], false);
				keyboardConfig.getMouseScratchConfig().setKeyAssign(MODE_HINT[mode]);
				for (int i = 0; i < controllerConfigs.length; i++) {
					controllerConfigs[i].setKeyAssign(MODE_HINT[mode], i, false);
				}
				midiconfig.setKeyAssign(MODE_HINT[mode], true);
			}

			if (input.isControlKeyPressed(ControlKeys.ENTER)) {
				setKeyAssignMode(cursorpos);
			}

			if (input.isControlKeyPressed(ControlKeys.DEL)) {
				if(!deletepressed) deleteKeyAssign(currentKeysa[cursorpos]);
				deletepressed = true;
			} else deletepressed = false;
		}

		// 处理ESC按键退出到主菜单（全局处理，不依赖keyinput状态）
		if (input.isControlKeyPressed(ControlKeys.ESCAPE)) {
			input.setControlKeyPressed(ControlKeys.ESCAPE, false); // 强制清除状态
			input.resetAllKeyState(); // 彻底清除所有按键状态，防止粘滞跳转回本界面
			main.saveConfig();
			main.changeState(MainStateType.MUSICSELECT);
			return; // 立即返回，不再执行后续 renderContent
		}

		// 渲染界面内容
		renderContent(sprite, scaleX, scaleY);
	}

	/**
	 * 手柄全局快捷键：十字键/摇杆 → UP/DOWN/LEFT/RIGHT，A → Enter，X → Del，B → Esc。
	 * 必须在 keyinput 块之前调用，否则 A/X/B 会被当作"绑定到当前光标位"。
	 */
		/**
		 * 手柄全局快捷键：仅在 keyinput=false (非分配模式) 时生效。
		 * keyinput=true 时不消费任何手柄按键，全部留给键位分配逻辑。
	 */
	private void pollControllerNavShortcuts(boolean consumeAsNav) {
		if (!consumeAsNav) return;
		for (BMControllerInputProcessor bmc : controllers) {
			int btn = bmc.getLastPressedButton();
			if (btn < 0) continue;
			int simKey = -1;
			if (btn == BMControllerInputProcessor.BMKeys.AXIS2_MINUS
					|| btn == XboxControllerHelper.ANDROID_DPAD_UP) {
				simKey = Keys.UP;
			} else if (btn == BMControllerInputProcessor.BMKeys.AXIS2_PLUS
					|| btn == XboxControllerHelper.ANDROID_DPAD_DOWN) {
				simKey = Keys.DOWN;
			} else if (btn == BMControllerInputProcessor.BMKeys.AXIS1_PLUS
					|| btn == XboxControllerHelper.ANDROID_DPAD_RIGHT) {
				simKey = Keys.RIGHT;
			} else if (btn == BMControllerInputProcessor.BMKeys.AXIS1_MINUS
					|| btn == XboxControllerHelper.ANDROID_DPAD_LEFT) {
				simKey = Keys.LEFT;
			} else if (consumeAsNav) {
				if (btn == XboxControllerHelper.XBOX_BUTTON_A || btn == XboxControllerHelper.ANDROID_BUTTON_A
						|| btn == XboxControllerHelper.XBOX_BUTTON_L3 || btn == XboxControllerHelper.ANDROID_BUTTON_L3) {
					simKey = Keys.ENTER;
				} else if (btn == XboxControllerHelper.XBOX_BUTTON_X || btn == XboxControllerHelper.ANDROID_BUTTON_X) {
					simKey = Keys.FORWARD_DEL;
				} else if (btn == XboxControllerHelper.XBOX_BUTTON_B || btn == XboxControllerHelper.ANDROID_BUTTON_B
						|| btn == XboxControllerHelper.XBOX_BUTTON_R3 || btn == XboxControllerHelper.ANDROID_BUTTON_R3) {
					simKey = Keys.ESCAPE;
				} else if (btn == XboxControllerHelper.XBOX_BUTTON_Y || btn == XboxControllerHelper.ANDROID_BUTTON_Y) {
					simKey = Keys.FORWARD_DEL;
				}
			}
			if (simKey >= 0) {
				keyboard.simulateKeyPress(simKey);
				bmc.setLastPressedButton(-1);
			}
		}
	}

	/**
	 * 渲染界面内容（不包括输入处理）
	 */
	private void renderContent(SpriteBatch sprite, float scaleX, float scaleY) {
		sprite.begin();
		if(titlefont != null) {
			titlefont.setColor(Color.CYAN);
			titlefont.draw(sprite, "<-- " + MODE[mode] + " -->", 80 * scaleX, 650 * scaleY);
			titlefont.setColor(Color.YELLOW);
			titlefont.draw(sprite, "Key Board", 180 * scaleX, 620 * scaleY);

			// 检测并显示控制器类型
			String controller1Name = pc.getController()[0].getName();
			String controller1Type = "Controller1";
			if (XboxControllerHelper.isXboxController(controller1Name)) {
				controller1Type = "XBOX Gamepad";
			} else if (controller1Name != null && !controller1Name.isEmpty()) {
				controller1Type = "Gamepad";
			}
			titlefont.setColor(Color.GREEN);
			titlefont.draw(sprite, controller1Type, 330 * scaleX, 620 * scaleY);

			if (pc.getController().length > 1) {
				titlefont.setColor(Color.YELLOW);
				titlefont.draw(sprite, "Controller2", 480 * scaleX, 620 * scaleY);
			}

			titlefont.draw(sprite, "MIDI", 630 * scaleX, 620 * scaleY);
			titlefont.setColor(Color.ORANGE);
			titlefont.draw(sprite, "Music Select (press [1] to change) :   ", 750 * scaleX, 620 * scaleY);
			titlefont.draw(sprite, SELECTKEY[config.getMusicselectinput()], 780 * scaleX, 590 * scaleY);

			// 显示所有已连接的控制器，标注哪个是 1P/2P
			boolean has2P = pc.getController().length > 1;
			int controllerCount = controllers != null ? controllers.length : 0;
			String name1 = pc.getController()[0].getName();
			String name2 = has2P ? pc.getController()[1].getName() : null;

			titlefont.setColor(Color.ORANGE);
			titlefont.draw(sprite, "Controllers (press [2]/[3] to change):", 750 * scaleX, 520 * scaleY);

			if (controllerCount == 0) {
				titlefont.setColor(Color.RED);
				titlefont.draw(sprite, "  No controllers detected!", 750 * scaleX, 490 * scaleY);
			} else {
				int yBase = 490;
				for (int i = 0; i < controllers.length && i < 8; i++) {
					String cname = controllers[i].getName();
					String label;
					Color labelColor;

					if (cname.equals(name1)) {
						label = "[1P] " + cname;
						labelColor = Color.GREEN;
					} else if (has2P && cname.equals(name2)) {
						label = "[2P] " + cname;
						labelColor = Color.CYAN;
					} else {
						label = "     " + cname;
						labelColor = Color.GRAY;
					}
					titlefont.setColor(labelColor);
					titlefont.draw(sprite, label, 760 * scaleX, (yBase - i * 25) * scaleY);
				}
			}

			titlefont.setColor(Color.CYAN);
			titlefont.draw(sprite, "[7] Restore to Default (Keyboard)", 750 * scaleX, 150 * scaleY);
			titlefont.draw(sprite, "[8] Restore to Default (Controller)", 750 * scaleX, 120 * scaleY);
			titlefont.draw(sprite, "[9] Restore to Default (MIDI)", 750 * scaleX, 90 * scaleY);

			// 每次渲染时记录一次控制器状态（仅第一次）
			if (titlefont != null && Gdx.graphics.getFrameId() == 1) {
				Gdx.app.log("KeyConfig", "Render: controllers=" + controllerCount + ", input=" + (input != null));
				if (input != null) {
					Gdx.app.log("KeyConfig", "  input.getBMInputProcessor()=" + (input.getBMInputProcessor() != null ? input.getBMInputProcessor().length : "null"));
				}
			}
		}

		sprite.end();
		if (cursorpos < scrollpos) {
			scrollpos = cursorpos;
		} else if (cursorpos - scrollpos > 24) {
			scrollpos = cursorpos - 24;
		}
		for (int i = scrollpos; i < currentKeys.length; i++) {
			int y = 576 - (i - scrollpos) * 24;
			if (i == cursorpos) {
				shape.setProjectionMatrix(sprite.getProjectionMatrix());
				shape.begin(ShapeType.Filled);
				shape.setColor(keyinput ? Color.RED : Color.BLUE);
				shape.rect(200 * scaleX, y * scaleY, 80 * scaleX, 24 * scaleY);
				shape.rect(350 * scaleX, y * scaleY, 80 * scaleX, 24 * scaleY);
				shape.rect(650 * scaleX, y * scaleY, 80 * scaleX, 24 * scaleY);
				shape.end();
			}
			sprite.begin();
			if(titlefont != null) {
				titlefont.setColor(Color.WHITE);
				titlefont.draw(sprite, currentKeys[i], 50 * scaleX, (y + 22) * scaleY);
				titlefont.draw(sprite, getMouseScratchKeyString(currentKeysa[i], getKeyboardKeyAssign(currentKeysa[i]) != -1 ?
					getKeyboardKeyString(getKeyboardKeyAssign(currentKeysa[i])) : "----"), 202 * scaleX, (y + 22) * scaleY);
				titlefont.draw(sprite, getControllerKeyAssign(0, currentKeysa[i]) != -1
						? BMControllerInputProcessor.BMKeys.toString(getControllerKeyAssign(0, currentKeysa[i])) : "----",
						352 * scaleX, (y + 22) * scaleY);
				if (pc.getController().length > 1) {
					titlefont.draw(sprite, getControllerKeyAssign(1, currentKeysa[i]) != -1 ?
							BMControllerInputProcessor.BMKeys.toString(getControllerKeyAssign(1, currentKeysa[i])) : "----", 502 * scaleX,
							(y + 22) * scaleY);
				}
				titlefont.draw(sprite,
						getMidiKeyAssign(currentKeysa[i]) != null ? getMidiKeyAssign(currentKeysa[i]).toString() : "----",
						652 * scaleX, (y + 22) * scaleY);
			}
			sprite.end();
		}
	}

	public void setKeyAssignMode(final int index) {
		input.getKeyBoardInputProcesseor().setLastPressedKey(-1);
		input.getKeyBoardInputProcesseor().getMouseScratchInput().setLastMouseScratch(-1);
		for (BMControllerInputProcessor bmc : controllers) {
			bmc.setLastPressedButton(-1);
		}
		if (midiinput != null) {
			midiinput.clearLastPressedKey();
		}
		cursorpos = index;
		keyinput = true;
	}
	/**
	 * キーインデックスに対応するキーの文字列を返す
	 *
	 * @param index
	 * @return
	 */
	public String getKeyAssign(final int index) {
		if(index < 0 || index >= KEYSA[mode].length) {
			return "!!!";
		}
		int keyindex = KEYSA[mode][index];


		final int kbinput = getKeyboardKeyAssign(keyindex);
		if(kbinput != -1) {
			return Keys.toString(getKeyboardKeyAssign(keyindex));
		}

		final String mouseinput = getMouseScratchKeyString(keyindex, null);
		if(mouseinput != null) {
			return mouseinput;
		}

		final int controllerinput = getControllerKeyAssign(0, keyindex);
		if(controllerinput != -1) {
			return BMControllerInputProcessor.BMKeys.toString(controllerinput);
		}
		if (pc.getController().length > 1) {
			final int controllerinput2 = getControllerKeyAssign(1, keyindex);
			if(controllerinput2 != -1) {
				return BMControllerInputProcessor.BMKeys.toString(controllerinput2);
			}
		}

		final PlayModeConfig.MidiConfig.Input midiinput = getMidiKeyAssign(keyindex);
		if(midiinput != null) {
			return midiinput.toString();
		}
		return "---";
	}

	private void setMode(int mode) {
		this.mode = mode;
		currentKeys = KEYS[mode];
		currentKeysa = KEYSA[mode];
		config = main.getPlayerResource().getPlayerConfig();
		pc = config.getPlayConfig(MODE_HINT[mode]);
		keyboardConfig = pc.getKeyboardConfig();
		controllerConfigs = pc.getController();
		midiconfig = pc.getMidiConfig();

		// 各configのキーサイズ等が足りない場合は補充する
		validateKeyboardLength();
		validateControllerLength();
		validateMidiLength();

		if (cursorpos >= KEYS[mode].length) {
			cursorpos = 0;
		}
	}

	private int getKeyboardKeyAssign(int index) {
		if (index >= 0) {
			return keyboardConfig.getKeyAssign()[index];
		} else if (index == -1) {
			return keyboardConfig.getStart();
		} else if (index == -2) {
			return keyboardConfig.getSelect();
		}
		return 0;
	}

	private void setKeyboardKeyAssign(int index) {
		int newKey = keyboard.getLastPressedKey();
		// 保留原生按键(ControlKeys 中除数字外的)不可绑定
		if (keyboard.isReservedKey(newKey)) {
			return;
		}
		// SCR 槽位(F-SCR/R-SCR/WHEEL 等)支持多键 pack;普通键槽位仍按 upstream reset+direct set
		boolean isScratchSlot = isScratchKeySlot(index);
		if (isScratchSlot) {
			if (index >= 0) {
				keyboardConfig.getKeyAssign()[index] = packKey(keyboardConfig.getKeyAssign()[index], newKey);
			} else if (index == -1) {
				keyboardConfig.setStart(packKey(keyboardConfig.getStart(), newKey));
			} else if (index == -2) {
				keyboardConfig.setSelect(packKey(keyboardConfig.getSelect(), newKey));
			}
		} else {
			resetKeyAssign(index);
			if (index >= 0) {
				keyboardConfig.getKeyAssign()[index] = newKey;
			} else if (index == -1) {
				keyboardConfig.setStart(newKey);
			} else if (index == -2) {
				keyboardConfig.setSelect(newKey);
			}
		}
	}

	/**
	 * 当前模式 (mode) 下,index 对应的槽位是否是 SCR(F-SCR/R-SCR/WHEEL 等)。
	 * 用于让 SCR 槽位支持多键 pack,普通键槽位仍按 upstream reset+direct set。
	 * 判定依据是 KEYS 标签 — Mode.scratchKey 仅包含 F-SCR(5K/7K),
	 * 漏掉了 R-SCR 这种"反向/换手"槽位,所以直接看标签更稳。
	 */
	private boolean isScratchKeySlot(int index) {
		if (index < 0) return false; // START/SELECT 不参与 pack
		String label = KEYS[mode][index];
		if (label.contains("SCR") || label.contains("WHEEL")) {
			return true;
		}
		for (int sc : MODE_HINT[mode].scratchKey) {
			if (index == sc) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 将 next 键打包进 current(8 bits per slot,0xFF 表示该 slot 空)。
	 * current 为单键 (< 256) 时,按单键直接处理(高 3 字节视为空),
	 * 保证从默认绑定升级到多键绑定时不会丢失已有按键。
	 * 已包含 next 时取反 (= toggle off);已满 4 键时丢弃新键。
	 * 所有未占用的 slot 都必须显式写入 0xFF,否则 0 会被
	 * getKeyboardKeyString 误识别为按键 0 → 显示 "Unknown"。
	 */
	private int packKey(int current, int next) {
		if (next == -1) return current;
		int[] ids = new int[4];
		int count = 0;
		boolean found = false;
		if (current >= 0 && current < 256) {
			if (current == next) return -1;
			ids[0] = current;
			count = 1;
		} else {
			for (int j = 0; j < 4; j++) {
				int id = (current >> (j * 8)) & 0xFF;
				if (id == 0xFF) continue;
				if (id == next) { found = true; continue; }
				ids[count++] = id;
			}
		}
		int result = 0xFFFFFFFF;
		if (found) {
			if (count == 0) return -1;
			for (int j = 0; j < count; j++) {
				result = (result & ~(0xFF << (j * 8))) | (ids[j] << (j * 8));
			}
			return result;
		}
		if (count >= 4) return current;
		ids[count++] = next;
		for (int j = 0; j < count; j++) {
			result = (result & ~(0xFF << (j * 8))) | (ids[j] << (j * 8));
		}
		return result;
	}

	private String getKeyboardKeyString(int packed) {
		if (packed == -1) return "----";
		if (packed >= 0 && packed < 256) return Keys.toString(packed);
		StringBuilder sb = new StringBuilder();
		for (int j = 0; j < 4; j++) {
			int id = (packed >> (j * 8)) & 0xFF;
			if (id == 0xFF) continue;
			if (sb.length() > 0) sb.append(" / ");
			sb.append(Keys.toString(id));
		}
		return sb.length() == 0 ? "----" : sb.toString();
	}

	private String getMouseScratchKeyString(int index, String defaultKeyString) {
		String keyString = null;
		if (index >= 0) {
			keyString = keyboardConfig.getMouseScratchConfig().getKeyString(index);
		} else if (index == -1) {
			keyString = keyboardConfig.getMouseScratchConfig().getStartString();
		} else if (index == -2) {
			keyString = keyboardConfig.getMouseScratchConfig().getSelectString();
		}
		if (keyString == null) {
			return defaultKeyString;
		} else {
			return keyString;
		}
	}

	private void setMouseScratchKeyAssign(int index, KeyBoardInputProcesseor kbp) {
		resetKeyAssign(index);
		int lastMouseScratch = kbp.getMouseScratchInput().getLastMouseScratch();
		if (index >= 0) {
			keyboardConfig.getMouseScratchConfig().getKeyAssign()[index] = lastMouseScratch;
		} else if (index == -1) {
			keyboardConfig.getMouseScratchConfig().setStart(lastMouseScratch);
		} else if (index == -2) {
			keyboardConfig.getMouseScratchConfig().setSelect(lastMouseScratch);
		}
	}

	private int getControllerKeyAssign(int device, int index) {
		if (index >= 0) {
			return controllerConfigs[device].getKeyAssign()[index];
		} else if (index == -1) {
			return controllerConfigs[device].getStart();
		} else if (index == -2) {
			return controllerConfigs[device].getSelect();
		}
		return 0;
	}

	private void setControllerKeyAssign(int index, BMControllerInputProcessor bmc) {
		int cindex = -1;
		for (int i = 0; i < controllerConfigs.length; i++) {
			if (bmc.getName().equals(controllerConfigs[i].getName())) {
				cindex = i;
				break;
			}
		}
		if (cindex < 0) {
			return;
		}
		resetKeyAssign(index);
		int newBtn = bmc.getLastPressedButton();
		if (index >= 0) {
			controllerConfigs[cindex].getKeyAssign()[index] = newBtn;
		} else if (index == -1) {
			controllerConfigs[cindex].setStart(newBtn);
		} else if (index == -2) {
			controllerConfigs[cindex].setSelect(newBtn);
		}
		// 消费 lastPressedButton,防止下一帧 pollControllerNavShortcuts 把它当 nav 快捷键
		bmc.setLastPressedButton(-1);
	}

	private MidiConfig.Input getMidiKeyAssign(int index) {
		if (index >= 0) {
			return midiconfig.getKeyAssign(index);
		} else if (index == -1) {
			return midiconfig.getStart();
		} else if (index == -2) {
			return midiconfig.getSelect();
		}
		return new MidiConfig.Input();
	}

	private void resetKeyAssign(int index) {
		if (index >= 0) {
			keyboardConfig.getKeyAssign()[index] = -1;
			keyboardConfig.getMouseScratchConfig().getKeyAssign()[index] = -1;
			midiconfig.setKeyAssign(index, null);
			for (ControllerConfig cc : controllerConfigs) {
				cc.getKeyAssign()[index] = -1;
			}
		}
	}

	private void deleteKeyAssign(int index) {
		final int noAssign = -1;
		if (index >= 0) keyboardConfig.getKeyAssign()[index] = noAssign;
		if(index >= 0) {
			keyboardConfig.getMouseScratchConfig().getKeyAssign()[index] = noAssign;
			for (ControllerConfig cc : controllerConfigs) {
				cc.getKeyAssign()[index] = noAssign;
			}
			midiconfig.setKeyAssign(index, null);
		} else if (index == -1) {
			keyboardConfig.setStart(noAssign);
			keyboardConfig.getMouseScratchConfig().setStart(noAssign);
			for (int i = 0; i < controllerConfigs.length; i++) {
				controllerConfigs[i].setStart(noAssign);
			}
			midiconfig.setStart(null);
		} else if (index == -2) {
			keyboardConfig.setSelect(noAssign);
			keyboardConfig.getMouseScratchConfig().setSelect(noAssign);
			for (int i = 0; i < controllerConfigs.length; i++) {
				controllerConfigs[i].setSelect(noAssign);
			}
			midiconfig.setSelect(null);
		}
	}

	private void setMidiKeyAssign(int index) {
		if (midiinput == null) return;
		resetKeyAssign(index);
		if (index >= 0) {
			midiconfig.setKeyAssign(index, midiinput.getLastPressedKey());
		} else if (index == -1) {
			midiconfig.setStart(midiinput.getLastPressedKey());
		} else if (index == -2) {
			midiconfig.setSelect(midiinput.getLastPressedKey());
		}
	}

	private void validateKeyboardLength() {
		// 用 KEYSA 中最大的正槽位计算 maxKey,START(-1)/SELECT(-2) 忽略。
		int maxKey = 0;
		for (int key : KEYSA[mode]) {
			if (key > maxKey) {
				maxKey = key;
			}
		}
		if (keyboardConfig.getKeyAssign().length <= maxKey) {
			int[] keys = new int[maxKey + 1];
			for (int i = 0; i < keyboardConfig.getKeyAssign().length; i++) {
				keys[i] = keyboardConfig.getKeyAssign()[i];
			}
			keyboardConfig.setKeyAssign(keys);
		}
	}

	private void validateControllerLength() {
		int maxKey = 0;
		for (int key : KEYSA[mode]) {
			if (key > maxKey) {
				maxKey = key;
			}
		}
		for (ControllerConfig controllerConfig : controllerConfigs) {
			if (controllerConfig.getKeyAssign().length <= maxKey) {
				int[] keys = new int[maxKey + 1];
				for (int i = 0; i < controllerConfig.getKeyAssign().length; i++) {
					keys[i] = controllerConfig.getKeyAssign()[i];
				}
				controllerConfig.setKeyAssign(keys);
			}
		}
	}

	private void validateMidiLength() {
		// MIDI 也只承担 1P 槽位
		int maxKey = 0;
		for (int key : KEYSA[mode]) {
			if (key % 100 > maxKey) {
				maxKey = key % 100;
			}
		}
		if (midiconfig.getKeys().length <= maxKey) {
			MidiConfig.Input[] keys = new MidiConfig.Input[maxKey + 1];
			for (int i = 0; i < keys.length; i++) {
				keys[i] = i < midiconfig.getKeys().length ? midiconfig.getKeys()[i] : new MidiConfig.Input();
			}
			midiconfig.setKeys(keys);
		}
	}

	@Override
	public void dispose() {
		super.dispose();
		if (titlefont != null) {
			titlefont.dispose();
			titlefont = null;
		}
		if (shape != null) {
			shape.dispose();
			shape = null;
		}
	}
}
