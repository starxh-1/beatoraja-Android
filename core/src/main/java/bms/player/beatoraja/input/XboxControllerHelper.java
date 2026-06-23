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

    // Android libGDX 后端 remap 后的实测 button id（与 KeyEvent.KEYCODE_BUTTON_* 对应）
    public static final int ANDROID_BUTTON_A = 97;
    public static final int ANDROID_BUTTON_B = 98;
    public static final int ANDROID_BUTTON_X = 100;
    public static final int ANDROID_BUTTON_Y = 101;

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
