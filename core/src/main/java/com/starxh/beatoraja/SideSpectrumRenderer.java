package com.starxh.beatoraja;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;

public class SideSpectrumRenderer {
    private ShapeRenderer shapeRenderer;
    private OrthographicCamera camera;
    private AudioSpectrumProvider spectrumProvider;

    private float[] spectrum = new float[64]; // 32 Left + 32 Right
    private float[] topValues = new float[64];
    private static final float FALLING_SPEED = 0.015f;
    private static final int BANDS_PER_CHANNEL = 32;
    private float testTimer = 0;

    public SideSpectrumRenderer() {
        shapeRenderer = new ShapeRenderer();
        camera = new OrthographicCamera();

        // 优先使用 AudioSpectrumManager 的全局提供者
        spectrumProvider = AudioSpectrumManager.getGlobalProvider();
        // 如果没有设置全局提供者，尝试直接从 Gdx.audio 获取（兼容旧代码）
        if (spectrumProvider == null && Gdx.audio instanceof AudioSpectrumProvider) {
            spectrumProvider = (AudioSpectrumProvider) Gdx.audio;
        }
    }

    public void resize(int width, int height) {
        camera.setToOrtho(false, width, height);
        camera.update();
    }

    public void render() {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();
        testTimer += Gdx.graphics.getDeltaTime();

        // 1. 获取数据
        boolean hasRealData = false;
        if (spectrumProvider != null) {
            float[] latest = spectrumProvider.getSpectrumMagnitudes();
            if (latest != null && latest.length >= 64) {
                System.arraycopy(latest, 0, spectrum, 0, 64);
                for (float v : spectrum) {
                    if (v > 0.005f) {
                        hasRealData = true;
                        break;
                    }
                }
            }
        }

        // 2. 如果没真实数据，生成一段测试波浪 (双声道不同步测试)
        if (!hasRealData) {
            for (int i = 0; i < 32; i++) {
                spectrum[i] = (MathUtils.sin(testTimer * 3 + i * 0.3f) + 1) * 0.2f; // 左声道波浪
                spectrum[32 + i] = (MathUtils.cos(testTimer * 2 + i * 0.4f) + 1) * 0.2f; // 右声道波浪
            }
        }

        // 3. 更新下落逻辑
        for (int i = 0; i < 64; i++) {
            if (spectrum[i] > topValues[i]) {
                topValues[i] = spectrum[i];
            } else {
                topValues[i] -= FALLING_SPEED;
                if (topValues[i] < 0) topValues[i] = 0;
            }
        }

        // 4. 渲染设置
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        camera.setToOrtho(false, w, h);
        camera.update();
        shapeRenderer.setProjectionMatrix(camera.combined);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        float gameW = h * (1920f / 1080f);
        float blackBarW = (w - gameW) / 2f;
        float drawW = Math.max(blackBarW, 120f);

        float barW = (drawW - 10) / 16f;
        Color barColor = hasRealData ? new Color(0.2f, 0.8f, 0.4f, 0.5f) : new Color(0.2f, 0.4f, 1f, 0.4f);

        // 绘制 - 左边显示左声道(0-15频段)，右边显示右声道(32-47频段)
        for (int i = 0; i < 16; i++) {
            drawBar(i, i * barW, h, barW, barColor); // 左侧黑边
            drawBar(32 + i, w - drawW + i * barW, h, barW, barColor); // 右侧黑边
        }

        shapeRenderer.end();
    }

    private void drawBar(int idx, float x, float h, float barW, Color color) {
        float val = spectrum[idx];
        float top = topValues[idx];
        float maxBarH = h * 0.5f; // 最大高度占屏幕一半
        float barH = val * maxBarH;
        float topY = top * maxBarH;
        float baseY = (h - maxBarH) / 2; // 居中垂直位置

        shapeRenderer.setColor(color);
        shapeRenderer.rect(x, baseY, barW - 2, barH);
        shapeRenderer.setColor(Color.WHITE);
        shapeRenderer.rect(x, baseY + topY, barW - 2, 4);
    }

    public void dispose() {
        shapeRenderer.dispose();
    }
}
