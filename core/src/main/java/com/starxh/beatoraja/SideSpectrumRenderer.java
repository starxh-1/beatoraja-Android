package com.starxh.beatoraja;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

public class SideSpectrumRenderer {
    public static final int MODE_SPECTRUM = 1;
    public static final int MODE_WAVEFORM = 2;

    private ShapeRenderer shapeRenderer;
    private OrthographicCamera camera;
    private AudioSpectrumProvider spectrumProvider;
    private int mode = MODE_SPECTRUM;

    private float[] spectrum = new float[64]; // 32 Left + 32 Right
    private float[] topValues = new float[64];
    private static final float FALLING_SPEED = 0.02f;
    private static final int BANDS_PER_CHANNEL = 64;

    private static final Color COLOR_BAR_ACTIVE = new Color(0.4f, 0.8f, 1f, 0.5f);
    private static final Color COLOR_BAR_INACTIVE = new Color(0.4f, 0.6f, 1f, 0.4f);
    private static final Color COLOR_LIVE_ACTIVE = new Color(0.4f, 0.8f, 1f, 0.9f);
    private static final Color COLOR_LIVE_INACTIVE = new Color(0.4f, 0.6f, 1f, 0.45f);
    private static final Color COLOR_BASELINE = new Color(0.5f, 0.5f, 0.5f, 0.3f);

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

    /**
     * Visualization mode: {@link #MODE_SPECTRUM} (default) or {@link #MODE_WAVEFORM}.
     */
    public void setMode(int mode) {
        this.mode = mode == MODE_WAVEFORM ? MODE_WAVEFORM : MODE_SPECTRUM;
    }

