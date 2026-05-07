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
     * 设置全局的频谱提供者实例
     */
    void setAsGlobalProvider();
}
