package bms.player.beatoraja.input;

import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.utils.Array;

import java.util.logging.Logger;

/**
 * XBOX控制器辅助类 - 提供XBOX手柄的按键映射和初始化支持
 *
 * 此类用于帮助检测和配置XBOX手柄，提供常用的XBOX手柄按键常量
 * 并在日志中输出控制器信息以便调试
 */
public class XboxControllerHelper {

    private static final String TAG = "XboxControllerHelper";

    // XBOX手柄按钮代码 (libGDX标准映射)
    public static final int XBOX_BUTTON_A = 0;
    public static final int XBOX_BUTTON_B = 1;
    public static final int XBOX_BUTTON_X = 2;
    public static final int XBOX_BUTTON_Y = 3;
    public static final int XBOX_BUTTON_LB = 4;
    public static final int XBOX_BUTTON_RB = 5;
    public static final int XBOX_BUTTON_BACK = 6;
    public static final int XBOX_BUTTON_START = 7;
    public static final int XBOX_BUTTON_L3 = 8;
    public static final int XBOX_BUTTON_R3 = 9;

    // Android のボタン id は、libGDX が「Android の KeyEvent keycode をそのまま」使う。
    // 根拠（gdx-controllers-android 2.2.4 ソース）:
    //   AndroidControllers#onKey() が `event.code = keyCode`（KeyEvent の keycode をそのまま）で積み、
    //   AndroidController#getButton(i) は `buttons.containsKey(i)` を返すだけ = 独自 remap なし。
    //   getMaxButtonIndex() のコメントも "see Android's KeyEvent class"。
    // ⇒ 値は KeyEvent.KEYCODE_* と**完全一致**させること。
    //    ⚠️ 旧値は全て +1 ずれていた（A=97/B=98/X=100/Y=101）。97 は KEYCODE_BUTTON_B なので、
    //       実機では A を押しても無反応・**B が Enter** になっていた（2026-10-13 修正）。
    public static final int ANDROID_BUTTON_A = 96;   // KeyEvent.KEYCODE_BUTTON_A
    public static final int ANDROID_BUTTON_B = 97;   // KeyEvent.KEYCODE_BUTTON_B
    public static final int ANDROID_BUTTON_X = 99;   // KeyEvent.KEYCODE_BUTTON_X
    public static final int ANDROID_BUTTON_Y = 100;  // KeyEvent.KEYCODE_BUTTON_Y
    public static final int ANDROID_BUTTON_L3 = 106; // KeyEvent.KEYCODE_BUTTON_THUMBL
    public static final int ANDROID_BUTTON_R3 = 107; // KeyEvent.KEYCODE_BUTTON_THUMBR

    // Android の D-pad も同じく KeyEvent keycode（HAT 軸から合成される経路も同じ値を使う）。
    // ⚠️ 旧値 20/21/22/23 は 1 つずれていた（D-pad 上=無反応、下押しが「上」扱い…の回転）。
    public static final int ANDROID_DPAD_UP = 19;    // KeyEvent.KEYCODE_DPAD_UP
    public static final int ANDROID_DPAD_DOWN = 20;  // KeyEvent.KEYCODE_DPAD_DOWN
    public static final int ANDROID_DPAD_LEFT = 21;  // KeyEvent.KEYCODE_DPAD_LEFT
    public static final int ANDROID_DPAD_RIGHT = 22; // KeyEvent.KEYCODE_DPAD_RIGHT

    // XBOX手柄轴代码
    public static final int XBOX_AXIS_LEFT_X = 0;
    public static final int XBOX_AXIS_LEFT_Y = 1;
    public static final int XBOX_AXIS_RIGHT_X = 3;
    public static final int XBOX_AXIS_RIGHT_Y = 4;
    public static final int XBOX_AXIS_LT = 2;
    public static final int XBOX_AXIS_RT = 5;

    /**
     * 检测是否存在XBOX控制器
     * @return 如果检测到XBOX类手柄返回true
     */
    public static boolean hasXboxController() {
        for (Controller controller : Controllers.getControllers()) {
            if (isXboxController(controller.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取XBOX控制器列表
     * @return XBOX控制器数组
     */
    public static Controller[] getXboxControllers() {
        Array<Controller> xboxControllers = new Array<>();
        for (Controller controller : Controllers.getControllers()) {
            if (isXboxController(controller.getName())) {
                xboxControllers.add(controller);
            }
        }
        return xboxControllers.toArray(Controller.class);
    }

    /**
     * 判断是否为XBOX控制器
     * @param name 控制器名称
     * @return 如果是XBOX类手柄返回true
     */
    public static boolean isXboxController(String name) {
        if (name == null) return false;
        String upperName = name.toUpperCase();
        return upperName.contains("XBOX") ||
               upperName.contains("XINPUT") ||
               upperName.contains("GAMEPAD") ||
               upperName.contains("CONTROLLER") ||
               upperName.contains("JOYSTICK") ||
               upperName.contains("BEITONG") ||
               upperName.contains("PLAYSTATION");
    }

    /**
     * 打印所有已连接的控制器信息（用于调试）
     */
    public static void printConnectedControllers() {
        Logger.getGlobal().info("=== Connected Controllers ===");
        for (Controller controller : Controllers.getControllers()) {
            Logger.getGlobal().info("Controller: " + controller.getName());
            // 测试按钮0-15来检测按钮数量
            int buttonCount = 0;
            for (int i = 0; i < 32; i++) {
                try {
                    controller.getButton(i);
                    buttonCount = i + 1;
                } catch (Exception e) {
                    break;
                }
            }
            Logger.getGlobal().info("  - Buttons: at least " + buttonCount);

            // 测试轴0-7来检测轴数量
            int axisCount = 0;
            for (int i = 0; i < 8; i++) {
                try {
                    controller.getAxis(i);
                    axisCount = i + 1;
                } catch (Exception e) {
                    break;
                }
            }
            Logger.getGlobal().info("  - Axes: at least " + axisCount);
        }
        Logger.getGlobal().info("===========================");
    }
}
