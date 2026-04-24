package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;

/**
 * Android 适配版：彻底剥离 JavaFX 依赖
 * 选曲界面配置在手机端暂不可用，保留空方法外壳防止外部调用报错
 */
public class MusicSelectConfigurationView {

    private Config config;
    private PlayerConfig player;

    public void initialize(URL arg0, ResourceBundle arg1) {
        // 空实现
    }

    public void update(Config config) {
        this.config = config;
    }

    public void commit() {
        // 空实现
    }

    public void updatePlayer(PlayerConfig player) {
        this.player = player;
    }

    public void commitPlayer() {
        // 空实现
    }
}
