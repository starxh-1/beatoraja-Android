package bms.player.beatoraja;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;

import bms.player.beatoraja.input.KeyBoardInputProcesseor;

/**
 * Android 用浮动快捷键菜单。
 * <p>
 * 右上角显示一个可点击展开的浮动图标，展开后显示一组快捷键按钮。
 * 所有坐标基于逻辑分辨率，使用 MainController 的 SpriteBatch 渲染。
 * 按键模拟通过 {@link KeyBoardInputProcesseor#simulateKeyPress(int)} 实现。
 * 实现 {@link InputProcessor} 以拦截触摸事件，阻止穿透到底层游戏。
 */
public class FloatingMenu implements InputProcessor {

    // ─── 逻辑分辨率（由 MainController 通过 setViewport 同步）───
    private int logicW = 1920;
    private int logicH = 1080;

    // ─── 视口参数（由 MainController 每帧更新，用于触摸坐标转换）───
    private int vpX, vpY, vpW, vpH;

    // ─── 浮动图标 ───
    private static final float ICON_SIZE = 77;           // 增大 10%
    private static final float ICON_MARGIN = 12;
    private float iconX, iconY;                          // 左下角坐标（逻辑坐标）

    // ─── 菜单面板 ───
    private static final float BTN_W = 209;              // 缩小 30%
    private static final float BTN_H = 80;               // 缩小 30%
    private static final float BTN_GAP = 10;             // 缩小 30%
    private static final float PANEL_PAD = 16;           // 缩小 30%

    private boolean expanded = false;
    private boolean visible = true;                     // PLAY 状态时隐藏

    // ─── 分页 ───
    private static final int ITEMS_PER_PAGE = 10;
    private int currentPage = 0;

    // ─── 纹理 ───
    private Texture iconTexture;
    private Texture whitePixel;
    private BitmapFont font; // 用于 hitTestPanel 计算文字宽度

    // ─── 按钮定义 ───
    private static class MenuItem {
        String label;
        final int keycode;
        final boolean isToggle;
        MenuItem(String label, int keycode) { this(label, keycode, false); }
        MenuItem(String label, int keycode, boolean isToggle) { this.label = label; this.keycode = keycode; this.isToggle = isToggle; }
    }

    private final MenuItem[] items = {
        new MenuItem("Touch Key: ON",  -100, true), // 特殊 keycode 用于标识切换触摸按键
        new MenuItem("Audio Spectrum: ON", -101, true), // 特殊 keycode 用于标识切换频谱显示
        new MenuItem("F1 Show FPS",      Keys.F1),
        new MenuItem("F2 Update Song",     Keys.F2),
        new MenuItem("F12 Skin Select",    Keys.F12),
        new MenuItem("NUM 6 Key Config",    Keys.NUM_6),
        new MenuItem("Backspace",     Keys.BACKSPACE),
        new MenuItem("ESC",         Keys.ESCAPE),
        new MenuItem("Enter",       Keys.ENTER),
        new MenuItem("^ UP",        Keys.UP),
        new MenuItem("v DOWN",      Keys.DOWN),
        new MenuItem("< LEFT",      Keys.LEFT),
        new MenuItem("> RIGHT",     Keys.RIGHT),
        new MenuItem("NUM 8 Controller Reset",    Keys.NUM_8),
        new MenuItem("NUM 2 Controller Reset",    Keys.NUM_2),
    };

    // ─── 触摸与反馈状态 ───
    private float lastTouchX = 0, lastTouchY = 0;
    /** 当前触摸是否被菜单消费（阻止穿透到游戏层） */
    private boolean consumingTouch = false;
    /** 当前手指正按下的按钮索引 */
    private int pressedIndex = -1;
    /** 每个按钮点击后的临时高亮计时器 */
    private final float[] flashTimers = new float[items.length];
    private static final float FLASH_DURATION = 0f; // 亮起常驻

