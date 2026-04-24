package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.SkinConfig;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.SkinType;

/**
 * Android 适配版：彻底剥离 JavaFX 依赖
 * 桌面端皮肤配置界面在手机端暂不可用，保留空方法外壳防止外部调用报错
 */
public class SkinConfigurationView {

    public void initialize(URL location, ResourceBundle resources) {
        // 空实现
    }

    public SkinHeader getSelectedHeader() {
        return null;
    }

    public SkinConfig.Property getProperty() {
        return new SkinConfig.Property();
    }

    public SkinHeader[] getSkinHeader(SkinType mode) {
        return new SkinHeader[0];
    }

    public void changeSkinType() {
        // 空实现
    }

    public void updateSkinType(SkinType type) {
        // 空实现
    }

    public void commitSkinType() {
        // 空实现
    }

    public void update(Config config) {
        // 空实现
    }

    public void update(PlayerConfig player) {
        // 空实现
    }

    public void commit() {
        // 空实现
    }

    public void changeSkinHeader() {
        // 空实现
    }

    public void updateSkinHeader(SkinHeader header) {
        // 空实现
    }

    public void commitSkinHeader() {
        // 空实现
    }
}
