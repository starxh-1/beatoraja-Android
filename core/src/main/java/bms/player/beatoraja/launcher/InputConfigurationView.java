package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;

import bms.player.beatoraja.PlayerConfig;

/**
 * Android 适配版：彻底剥离 JavaFX 依赖
 * 桌面端按键配置界面在手机端暂不可用，保留空方法外壳防止外部调用报错
 */
public class InputConfigurationView {

    private PlayerConfig player;
    private PlayConfigurationView.PlayMode mode;

    public void initialize(URL location, ResourceBundle resources) {
        // 空实现
    }

    public void changeMode() {
        // 空实现
    }

    public void update(PlayerConfig player) {
        this.player = player;
    }

    public void commit() {
        // 空实现
    }

    public void updateMode(PlayConfigurationView.PlayMode mode) {
        this.mode = mode;
    }

    public void commitMode() {
        // 空实现
    }
}