    // ─── 引用 ───
    private KeyBoardInputProcesseor kbInput;

    public FloatingMenu() {
        updateIconPosition();
        createTextures();
    }

    /** 提供键盘输入处理器引用（用于 simulateKeyPress） */
    public void setKeyboardInput(KeyBoardInputProcesseor kb) {
        this.kbInput = kb;
        // 初始化 Touch Key 按钮状态
        Object mc = kb.getMainController();
        if (mc instanceof MainController) {
            Config config = ((MainController) mc).getConfig();
            if (config != null) {
                items[0].label = "Touch Key: " + (config.isShowTouchKey() ? "ON" : "OFF");
                items[1].label = "Audio Spectrum: " + (config.isShowAudioSpectrum() ? "ON" : "OFF");
            }
        }
    }

    /**
     * 每帧由 MainController 调用，同步视口参数。
     * 确保触摸坐标转换与游戏实际视口一致。
     */
    public void setViewport(int vpX, int vpY, int vpW, int vpH, int logicW, int logicH) {
        this.vpX = vpX;
        this.vpY = vpY;
        this.vpW = vpW;
        this.vpH = vpH;
        if (this.logicW != logicW || this.logicH != logicH) {
            this.logicW = logicW;
            this.logicH = logicH;
            updateIconPosition();
        }
    }

    private void updateIconPosition() {
        iconX = logicW - ICON_SIZE - ICON_MARGIN;
        iconY = logicH - ICON_SIZE - ICON_MARGIN;
    }

    /** PLAY 状态时调用 setVisible(false) 隐藏 */
    public void setVisible(boolean v) {
        this.visible = v;
        // 移除 if (!v) expanded = false; 以保持展开状态
    }

    public boolean isVisible() { return visible; }

    // ─────────────────── 纹理创建 ───────────────────

    private void createTextures() {
        int s = 96;
        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0);
        pm.fill();
        // 半透明深色圆角背景
        pm.setColor(0.15f, 0.15f, 0.2f, 0.65f);
        int cr = 14;
        pm.fillCircle(cr, cr, cr);
        pm.fillCircle(s - 1 - cr, cr, cr);
        pm.fillCircle(cr, s - 1 - cr, cr);
        pm.fillCircle(s - 1 - cr, s - 1 - cr, cr);
        pm.fillRectangle(cr, 0, s - 2 * cr, s);
        pm.fillRectangle(0, cr, s, s - 2 * cr);
        // 三条白色横杠
        pm.setColor(1, 1, 1, 0.9f);
        int barH = 7;
        int barW = s * 55 / 100;
        int barX = (s - barW) / 2;
        int gap = 16;
        int cy = s / 2;
        pm.fillRectangle(barX, cy - barH / 2, barW, barH);
        pm.fillRectangle(barX, cy - gap - barH - barH / 2, barW, barH);
        pm.fillRectangle(barX, cy + gap + barH / 2, barW, barH);
        iconTexture = new Texture(pm);
        pm.dispose();

