package com.starxh.beatoraja.android;

import android.util.Log;
import barsoosayque.libgdxoboe.OboeAudio;
import com.starxh.beatoraja.AudioSpectrumManager;
import com.starxh.beatoraja.AudioSpectrumProvider;

/**
 * Adapter to expose OboeAudio's spectrum data via AudioSpectrumProvider interface.
 */
public class AudioSpectrumAdapter implements AudioSpectrumProvider {
    private static final String TAG = "AudioSpectrumAdapter";
    private OboeAudio oboeAudio;

    public AudioSpectrumAdapter(OboeAudio oboeAudio) {
        this.oboeAudio = oboeAudio;
        Log.i(TAG, "Adapter created with OboeAudio: " + (oboeAudio != null ? "valid" : "null"));
    }

    @Override
    public float[] getSpectrumMagnitudes() {
        if (oboeAudio != null) {
            float[] raw = oboeAudio.getSpectrumMagnitudes();
            if (raw != null && raw.length == 32) {
                // OboeAudio 提供 32 bands (16左+16右)，插值为 64 bands (32左+32右)
                float[] expanded = new float[64];
                for (int i = 0; i < 16; i++) {
                    expanded[i] = raw[i];
                    expanded[i + 16] = (raw[i] + raw[i + 1]) / 2f; // 插值中间值
                }
                for (int i = 0; i < 16; i++) {
                    expanded[32 + i] = raw[16 + i];
                    expanded[48 + i] = (raw[16 + i] + raw[16 + Math.min(i + 1, 15)]) / 2f;
                }
                return expanded;
            }
            return raw;
        }
        return new float[64];
    }

    @Override
    public void setAsGlobalProvider() {
        AudioSpectrumManager.setGlobalProvider(this);
        Log.i(TAG, "Set as global provider");
    }
}
