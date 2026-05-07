package com.starxh.beatoraja;

/**
 * 全局频谱提供者管理器
 */
public class AudioSpectrumManager {
    private static volatile AudioSpectrumProvider globalProvider = null;

    public static void setGlobalProvider(AudioSpectrumProvider provider) {
        globalProvider = provider;
    }

    public static AudioSpectrumProvider getGlobalProvider() {
        return globalProvider;
    }
}
