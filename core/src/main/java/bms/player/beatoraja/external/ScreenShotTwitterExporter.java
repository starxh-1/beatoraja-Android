package bms.player.beatoraja.external;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PlayerConfig;

/**
 * Android 适配版：彻底剥离 Twitter4J 依赖
 * 手机端建议后续使用 Android 原生 Share Intent 来调用系统分享菜单
 */
public class ScreenShotTwitterExporter implements ScreenShotExporter {

    public ScreenShotTwitterExporter(PlayerConfig player) {
        // 空实现
    }

    @Override
    public boolean send(MainState currentState, byte[] pixels) {
        // 直接返回 false，禁用原版硬编码的推特分享
        return false;
    }

}
