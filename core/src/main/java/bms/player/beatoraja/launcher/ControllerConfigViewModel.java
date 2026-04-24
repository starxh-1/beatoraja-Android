package bms.player.beatoraja.launcher;

import bms.player.beatoraja.PlayModeConfig.ControllerConfig;

/**
 * ControllerConfig の ViewModel (Android 适配版：彻底剥离 JavaFX 依赖)
 */
public class ControllerConfigViewModel {
    // 1. 将 JavaFX 的 Property 替换为 Java 原生类型
    private String name;
    private boolean isAnalogScratch;
    private Integer analogScratchThreshold;
    private Integer analogScratchMode;

    private ControllerConfig config;

    public ControllerConfigViewModel(ControllerConfig config) {
        this.config = config;

        this.name = config.getName();
        this.isAnalogScratch = config.isAnalogScratch();
        this.analogScratchThreshold = config.getAnalogScratchThreshold();
        this.analogScratchMode = config.getAnalogScratchMode();
    }

    // 2. 保留常规的 Getter 和 Setter，删掉所有返回 Property 对象的方法
    public String getName() {
        return this.name;
    }

    public boolean getIsAnalogScratch() {
        return this.isAnalogScratch;
    }

    public void setIsAnalogScratch(boolean isAnalogScratch) {
        this.isAnalogScratch = isAnalogScratch;
    }

    public int getAnalogScratchThreshold() {
        return this.analogScratchThreshold;
    }

    public void setAnalogScratchThreshold(Integer analogScratchThreshold) {
        this.analogScratchThreshold = analogScratchThreshold;
    }

    public int getAnalogScratchMode() {
        return this.analogScratchMode;
    }

    public void setAnalogScratchMode(int analogScratchMode) {
        this.analogScratchMode = analogScratchMode;
    }

    public ControllerConfig getConfig() {
        return this.config;
    }
}