    public int getMode() {
        return mode;
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

        // 1. 获取数据（两种模式都共用频谱数组；波形模式只是把同一份数据画成另一种形状）
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

        // 2. 频谱下落逻辑（仅 spectrum 模式）
        if (mode == MODE_SPECTRUM) {
            for (int i = 0; i < 64; i++) {
                if (spectrum[i] > topValues[i]) {
                    topValues[i] = spectrum[i];
                } else {
                    topValues[i] -= FALLING_SPEED;
                    if (topValues[i] < 0) topValues[i] = 0;
                }
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
            float specScreenY_bottom = (gameAreaY / 1080f) * gameHeight;

            // 设置viewport和camera只覆盖spectrum区域
            Gdx.gl.glViewport((int) specScreenX, (int) specScreenY_bottom, (int) specScreenW, (int) specScreenH);
            if (needsMatrixUpdate) {
                camera.setToOrtho(false, (int) specScreenW, (int) specScreenH);
                camera.update();
                needsMatrixUpdate = false;
            }
            shapeRenderer.setProjectionMatrix(camera.combined);
            if (mode == MODE_WAVEFORM) {
                renderWaveformInGameArea(spectrum, hasRealData, specScreenW, specScreenH);
            } else {
                renderInGameArea(spectrum, topValues, hasRealData, specScreenW, specScreenH);
            }
        } else {
            // 黑边区域渲染模式 - 使用屏幕坐标
            Gdx.gl.glViewport(0, 0, w, h);
            if (needsMatrixUpdate) {
                camera.setToOrtho(false, w, h);
                camera.update();
                needsMatrixUpdate = false;
            }
            shapeRenderer.setProjectionMatrix(camera.combined);
            if (mode == MODE_WAVEFORM) {
                renderWaveformInBlackBars(spectrum, w, h, hasRealData);
            } else {
                renderInBlackBars(spectrum, topValues, w, h, hasRealData);
            }
        }

        shapeRenderer.end();
    }

    // 游戏内区域渲染 - 横向32band频谱
    private void renderInGameArea(float[] spectrum, float[] topValues, boolean hasRealData, float screenW, float screenH) {
        Color barColor = hasRealData ? COLOR_BAR_ACTIVE : COLOR_BAR_INACTIVE;

        // 32个频段，水平排列，填充整个区域
        float barW = screenW / 32f;
        float barThickness = barW * 0.8f;
        float baseY = 0;

        for (int i = 0; i < 32; i++) {
            float x = i * barW;
            // 合并左右声道取平均
            float monoVal = (spectrum[i] + spectrum[32 + i]) / 2f;
            float monoTop = (topValues[i] + topValues[32 + i]) / 2f;

            // 频谱值映射到高度（dB 缩放后值偏高，乘 0.5 让柱子更克制）
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

        // blackBarW < 0 表示屏幕比 16:9 更高（letterbox，上下黑边），使用垂直排列
        // blackBarW == 0 表示恰好 16:9，无黑边可显示
        if (blackBarW < 0) {
            renderVerticalInBlackBars(spectrum, topValues, w, h, hasRealData);
            return;
        }
        if (blackBarW == 0) {
            return;
        }

        float maxBarW = Math.min(w * 0.09f, blackBarW);

        float totalHeight = h * 0.8f;
        float barH = totalHeight / 32f;
        float barThickness = barH * 0.7f;
        Color barColor = hasRealData ? COLOR_BAR_ACTIVE : COLOR_BAR_INACTIVE;

        float baseY = (h - totalHeight) / 2;
        for (int i = 1; i <= 31; i++) {
            float y = baseY + (32 - i) * barH;
            drawHorizontalBarLeft(i, y, maxBarW, barThickness, barColor, blackBarW);
            drawHorizontalBarRight(32 + i, y, maxBarW, barThickness, barColor, blackBarW);
        }
    }

    // 垂直排列渲染（用于上下黑边的屏幕，如 4:3、5:4、3:2 等比 16:9 更"方"的屏）
    private void renderVerticalInBlackBars(float[] spectrum, float[] topValues, int w, int h, boolean hasRealData) {
        // 游戏区域是按宽度等比缩放的 16:9 矩形（letterbox）：viewportW = w, viewportH = w * 9/16
        // 上下黑边各占 (h - viewportH) / 2。
        float gameH = w * (1080f / 1920f);
        float topBarH = (h - gameH) / 2f;    // 上黑边高度
        float bottomBarH = topBarH;          // 下黑边高度

        if (topBarH <= 4 || bottomBarH <= 4) {
            return; // 没有足够空间，跳过
        }

        // 整组水平居中，占屏幕宽度的 75%。上黑边放左 32 频段，下黑边放右 32 频段。
        final int BANDS_PER_BAR = 32;
        float totalSpan = w * 0.5f;
        float barW = totalSpan / BANDS_PER_BAR;
        float barThickness = barW * 0.7f;
        float startX = (w - totalSpan) / 2f;
        Color barColor = hasRealData ? COLOR_BAR_ACTIVE : COLOR_BAR_INACTIVE;

        // 上黑边：左声道 32 频段，baseY 贴游戏区顶边，向上长
        for (int i = 0; i < BANDS_PER_BAR; i++) {
            float val = spectrum[i];
            float top = topValues[i];
            float x = startX + i * barW;

            float barHeight = Math.min(val * topBarH * 0.85f, topBarH - 4);
            float topHeight = Math.min(top * topBarH * 0.85f, topBarH - 4);
            float baseY = h - topBarH + 2;
            shapeRenderer.setColor(barColor);
            shapeRenderer.rect(x + (barW - barThickness) / 2, baseY, barThickness, barHeight);
            if (topHeight > 2) {
                shapeRenderer.setColor(Color.WHITE);
                shapeRenderer.rect(x + (barW - barThickness) / 2, baseY + topHeight - 2, barThickness, 2);
            }
        }

        // 下黑边：右声道 32 频段，顶端贴游戏区底边，向下长
        for (int i = 0; i < BANDS_PER_BAR; i++) {
            float val = spectrum[32 + i];
            float top = topValues[32 + i];
            float x = startX + i * barW;

            float barHeight = Math.min(val * bottomBarH * 0.85f, bottomBarH - 4);
            float topHeight = Math.min(top * bottomBarH * 0.85f, bottomBarH - 4);
            float topY = bottomBarH - 2;
            shapeRenderer.setColor(barColor);
            shapeRenderer.rect(x + (barW - barThickness) / 2, topY - barHeight, barThickness, barHeight);
            if (topHeight > 2) {
                shapeRenderer.setColor(Color.WHITE);
                shapeRenderer.rect(x + (barW - barThickness) / 2, topY - topHeight, barThickness, 2);
            }
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

    // 在游戏内区域渲染波形（频谱换成波形形状）— 64 个频段值连接成 polyline，以 0.5 为中线做上下偏移
    private void renderWaveformInGameArea(float[] spectrum, boolean hasRealData, float screenW, float screenH) {
        int n = 64; // 32 L + 32 R
        float midY = screenH * 0.5f;
        float halfH = screenH * 0.45f;
        Color live = hasRealData ? COLOR_LIVE_ACTIVE : COLOR_LIVE_INACTIVE;

        // Baseline
        shapeRenderer.setColor(COLOR_BASELINE);
        shapeRenderer.rect(0, midY - 0.5f, screenW, 1f);

        shapeRenderer.setColor(live);
        float prevX = 0;
        float prevY = midY;
        for (int i = 0; i < n; i++) {
            float x = (float) i / (n - 1) * screenW;
            // L/R 平均，sqrt 放大低幅值；以 0.5 为中线做偏移；幅度放大 3.5x 让波动更明显
            float avg = (spectrum[i] + spectrum[32 + i]) * 0.5f;
            float v = (float) Math.sqrt(avg) - 0.5f;
            if (v < -0.5f) v = -0.5f; else if (v > 0.5f) v = 0.5f;
            float y = midY - v * halfH * 3.5f;
            if (i > 0) shapeRenderer.rectLine(prevX, prevY, x, y, 2.5f);
            prevX = x; prevY = y;
        }
    }

    // 在黑边区域渲染波形（频谱换成波形形状）— Y = 频段序号、X = 幅度向外延伸，几何与频谱条带对齐
    private void renderWaveformInBlackBars(float[] spectrum, int w, int h, boolean hasRealData) {
        float gameW = h * (1920f / 1080f);
        float blackBarW = (w - gameW) / 2f;
        Color live = hasRealData ? COLOR_LIVE_ACTIVE : COLOR_LIVE_INACTIVE;

        if (blackBarW < 0) {
            float gameH = w * (1080f / 1920f);
            float topBarH = (h - gameH) / 2f;
            float bottomBarH = topBarH;
            if (topBarH > 4 && bottomBarH > 4) {
                int bins = 32;

                // 上黑边 - L 通道（从游戏区顶边向上延伸，与频谱对称）
                float baselineTop = h - topBarH;
                shapeRenderer.setColor(COLOR_BASELINE);
                shapeRenderer.rect(0, baselineTop - 0.5f, w, 1f);
                shapeRenderer.setColor(live);
                float prevX = 0, prevY = baselineTop;
                for (int b = 0; b < bins; b++) {
                    float x = (float) b / (bins - 1) * w;
                    float v = (float) Math.sqrt(spectrum[b]) - 0.5f;
                    if (v < -0.5f) v = -0.5f; else if (v > 0.5f) v = 0.5f;
                    float y = baselineTop - v * (topBarH * 1.8f);
                    if (b > 0) shapeRenderer.rectLine(prevX, prevY, x, y, 2.5f);
                    prevX = x; prevY = y;
                }

                // 下黑边 - R 通道（从游戏区底边向下延伸，与频谱对称）
                float baselineBottom = bottomBarH;
                shapeRenderer.setColor(COLOR_BASELINE);
                shapeRenderer.rect(0, baselineBottom - 0.5f, w, 1f);
                shapeRenderer.setColor(live);
                prevX = 0; prevY = baselineBottom;
                for (int b = 0; b < bins; b++) {
                    float x = (float) b / (bins - 1) * w;
                    float v = (float) Math.sqrt(spectrum[32 + b]) - 0.5f;
                    if (v < -0.5f) v = -0.5f; else if (v > 0.5f) v = 0.5f;
                    float y = baselineBottom + v * (bottomBarH * 1.8f);
                    if (b > 0) shapeRenderer.rectLine(prevX, prevY, x, y, 2.5f);
                    prevX = x; prevY = y;
                }
            }
            return;
        }

        if (blackBarW == 0) return;

        // 横屏（侧黑边）：瀑布布局 — Y = 频段序号（0 在底，bins-1 在顶），
        // X = 幅度从游戏边界向外延伸。totalHeight / maxBarW 与频谱常量完全相同。
        int bins = 32; // 频谱每通道 32 段，直接连成线段
        float totalHeight = h * 0.8f;
        float baseY = (h - totalHeight) / 2f;
        float maxBarW = Math.min(w * 0.09f, blackBarW);
        float ampScale = 1.0f; // 与 spectrum 一致：x 方向不超过 maxBarW；靠 sqrt 放大低幅值来体现"明显"
        float anchorL = blackBarW;       // 左侧游戏边界
        float anchorR = w - blackBarW;   // 右侧游戏边界
        Color baseColor = COLOR_BASELINE;

        // 边界中线（与频谱条带共享同一根锚轴）
        shapeRenderer.setColor(baseColor);
        shapeRenderer.rect(anchorL - 0.5f, baseY, 1f, totalHeight);
        shapeRenderer.rect(anchorR - 0.5f, baseY, 1f, totalHeight);

        shapeRenderer.setColor(live);

        // L 通道 — 幅度向左（外侧）延伸
        float prevX = anchorL, prevY = baseY + totalHeight;
        for (int b = 0; b < bins; b++) {
            float v = (float) Math.sqrt(spectrum[b]);
            float y = baseY + (float) (bins - 1 - b) / (bins - 1) * totalHeight;
            float x = anchorL - v * maxBarW * ampScale;
            if (b > 0) shapeRenderer.rectLine(prevX, prevY, x, y, 1.5f);
            prevX = x; prevY = y;
        }

        // R 通道 — 幅度向右（外侧）延伸
        prevX = anchorR; prevY = baseY + totalHeight;
        for (int b = 0; b < bins; b++) {
            float v = (float) Math.sqrt(spectrum[32 + b]);
            float y = baseY + (float) (bins - 1 - b) / (bins - 1) * totalHeight;
            float x = anchorR + v * maxBarW * ampScale;
            if (b > 0) shapeRenderer.rectLine(prevX, prevY, x, y, 1.5f);
            prevX = x; prevY = y;
        }
    }

    public void dispose() {
        shapeRenderer.dispose();
    }
}