        // 1×1 白色像素
        Pixmap wp = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        wp.setColor(1, 1, 1, 1);
        wp.fill();
        whitePixel = new Texture(wp);
        wp.dispose();
    }

    /** GL 上下文恢复后重建纹理 */
    public void rebuildTextures() {
        dispose();
        createTextures();
    }

    // ─────────────────── 渲染 ───────────────────

    /**
     * 每帧调用：绘制。
     * 触摸处理已通过 InputProcessor 事件驱动实现。
     *
     * @param sprite MainController 的 SpriteBatch
     * @param font   systemfont（24pt），用于按钮文字
     */
    public void render(SpriteBatch sprite, BitmapFont font) {
        this.font = font; // 保存 font 供 hitTestPanel 使用
        if (!visible) return;

        // 更新闪烁计时器
        float delta = Gdx.graphics.getDeltaTime();
        for (int i = 0; i < flashTimers.length; i++) {
            if (flashTimers[i] > 0) flashTimers[i] -= delta;
        }

        // ─── 设置投影矩阵到逻辑坐标 ───
        sprite.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, logicW, logicH));

        sprite.begin();

        // 绘制浮动图标
        sprite.setColor(1, 1, 1, 0.55f);
        sprite.draw(iconTexture, iconX, iconY, ICON_SIZE, ICON_SIZE);

        // 如果展开，绘制面板
        if (expanded && font != null) {
            drawPanel(sprite, font);
        }

        sprite.setColor(1, 1, 1, 1);  // 重置颜色
        sprite.end();
    }

    private void drawPanel(SpriteBatch sprite, BitmapFont font) {
        int cols = 2;
        // 临时计算用于确定实际显示的行数
        int tempRows = (ITEMS_PER_PAGE + cols - 1) / cols;
        float panelW = cols * BTN_W + (cols - 1) * BTN_GAP + PANEL_PAD * 2;

        // 计算当前页显示的按钮
        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, items.length);
        int itemCount = endIdx - startIdx;
        int actualRows = (itemCount + cols - 1) / cols;

        float panelH = actualRows * BTN_H + (actualRows - 1) * BTN_GAP + PANEL_PAD * 2 + 40; // 底部留40像素给翻页按钮

        // 面板位于图标左下方
        float panelX = iconX + ICON_SIZE - panelW;
        float panelY = iconY - panelH - 4;

        // 面板背景
        sprite.setColor(0.1f, 0.1f, 0.15f, 0.85f);
        sprite.draw(whitePixel, panelX, panelY, panelW, panelH);

        // 面板边框
        sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
        float border = 2;
        sprite.draw(whitePixel, panelX, panelY, panelW, border);                         // bottom
        sprite.draw(whitePixel, panelX, panelY + panelH - border, panelW, border);       // top
        sprite.draw(whitePixel, panelX, panelY, border, panelH);                         // left
        sprite.draw(whitePixel, panelX + panelW - border, panelY, border, panelH);       // right

        // 按钮（从顶部开始布局，底部预留40像素给翻页按钮）
        GlyphLayout layout = new GlyphLayout();
        for (int i = startIdx; i < endIdx; i++) {
            int localIdx = i - startIdx;
            int col = localIdx % cols;
            int row = localIdx / cols;
            float bx = panelX + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = panelY + PANEL_PAD + row * (BTN_H + BTN_GAP); // 从顶部开始

            // 按钮背景颜色计算：按下或正在闪烁时变亮
            if (i == pressedIndex || flashTimers[i] > 0) {
                // 亮蓝色反馈
                sprite.setColor(0.4f, 0.6f, 1.0f, 0.9f);
            } else {
                // 默认深色
                sprite.setColor(0.25f, 0.3f, 0.4f, 0.8f);
            }
            sprite.draw(whitePixel, bx, by, BTN_W, BTN_H);

            // 文字居中
            font.setColor(1, 1, 1, 0.95f);
            layout.setText(font, items[i].label);
            float tx = bx + (BTN_W - layout.width) / 2;
            float ty = by + (BTN_H + layout.height) / 2;
            font.draw(sprite, items[i].label, tx, ty);
        }

        // 绘制分页指示器和翻页按钮
        int totalPages = (items.length + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (totalPages > 1) {
            // 绘制在面板最底部
            float bottomY = panelY + PANEL_PAD;
            String pageText = (currentPage + 1) + "/" + totalPages;
            font.setColor(0.7f, 0.7f, 0.7f, 0.9f);
            layout.setText(font, pageText);
            float px = panelX + PANEL_PAD;
            float py = bottomY + layout.height;
            font.draw(sprite, pageText, px, py);

            // 绘制左右翻页箭头
            if (currentPage > 0) {
                font.setColor(0.5f, 0.8f, 1f, 0.9f);
                font.draw(sprite, "<", panelX + PANEL_PAD, py);
            }
            if (currentPage < totalPages - 1) {
                String rightArrow = ">";
                layout.setText(font, rightArrow);
                float arrowX = panelX + panelW - PANEL_PAD - layout.width;
                font.draw(sprite, rightArrow, arrowX, py);
            }
        }
    }

    // ─────────────────── InputProcessor 事件驱动触摸处理 ───────────────────

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!visible) return false;

        float tx = screenToLogicX(screenX);
        float ty = screenToLogicY(screenY);
        lastTouchX = screenX;
        lastTouchY = screenY;

        if (expanded) {
            // 展开状态：检查是否点击了图标（关闭菜单）
            if (hitTestIcon(tx, ty)) {
                expanded = false;
                consumingTouch = true;
                return true;
            }
            // 检查是否点到了按钮
            int hit = hitTestPanel(tx, ty);
            if (hit >= 0) {
                pressedIndex = hit;
                pressButton(hit); // 按下时立即触发
                consumingTouch = true;
                return true;
            }
            // 处理翻页
            if (hit == -3) {
                // 上一页
                if (currentPage > 0) currentPage--;
                consumingTouch = true;
                return true;
            }
            if (hit == -4) {
                // 下一页
                int totalPages = (items.length + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
                if (currentPage < totalPages - 1) currentPage++;
                consumingTouch = true;
                return true;
            }
            // 面板区域内空白 → 消费事件
            if (hit == -1) {
                consumingTouch = true;
                return true;
            }
            // 面板外点击
            consumingTouch = true;
            return true;
        } else {
            // 收起状态：检查是否点击了图标
            if (hitTestIcon(tx, ty)) {
                expanded = true;
                consumingTouch = true;
                return true;  // 消耗事件
            }
            // 没有点击图标，不拦截
            return false;
        }
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (!visible) {
            consumingTouch = false;
            Gdx.app.log("FloatingMenu", "touchUp: not visible, returning false");
            return false;
        }

        float tx = screenToLogicX(screenX);
        float ty = screenToLogicY(screenY);

        // 如果我们正在消费这个触摸事件，就一直消费到结束
        if (consumingTouch) {
            // 检查是否抬起了手指在按钮上
            if (expanded) {
                if (pressedIndex >= 0) {
                    releaseButton(pressedIndex);
                    flashTimers[pressedIndex] = FLASH_DURATION;
                }

                // 如果手指在图标位置抬起，视为单纯的展开/收起操作
                if (hitTestIcon(tx, ty)) {
                    expanded = !expanded;  // 切换展开状态
                    Gdx.app.log("FloatingMenu", "touchUp: hit icon, toggled expanded to " + expanded);
                }

                float dragDist = (float) Math.hypot(screenX - lastTouchX, screenY - lastTouchY);
                if (dragDist < 30) {
                    // 点击逻辑已在 press/release 中处理
                }
            }

            pressedIndex = -1; // 抬起手指，清除按下状态
            consumingTouch = false;
            Gdx.app.log("FloatingMenu", "touchUp: consumingTouch ended");
            return true;  // 始终消费这个事件序列
        }

        Gdx.app.log("FloatingMenu", "touchUp: not consuming, returning false");
        // 防止触摸穿透到下层（KeyBoardInputProcesseor）触发重复的 ESC/ENTER 模拟
        // 收起状态点击图标展开时，touchDown 已消费事件，touchUp 也应消费
        consumingTouch = false;
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!visible) return false;
        // 如果我们正在消费这个触摸事件，继续消费
        if (consumingTouch) {
            // 实时更新按下的按钮索引（支持滑动切换按键状态，实现滑动长按）
            if (expanded) {
                float tx = screenToLogicX(screenX);
                float ty = screenToLogicY(screenY);
                int hit = hitTestPanel(tx, ty);
                if (hit != pressedIndex) {
                    if (pressedIndex >= 0) releaseButton(pressedIndex);
                    pressedIndex = hit;
                    if (pressedIndex >= 0) pressButton(pressedIndex);
                }
            }
            return true;
        }
        // 检查是否拖拽到了面板区域
        float tx = screenToLogicX(screenX);
        float ty = screenToLogicY(screenY);
        boolean inPanel = hitTestPanel(tx, ty) >= -1 || hitTestIcon(tx, ty);
        if (inPanel) {
            consumingTouch = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) { return false; }

    @Override
    public boolean scrolled(float amountX, float amountY) { return false; }

    @Override
    public boolean keyDown(int keycode) { return false; }

    @Override
    public boolean keyUp(int keycode) { return false; }

    @Override
    public boolean keyTyped(char character) { return false; }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        consumingTouch = false;
        pressedIndex = -1;
        return false;
    }

    /** 返回当前触摸是否正被浮动菜单消费（用于 MainController 跳过触摸指针/皮肤事件） */
    public boolean isConsumingTouch() {
        return consumingTouch;
    }

    // ─────────────────── 旧版轮询触摸处理（已废弃） ───────────────────

    @Deprecated
    private void handleTouch() {
        boolean touched = Gdx.input.isTouched();
        if (touched) {
            float tx = screenToLogicX(Gdx.input.getX());
            float ty = screenToLogicY(Gdx.input.getY());

            if (expanded) {
                int hit = hitTestButton(tx, ty);
                if (hit >= 0) {
                    fireButton(hit);
                } else if (hitTestIcon(tx, ty)) {
                    expanded = false;
                } else {
                    expanded = false;
                }
            } else {
                if (hitTestIcon(tx, ty)) {
                    expanded = true;
                }
            }
        }
    }

    private boolean hitTestIcon(float tx, float ty) {
        return tx >= iconX && tx <= iconX + ICON_SIZE
            && ty >= iconY && ty <= iconY + ICON_SIZE;
    }

    /** 检测触摸点是否在面板区域内，返回按钮索引（>=0）或 -1（在面板空白处）或 -2（不在面板内）或 -3/-4（翻页） */
    private int hitTestPanel(float tx, float ty) {
        if (font == null) return -2;
        int cols = 2;

        // 计算当前页显示的按钮
        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, items.length);
        int itemCount = endIdx - startIdx;
        int actualRows = (itemCount + cols - 1) / cols;

        float panelW = cols * BTN_W + (cols - 1) * BTN_GAP + PANEL_PAD * 2;
        float panelH = actualRows * BTN_H + (actualRows - 1) * BTN_GAP + PANEL_PAD * 2 + 40;
        float panelX = iconX + ICON_SIZE - panelW;
        float panelY = iconY - panelH - 4;

        if (tx < panelX || tx > panelX + panelW || ty < panelY || ty > panelY + panelH) {
            return -2;  // 不在面板区域内
        }

        for (int i = startIdx; i < endIdx; i++) {
            int localIdx = i - startIdx;
            int col = localIdx % cols;
            int row = localIdx / cols;
            float bx = panelX + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = panelY + PANEL_PAD + row * (BTN_H + BTN_GAP); // 从顶部开始
            if (tx >= bx && tx <= bx + BTN_W && ty >= by && ty <= by + BTN_H) {
                return i;
            }
        }

        // 检查是否点击了翻页箭头（最底部）
        int totalPages = (items.length + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        float bottomY = panelY + PANEL_PAD;
        GlyphLayout layout = new GlyphLayout();

        // 翻页按钮位置（底部）
        float pageTextY = bottomY + layout.height;

        // 检查左箭头
        if (currentPage > 0) {
            if (tx >= panelX + PANEL_PAD - 20 && tx <= panelX + PANEL_PAD + 20 && ty >= bottomY && ty <= bottomY + 30) {
                return -3;  // 上一页
            }
        }
        // 检查右箭头
        if (currentPage < totalPages - 1) {
            String rightArrow = ">";
            layout.setText(font, rightArrow);
            float arrowX = panelX + panelW - PANEL_PAD - layout.width;
            if (tx >= arrowX - 20 && tx <= arrowX + layout.width + 20 && ty >= bottomY && ty <= bottomY + 30) {
                return -4;  // 下一页
            }
        }

        return -1;  // 在面板空白处
    }

    private int hitTestButton(float tx, float ty) {
        return hitTestPanel(tx, ty);
    }

    private void pressButton(int index) {
        if (index < 0 || index >= items.length) return;
        MenuItem item = items[index];
        if (item.keycode == -100 || item.keycode == -101) return; // Toggle 类型在 touchUp 处理

        if (kbInput != null) {
            // 使用 setSimulatedKeyState 实现真正的长按（直到调用 false）
            kbInput.setSimulatedKeyState(item.keycode, true);
            Gdx.app.log("FloatingMenu", "pressButton: " + item.label);
        }
    }

    private void releaseButton(int index) {
        if (index < 0 || index >= items.length) return;
        MenuItem item = items[index];

        if (item.keycode == -100) {
            handleToggle(item);
            return;
        }

        if (kbInput != null) {
            kbInput.setSimulatedKeyState(item.keycode, false);
            Gdx.app.log("FloatingMenu", "releaseButton: " + item.label);
        }
    }

    private void handleToggle(MenuItem item) {
        Object mainController = kbInput.getMainController();
        if (mainController instanceof MainController) {
            Config config = ((MainController) mainController).getConfig();
            if (config != null) {
                if (item.keycode == -100) {
                    // Touch Key toggle
                    boolean newState = !config.isShowTouchKey();
                    config.setShowTouchKey(newState);
                    item.label = "Touch Key: " + (newState ? "ON" : "OFF");
                    Config.write(config);
                    MainState current = ((MainController) mainController).getCurrentState();
                    if (current instanceof bms.player.beatoraja.play.BMSPlayer) {
                        try {
                            java.lang.reflect.Field field = bms.player.beatoraja.play.BMSPlayer.class.getDeclaredField("touchKeyMapper");
                            field.setAccessible(true);
                            Object mapper = field.get(current);
                            if (mapper != null) {
                                ((bms.player.beatoraja.play.PlayTouchKeyMapper) mapper).setEnabled(newState);
                            }
                        } catch (Exception e) {
                            Gdx.app.log("FloatingMenu", "Failed to update touchKeyMapper state: " + e.getMessage());
                        }
                    }
                } else if (item.keycode == -101) {
                    // Audio Spectrum toggle
                    boolean newState = !config.isShowAudioSpectrum();
                    config.setShowAudioSpectrum(newState);
                    item.label = "Audio Spectrum: " + (newState ? "ON" : "OFF");
                    Config.write(config);
                    Gdx.app.log("FloatingMenu", "Audio Spectrum toggled to: " + newState);
                }
            }
        }
    }

    @Deprecated
    private void fireButton(int index) {
        // 已弃用，逻辑移至 pressButton/releaseButton
    }

    // ─────────────────── 坐标转换（使用 MainController 同步的视口参数）───────────────────

    private float screenToLogicX(int screenX) {
        if (vpW <= 0 || logicW <= 0) return screenX;
        return (screenX - vpX) * (float) logicW / vpW;
    }

    private float screenToLogicY(int screenY) {
        if (vpH <= 0 || logicH <= 0) return screenY;
        // 屏幕 Y 从上往下，逻辑 Y 从下往上
        return logicH - (screenY - vpY) * (float) logicH / vpH;
    }

    // ─────────────────── 资源释放 ───────────────────

    public void dispose() {
        if (iconTexture != null) { iconTexture.dispose(); iconTexture = null; }
        if (whitePixel != null)  { whitePixel.dispose();  whitePixel = null;  }
    }
}
