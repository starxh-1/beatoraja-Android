package com.starxh.beatoraja;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

public class SideSpectrumRenderer {
    private ShapeRenderer shapeRenderer;
    private OrthographicCamera camera;
    private AudioSpectrumProvider spectrumProvider;

    private float[] spectrum = new float[64]; // 32 Left + 32 Right
    private float[] topValues = new float[64];
    private static final float FALLING_SPEED = 0.02f;
    private static final int BANDS_PER_CHANNEL = 64;
    private float testTimer = 0;

    // 游戏内区域渲染
    private boolean renderInGameArea = false;
    private boolean renderMono = false;
    private float gameAreaX = 0, gameAreaY = 0, gameAreaW = 320, gameAreaH = 80;
    private boolean hasValidGameArea = false;

    private int lastW = -1, lastH = -1;
    private boolean needsMatrixUpdate = true;

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

    /**
     * 设置是否在游戏内区域渲染频谱
     */
    public void setRenderInGameArea(boolean inGameArea) {
        this.renderInGameArea = inGameArea;
    }

    /**
     * 设置游戏内区域的坐标（逻辑分辨率1920x1080下的坐标）
     */
    public void setGameArea(float x, float y, float w, float h) {
        this.gameAreaX = x;
        this.gameAreaY = y;
        this.gameAreaW = w;
        this.gameAreaH = h;
        this.hasValidGameArea = (w > 0 && h > 0);
    }

    /**
     * 设置是否将左右声道合成为单声道渲染
     */
    public void setRenderMono(boolean mono) {
        this.renderMono = mono;
    }

    public void render() {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            needsMatrixUpdate = true;
        }

        testTimer += Gdx.graphics.getDeltaTime();

        // 1. 获取数据
        boolean hasRealData = false;
        if (spectrumProvider != null) {
            float[] latest = spectrumProvider.getSpectrumMagnitudes();
            if (latest != null && latest.length >= 64) {
                java.lang.System.arraycopy(latest, 0, spectrum, 0, 64);
                for (float v : spectrum) {
                    if (v > 0.0001f) {
                        hasRealData = true;
                        break;
                    }
                }
            }
        }

        // 2. 更新下落逻辑
        for (int i = 0; i < 64; i++) {
            if (spectrum[i] > topValues[i]) {
                topValues[i] = spectrum[i];
            } else {
                topValues[i] -= FALLING_SPEED;
                if (topValues[i] < 0) topValues[i] = 0;
            }
        }

        // 3. 渲染设置
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        if (renderInGameArea && hasValidGameArea) {
            // 计算游戏区域在屏幕上的位置（1920x1080坐标映射到实际屏幕）
            float gameHeight = h;
            float gameWidth = gameHeight * (1920f / 1080f);
            float gameLeft = (w - gameWidth) / 2f;

            // 计算spectrum区域在屏幕上的像素位置和大小
            float specScreenX = gameLeft + (gameAreaX / 1920f) * gameWidth;
            float specScreenW = (gameAreaW / 1920f) * gameWidth;
            float specScreenH = (gameAreaH / 1080f) * gameHeight;
            float specScreenY_bottom = gameAreaY;

            // 设置viewport和camera只覆盖spectrum区域
            Gdx.gl.glViewport((int) specScreenX, (int) specScreenY_bottom, (int) specScreenW, (int) specScreenH);
            if (needsMatrixUpdate) {
                camera.setToOrtho(false, (int) specScreenW, (int) specScreenH);
                camera.update();
                needsMatrixUpdate = false;
            }
            shapeRenderer.setProjectionMatrix(camera.combined);
            renderInGameArea(spectrum, topValues, hasRealData, specScreenW, specScreenH);
        } else {
            // 黑边区域渲染模式 - 使用屏幕坐标
            Gdx.gl.glViewport(0, 0, w, h);
            if (needsMatrixUpdate) {
                camera.setToOrtho(false, w, h);
                camera.update();
                needsMatrixUpdate = false;
            }
            shapeRenderer.setProjectionMatrix(camera.combined);
            renderInBlackBars(spectrum, topValues, w, h, hasRealData);
        }

        shapeRenderer.end();
    }

    // 游戏内区域渲染 - 横向32band频谱
    private void renderInGameArea(float[] spectrum, float[] topValues, boolean hasRealData, float screenW, float screenH) {
        Color barColor = hasRealData ? new Color(0.4f, 0.8f, 1f, 0.5f) : new Color(0.4f, 0.6f, 1f, 0.4f);

        // 32个频段，水平排列，填充整个区域
        float barW = screenW / 32f;
        float barThickness = barW * 0.8f;
        float baseY = 0;

        for (int i = 0; i < 32; i++) {
            float x = i * barW;
            // 合并左右声道取平均
            float monoVal = (spectrum[i] + spectrum[32 + i]) / 2f;
            float monoTop = (topValues[i] + topValues[32 + i]) / 2f;

            // 频谱值映射到高度
            float barHeight = monoVal * screenH * 0.9f;
            float topHeight = monoTop * screenH * 0.9f;
            barHeight = Math.min(barHeight, screenH - 2);
            topHeight = Math.min(topHeight, screenH - 2);

            // 底部填充
            shapeRenderer.setColor(barColor);
            shapeRenderer.rect(x + (barW - barThickness) / 2, baseY, barThickness, barHeight);

            // 顶部高亮
            if (topHeight > 2) {
                shapeRenderer.setColor(Color.WHITE);
                shapeRenderer.rect(x + (barW - barThickness) / 2, baseY + topHeight - 2, barThickness, 2);
            }
        }
    }

    // 黑边区域渲染
    private void renderInBlackBars(float[] spectrum, float[] topValues, int w, int h, boolean hasRealData) {
        float gameW = h * (1920f / 1080f);
        float blackBarW = (w - gameW) / 2f;
        float maxBarW = Math.min(w * 0.09f, blackBarW);

        float totalHeight = h * 0.8f;
        float barH = totalHeight / 32f;
        float barThickness = barH * 0.7f;
        Color barColor = hasRealData ? new Color(0.4f, 0.8f, 1f, 0.5f) : new Color(0.4f, 0.6f, 1f, 0.4f);

        float baseY = (h - totalHeight) / 2;
        for (int i = 1; i <= 31; i++) {
            float y = baseY + (32 - i) * barH;
            drawHorizontalBarLeft(i, y, maxBarW, barThickness, barColor, blackBarW);
            drawHorizontalBarRight(32 + i, y, maxBarW, barThickness, barColor, blackBarW);
        }
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
