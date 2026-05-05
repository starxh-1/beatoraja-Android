package bms.player.beatoraja.launcher;

import bms.player.beatoraja.AudioConfig;

/**
 * Android 空实现：音频配置界面已迁移到 Compose
 */
public class AudioConfigurationView {
    private AudioConfig config;

    public void update(AudioConfig config) {
        this.config = config;
    }

    public void commit() {
        // Android 使用 Oboe，无需手动提交
    }
}