package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;
import bms.player.beatoraja.PlayerConfig;

/**
 * Android 适配版：彻底剥离 JavaFX 和 AWT 依赖
 * 桌面端 IR 配置界面在手机端暂不可用，保留空方法外壳防止外部调用报错
 */
public class IRConfigurationView {

    private PlayerConfig player;

    public void initialize(URL arg0, ResourceBundle arg1) {
        // 空实现
    }

    public void update(PlayerConfig player) {
        this.player = player;
    }

    public void commit() {
        // 空实现
    }

    public void setPrimary() {
        // 空实现
    }

    public void updateIRConnection() {
        // 空实现
    }
}
