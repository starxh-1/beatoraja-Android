package bms.player.beatoraja.launcher;

import bms.player.beatoraja.AudioConfig;
// 彻底删除了所有 javafx.* 和 com.portaudio.* 的引用

/**
 * Android 适配版：阉割掉 JavaFX 桌面 UI 和 PortAudio 驱动
 * 仅保留空方法以防其他类调用报错
 */
public class AudioConfigurationView {

    private AudioConfig config;

    // 移除了 initialize 方法，因为 Android 不需要 Initializable 接口

    public void update(AudioConfig config) {
        this.config = config;
        // 移除了所有 UI 控件的赋值逻辑
    }

    public void commit() {
        if (config != null) {
            // 默认强制在 Android 上使用 OpenAL
            config.setDriver(AudioConfig.DriverType.OpenAL);
        }
        // 移除了从 UI 读取数值的逻辑
    }

    public void updateAudioDriver() {
        // 空实现
    }
}
