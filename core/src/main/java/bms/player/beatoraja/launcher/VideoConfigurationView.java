package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;

/**
 * Android 适配版：彻底剥离 JavaFX 依赖
 * 桌面端视频/显示设置界面，保留空方法外壳防止报错
 */
public class VideoConfigurationView {

    public void initialize(URL location, ResourceBundle resources) {}

    public void update(Config config) {}

    public void updatePlayer(PlayerConfig player) {}

    public void commit(Config config) {}

    public void commitPlayer(PlayerConfig player) {}

    public void updateResolutions() {}
}
