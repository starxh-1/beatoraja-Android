package com.starxh.beatoraja;

/**
 * 通用的频谱数据提供接口，用于解耦核心层与平台实现层
 */
public interface AudioSpectrumProvider {
    /**
     * 获取最新的频谱数据（32频段）
     */
    float[] getSpectrumMagnitudes();

    /**
     * 获取最近一个 FFT 窗口的原始波形采样（交错 L/R，范围 [-1, 1]）。
     * 长度为 FFT_SIZE * 2；可通过 samples[i*2] / samples[i*2+1] 读取 L/R 通道。
     */
    float[] getWaveformSamples();

    /**
     * 设置全局的频谱提供者实例
     */
    void setAsGlobalProvider();
}
