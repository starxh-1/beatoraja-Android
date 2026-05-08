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
    private static final float FALLING_SPEED = 0.02f;
    private static final int BANDS_PER_CHANNEL = 64;
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
                    if (v > 0.0001f) {
                        hasRealData = true;
                        break;
                    }
                }
            }
        }

        // 2. 更新下落逻辑（即使没有音频数据也继续渲染，让条形图逐渐归零）
        for (int i = 0; i < 64; i++) {
            if (spectrum[i] > topValues[i]) {
                topValues[i] = spectrum[i];
            } else {
                topValues[i] -= FALLING_SPEED;
                if (topValues[i] < 0) topValues[i] = 0;
            }
        }

        // 3. 渲染设置 - 使用全屏 viewport
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glViewport(0, 0, w, h);
        camera.setToOrtho(false, w, h);
        camera.update();
        shapeRenderer.setProjectionMatrix(camera.combined);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        // 计算黑边区域宽度，确保频谱不会进入游戏画面
        float gameW = h * (1920f / 1080f);
        float blackBarW = (w - gameW) / 2f;
        float maxBarW = Math.min(w * 0.09f, blackBarW); // 受黑边和屏幕宽度限制

        float totalHeight = h * 0.8f; // 频谱总高度（占屏幕比例）
        float barH = totalHeight / 32f; // 32条频谱
        float barThickness = barH * 0.7f; // bar 厚度
        Color barColor = hasRealData ? new Color(0.4f, 0.8f, 1f, 0.5f) : new Color(0.4f, 0.6f, 1f, 0.4f);

        // 绘制 - 左边显示左声道(0-31频段)，右边显示右声道(32-63频段)，条形横向延伸，居中显示
        float baseY = (h - totalHeight) / 2; // 垂直居中
        for (int i = 1; i < 32; i++) {
            float y = baseY + i * barH;
            drawHorizontalBarLeft(i, y, maxBarW, barThickness, barColor, blackBarW); // 左侧左声道，从右向左延伸
            drawHorizontalBarRight(32 + i, y, maxBarW, barThickness, barColor, blackBarW); // 右侧右声道，从左向右延伸
        }

        shapeRenderer.end();
    }

    // 左侧条形图 - 从右向左延伸，限制在黑边区域内
    private void drawHorizontalBarLeft(int idx, float y, float maxBarW, float barH, Color color, float blackBarW) {
        float val = spectrum[idx];
        float top = topValues[idx];
        float barW = Math.min(val * maxBarW, maxBarW); // 截断到最大宽度，防止出界
        float topX = Math.min(top * maxBarW, maxBarW);
        float anchorX = blackBarW; // 锚点在游戏边界，条形向左延伸到黑边

        shapeRenderer.setColor(color);
        shapeRenderer.rect(anchorX - barW, y, barW, barH - 2);
        shapeRenderer.setColor(Color.WHITE);
        shapeRenderer.rect(anchorX - topX - 4, y, 4, barH - 2);
    }

    // 右侧条形图 - 从左向右延伸，限制在黑边区域内
    private void drawHorizontalBarRight(int idx, float y, float maxBarW, float barH, Color color, float blackBarW) {
        float val = spectrum[idx];
        float top = topValues[idx];
        float barW = Math.min(val * maxBarW, maxBarW); // 截断到最大宽度，防止出界
        float topX = Math.min(top * maxBarW, maxBarW);
        float w = Gdx.graphics.getWidth();
        float anchorX = w - blackBarW; // 锚点在游戏边界，条形向右延伸到黑边

        shapeRenderer.setColor(color);
        shapeRenderer.rect(anchorX, y, barW, barH - 2);
        shapeRenderer.setColor(Color.WHITE);
        shapeRenderer.rect(anchorX + topX, y, 4, barH - 2);
    }

    public void dispose() {
        shapeRenderer.dispose();
    }
}
