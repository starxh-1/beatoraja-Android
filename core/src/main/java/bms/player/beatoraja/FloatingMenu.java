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
    private static final float PANEL_PAD = 24;           // 缩小 30%
    /** 频谱调整页开始的按钮索引（第12项，0-based） */
    private static final int SPECTRUM_START = 12;

    private boolean expanded = false;
    private boolean visible = true;                     // PLAY 状态时隐藏
    private boolean selectMode = false; // 是否为 Select 界面
    private boolean keyConfigMode = false; // 是否为 KeyConfig 界面
    private boolean isPlayMode = false; // 是否为 Play 界面
    /** Play 模式时：距上次交互超过此时间则自动隐藏图标（秒） */
    private static final float HIDE_DELAY = 1.0f;
    /** Play 模式时：距上次交互已过时间（秒） */
    private float sinceLastInteraction = 0f;
    /** Play 模式时：图标是否因超时被隐藏（点击图标区域可重新显示） */
    private boolean playIconHidden = false;

    // ─── 频谱编辑模式 ───
    private int editingField = -1; // -1=none, 0=X, 1=Y, 2=W, 3=H
    private int editValue = 0; // 当前编辑值
    private int adjustDelta = 1; // 调整粒度：X/Y用1pixel，W/H用10
    /** 长按调整定时器（纳秒） */
    private long adjustStartTime = 0;
    private static final long ADJUST_INITIAL_DELAY = 300000000L; // 300ms开始
    private static final long ADJUST_ACCEL_INTERVAL = 300000000L; // 每300ms加速
    private int adjustDirection = 0; // -1=递减, +1=递增
    private boolean shortPressCommitted = false; // 短按已在render中触发，release时不再重复提交

    // ─── 分页 ───
    private static final int ITEMS_PER_PAGE = 12;  // 每页12个：2列×6行普通，或3列×4行频谱调整页
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
        final boolean showOnSelect; // 是否在 Select 界面显示
        final boolean showOnKeyConfig; // 是否在 KeyConfig 界面显示
        final boolean showOnPlay; // 是否在 Play 界面显示
        MenuItem(String label, int keycode) { this(label, keycode, false, true, true, true); }
        MenuItem(String label, int keycode, boolean isToggle) { this(label, keycode, isToggle, true, true, true); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect) { this(label, keycode, isToggle, showOnSelect, true, true); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig) { this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, true); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay) {
            this.label = label; this.keycode = keycode; this.isToggle = isToggle;
            this.showOnSelect = showOnSelect; this.showOnKeyConfig = showOnKeyConfig; this.showOnPlay = showOnPlay;
        }
    }

    // 频谱调整：-111~114=X/Y/W/H选择器, -121~122=X+/- -123~124=Y+/- -125~126=W+/- -127~128=H+/-
    // 结构：6个通用 + 13个频谱调整（独占1页）+ 2个Controller Reset
    private final MenuItem[] items = {
        // ── 通用按钮（第1页）───────────────────────
        new MenuItem("Touch Key: ON",  -100, true,  true, false, false),
        new MenuItem("F1 Show FPS",      Keys.F1, false, true, false, false),
        new MenuItem("F2 Update Song",   Keys.F2, false, true, false, false),
        new MenuItem("F10 RANDOM",   Keys.F10, false, true, false, false),
        new MenuItem("F12 Skin Select",   Keys.F12, false, true, false, false),
        new MenuItem("NUM 6 Key Config", Keys.NUM_6, false, true, false, false),
        new MenuItem("NUM 5", Keys.NUM_5, false, true, false, false),
        new MenuItem("Backspace",        Keys.BACKSPACE, false, false, true, false),
        new MenuItem("Enter",            Keys.ENTER, false, true, true, false),
        new MenuItem("^ UP",        Keys.UP, false, true, true, false),
        new MenuItem("v DOWN",      Keys.DOWN, false, true, true, false),
        new MenuItem("< LEFT",      Keys.LEFT, false, true, true, false),
        new MenuItem("> RIGHT",     Keys.RIGHT, false, true, true, false),
        // ── 频谱调整（第2页，12项，独立使用3列布局）─────
        // showOnKeyConfig=false：频谱调整仅在 Select/Play 界面显示
        new MenuItem("X: 0", -111, false, true, false, true),
        new MenuItem("[-]", -121, false, true, false, true),
        new MenuItem("[+]", -122, false, true, false, true),
        new MenuItem("Y: 0", -112, false, true, false, true),
        new MenuItem("[-]", -123, false, true, false, true),
        new MenuItem("[+]", -124, false, true, false, true),
        new MenuItem("W: 0", -113, false, true, false, true),
        new MenuItem("[-]", -125, false, true, false, true),
        new MenuItem("[+]", -126, false, true, false, true),
        new MenuItem("H: 0", -114, false, true, false, true),
        new MenuItem("[-]", -127, false, true, false, true),
        new MenuItem("[+]", -128, false, true, false, true),
        // ── Controller Reset（第3页，仅KeyConfig模式）──
        new MenuItem("NUM 8 CTRLLER RST", Keys.NUM_8, false, false, true, false),
        new MenuItem("NUM 2 CTRLLER RST", Keys.NUM_2, false, false, true, false),
    };

    // ─── 触摸与反馈状态 ───
    private float lastTouchX = 0, lastTouchY = 0;
    /** 每个指针是否被菜单消费（阻止穿透到游戏层） */
    private boolean[] pointerConsuming = new boolean[20];
    /** 每个指针正按下的按钮索引（-1 表示未按下按钮） */
    private int[] pointerPressedIndex = new int[20];
    /** 每个按钮点击后的临时高亮计时器 */
    private final float[] flashTimers = new float[items.length];
    private static final float FLASH_DURATION = 0f; // 亮起常驻
    /** 标记是否刚通过图标点击展开了菜单，用于在 touchUp 时忽略图标区域的抬起事件 */
    private boolean justExpandedByIcon = false;

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

    /** 设置是否为 Select 界面（影响按钮过滤） */
    public void setSelectMode(boolean selectMode) {
        this.selectMode = selectMode;
        currentPage = 0; // 切换模式时重置页码
    }

    /** 设置是否为 KeyConfig 界面（影响按钮过滤） */
    public void setKeyConfigMode(boolean keyConfigMode) {
        this.keyConfigMode = keyConfigMode;
        currentPage = 0;
    }

    /** 设置是否为 Play 界面（复用一个通用的菜单，不单独处理） */
    public void setPlayMode(boolean playMode) {
        this.isPlayMode = playMode;
        // 不再单独处理：play 模式复用 selectMode 的菜单
        // 进入/退出 play 模式时重置超时状态
        if (playMode) {
            sinceLastInteraction = 0f;
            playIconHidden = false;
        }
    }

    /** 判断按钮是否在当前界面显示 */
    private boolean isItemVisible(MenuItem item) {
        // Play 模式复用 selectMode 的菜单，使用 showOnSelect 作为显示依据
        if (selectMode && !item.showOnSelect) return false;
        if (keyConfigMode && !item.showOnKeyConfig) return false;
        if (isPlayMode && !item.showOnSelect) return false;
        return true;
    }

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

        // Play 模式：1秒无操作则自动隐藏图标
        if (isPlayMode && visible && !expanded) {
            sinceLastInteraction += delta;
            if (sinceLastInteraction >= HIDE_DELAY) {
                playIconHidden = true;
            }
        }

        // 长按调整：300ms后开始，每300ms加速（1→2→5→10→20...）
        if (adjustStartTime > 0 && adjustDirection != 0) {
            long elapsed = System.nanoTime() - adjustStartTime;
            if (elapsed >= ADJUST_INITIAL_DELAY) {
                // 计算加速倍数：每300ms加速一次
                long accels = (elapsed - ADJUST_INITIAL_DELAY) / ADJUST_ACCEL_INTERVAL;
                int multiplier = 1;
                for (long i = 0; i < accels && multiplier < 100; i++) {
                    multiplier = Math.min(multiplier * 2, 100);
                }
                editValue += adjustDirection * adjustDelta * multiplier;
                updateFieldLabel(editingField, editValue);
            } else if (elapsed >= 0 && !shortPressCommitted) {
                // 短按：不足300ms就松开，执行1次调整
                editValue += adjustDirection * adjustDelta;
                updateFieldLabel(editingField, editValue);
                shortPressCommitted = true;
            }
        }

        // ─── 设置投影矩阵到逻辑坐标 ───
        sprite.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, logicW, logicH));

        sprite.begin();
        // 确保使用正常的混合模式，防止皮肤（如 Note 爆发效果）残留的加算模式导致菜单发光
        sprite.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Play 模式超时隐藏：图标不绘制，但仍响应触摸
        boolean showIcon = !(isPlayMode && playIconHidden);

        if (showIcon) {
            // 绘制浮动图标
            sprite.setColor(1, 1, 1, 0.55f);
            sprite.draw(iconTexture, iconX, iconY, ICON_SIZE, ICON_SIZE);
        }

        // 如果展开，绘制面板
        if (expanded && font != null) {
            drawPanel(sprite, font);
        }

        sprite.setColor(1, 1, 1, 1);  // 重置颜色
        sprite.end();
    }

    private void drawPanel(SpriteBatch sprite, BitmapFont font) {
        // 过滤出当前界面显示的按钮
        int visibleCount = 0;
        for (MenuItem item : items) {
            if (isItemVisible(item)) visibleCount++;
        }

        // 计算分页栏高度
        int totalPages = (visibleCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        float pageBarHeight = (totalPages > 1) ? 36 : 0;

        // 计算当前页显示的按钮
        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, visibleCount);
        int itemCount = endIdx - startIdx;

        // 频谱调整页使用3列布局（4行），普通页使用2列布局（6行）
        boolean isSpectrumPage = (SPECTRUM_START >= startIdx && SPECTRUM_START < endIdx);
        int cols = isSpectrumPage ? 3 : 2;
        int actualRows = (itemCount + cols - 1) / cols;

        float panelW = cols * BTN_W + (cols - 1) * BTN_GAP + PANEL_PAD * 2;
        float panelH = actualRows * BTN_H + (actualRows - 1) * BTN_GAP + PANEL_PAD * 2 + pageBarHeight;

        // 面板位于图标左下方
        float panelX = iconX + ICON_SIZE - panelW;
        float panelY = iconY - panelH - 4;

        // 面板背景
        sprite.setColor(0.1f, 0.1f, 0.15f, 0.85f);
        sprite.draw(whitePixel, panelX, panelY, panelW, panelH);

        // 面板边框
        sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
        float border = 2;
        sprite.draw(whitePixel, panelX, panelY, panelW, border);
        sprite.draw(whitePixel, panelX, panelY + panelH - border, panelW, border);
        sprite.draw(whitePixel, panelX, panelY, border, panelH);
        sprite.draw(whitePixel, panelX + panelW - border, panelY, border, panelH);

        // 收集可见按钮索引
        int[] visibleIndices = new int[visibleCount];
        int idx = 0;
        for (int i = 0; i < items.length; i++) {
            if (isItemVisible(items[i])) {
                visibleIndices[idx++] = i;
            }
        }

        // GlyphLayout 在翻页和按钮中都要用，提前声明
        GlyphLayout layout = new GlyphLayout();

        // 绘制分页指示器和翻页按钮（面板顶部）
        if (totalPages > 1) {
            float pageY = panelY + panelH - PANEL_PAD - pageBarHeight;
            // 分页栏背景
            sprite.setColor(0.15f, 0.15f, 0.2f, 0.5f);
            sprite.draw(whitePixel, panelX + border, pageY, panelW - border * 2, pageBarHeight);

            // 左右翻页箭头
            sprite.setColor(0.4f, 0.6f, 1f, 0.9f);
            if (currentPage > 0) {
                font.setColor(0.5f, 0.8f, 1f, 0.9f);
                font.draw(sprite, "<", panelX + PANEL_PAD, pageY + pageBarHeight - 8);
            }
            if (currentPage < totalPages - 1) {
                font.setColor(0.5f, 0.8f, 1f, 0.9f);
                String rightArrow = ">";
                layout.setText(font, rightArrow);
                float arrowX = panelX + panelW - PANEL_PAD - layout.width;
                font.draw(sprite, rightArrow, arrowX, pageY + pageBarHeight - 8);
            }
            // 页码：频谱调整页显示"Spectrum"，其他页显示"页码"
            String pageText;
            if (isSpectrumPage) {
                pageText = "Spectrum Adjust";
            } else {
                pageText = (currentPage + 1) + "/" + totalPages;
            }
            font.setColor(0.7f, 0.7f, 0.7f, 0.9f);
            layout.setText(font, pageText);
            float pageTextX = panelX + (panelW - layout.width) / 2;
            font.draw(sprite, pageText, pageTextX, pageY + pageBarHeight - 8);
        }

        // 按钮（从顶部开始布局，pageBarHeight 已包含在 panelH 中）
        float contentTop = panelY + panelH - PANEL_PAD;
        for (int j = startIdx; j < endIdx; j++) {
            int itemIdx = visibleIndices[j];
            MenuItem item = items[itemIdx];
            int localIdx = j - startIdx;
            int col = localIdx % cols;
            int row = localIdx / cols;
            float bx = panelX + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = contentTop - PANEL_PAD - pageBarHeight - (row + 1) * BTN_H - row * BTN_GAP;

            // 按钮背景颜色计算：按下或正在闪烁时变亮
            boolean isPressed = false;
            for (int p = 0; p < pointerPressedIndex.length; p++) {
                if (pointerPressedIndex[p] == itemIdx) {
                    isPressed = true;
                    break;
                }
            }
            if (isPressed || flashTimers[itemIdx] > 0) {
                sprite.setColor(0.4f, 0.6f, 1.0f, 0.9f);
            } else {
                sprite.setColor(0.25f, 0.3f, 0.4f, 0.8f);
            }
            sprite.draw(whitePixel, bx, by, BTN_W, BTN_H);

            // 文字居中
            font.setColor(1, 1, 1, 0.95f);
            layout.setText(font, item.label);
            float tx = bx + (BTN_W - layout.width) / 2;
            float ty = by + (BTN_H + layout.height) / 2;
            font.draw(sprite, item.label, tx, ty);
        }
    }

    // ─────────────────── InputProcessor 事件驱动触摸处理 ───────────────────

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!visible || pointer >= pointerConsuming.length) return false;

        float tx = screenToLogicX(screenX);
        float ty = screenToLogicY(screenY);
        lastTouchX = screenX;
        lastTouchY = screenY;

        if (expanded) {
            // 展开状态：检查是否点击了图标（关闭菜单）
            if (hitTestIcon(tx, ty)) {
                expanded = false;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            // 检查是否点到了按钮
            int hit = hitTestPanel(tx, ty);
            if (hit >= 0) {
                pointerPressedIndex[pointer] = hit;
                pressButton(hit);
                pointerConsuming[pointer] = true;
                return true;
            }
            // 处理翻页
            if (hit == -3) {
                if (currentPage > 0) currentPage--;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            if (hit == -4) {
                int visibleCount = 0;
                for (MenuItem item : items) {
                    if (selectMode ? item.showOnSelect : true) visibleCount++;
                }
                int totalPages = (visibleCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
                if (currentPage < totalPages - 1) currentPage++;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            // 面板区域内空白 → 消费事件
            if (hit == -1) {
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            // 面板外点击
            pointerConsuming[pointer] = true;
            pointerPressedIndex[pointer] = -1;
            return true;
        } else {
            // 收起状态：检查是否点击了图标
            if (hitTestIcon(tx, ty)) {
                if (isPlayMode) {
                    sinceLastInteraction = 0f;
                    playIconHidden = false;
                }
                expanded = true;
                justExpandedByIcon = true;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (!visible || pointer >= pointerConsuming.length) return false;

        // 如果这个指针正在消费事件
        if (pointerConsuming[pointer]) {
            // Play 模式：任何交互都重置超时计时器
            if (isPlayMode) {
                sinceLastInteraction = 0f;
            }
            // 检查是否抬起了手指在按钮上
            int pressedIdx = pointerPressedIndex[pointer];
            if (expanded && pressedIdx >= 0) {
                releaseButton(pressedIdx);
                flashTimers[pressedIdx] = FLASH_DURATION;
            }
            justExpandedByIcon = false;
            pointerPressedIndex[pointer] = -1;
            pointerConsuming[pointer] = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!visible || pointer >= pointerConsuming.length) return false;
        if (pointerConsuming[pointer]) {
            if (expanded) {
                float tx = screenToLogicX(screenX);
                float ty = screenToLogicY(screenY);
                int hit = hitTestPanel(tx, ty);
                int currentIdx = pointerPressedIndex[pointer];
                if (hit != currentIdx) {
                    if (currentIdx >= 0) releaseButton(currentIdx);
                    pointerPressedIndex[pointer] = hit;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) { return false; }

    @Override
    public boolean scrolled(float amountX, float amountY) { return false; }

    @Override
    public boolean keyDown(int keycode) {
        // ESC取消编辑，ENTER确认
        if (editingField >= 0) {
            if (keycode == Keys.ESCAPE) {
                cancelEdit();
                return true;
            } else if (keycode == Keys.ENTER) {
                commitAdjust(false);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyUp(int keycode) { return false; }

    @Override
    public boolean keyTyped(char character) { return false; }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        if (pointer < pointerConsuming.length) {
            pointerPressedIndex[pointer] = -1;
            pointerConsuming[pointer] = false;
        }
        return false;
    }

    /** 返回当前触摸是否正被浮动菜单消费（用于 MainController 跳过触摸指针/皮肤事件） */
    public boolean isConsumingTouch() {
        for (boolean consuming : pointerConsuming) {
            if (consuming) return true;
        }
        return false;
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

        // 计算可见按钮数量
        int visibleCount = 0;
        for (MenuItem item : items) {
            if (isItemVisible(item)) visibleCount++;
        }

        // 计算当前页显示的按钮
        int startIdx = currentPage * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, visibleCount);
        int itemCount = endIdx - startIdx;

        // 频谱调整页使用3列布局（4行），普通页使用2列布局（6行）
        boolean isSpectrumPage = (SPECTRUM_START >= startIdx && SPECTRUM_START < endIdx);
        int cols = isSpectrumPage ? 3 : 2;
        int actualRows = (itemCount + cols - 1) / cols;

        float panelW = cols * BTN_W + (cols - 1) * BTN_GAP + PANEL_PAD * 2;
        int totalPages = (visibleCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        float pageBarHeight = (totalPages > 1) ? 36 : 0;
        float panelH = actualRows * BTN_H + (actualRows - 1) * BTN_GAP + PANEL_PAD * 2 + pageBarHeight;
        float panelX = iconX + ICON_SIZE - panelW;
        float panelY = iconY - panelH - 4;

        if (tx < panelX || tx > panelX + panelW || ty < panelY || ty > panelY + panelH) {
            return -2;  // 不在面板区域内
        }

        // 检查是否点击了翻页箭头（顶部）
        if (totalPages > 1) {
            float pageY = panelY + panelH - PANEL_PAD - pageBarHeight;
            if (ty >= pageY && ty <= pageY + pageBarHeight) {
                // 检查左箭头
                if (currentPage > 0 && tx >= panelX + PANEL_PAD - 20 && tx <= panelX + PANEL_PAD + 30) {
                    return -3;  // 上一页
                }
                // 检查右箭头
                if (currentPage < totalPages - 1) {
                    GlyphLayout layout = new GlyphLayout();
                    layout.setText(font, ">");
                    float arrowX = panelX + panelW - PANEL_PAD - layout.width;
                    if (tx >= arrowX - 20 && tx <= arrowX + layout.width + 20) {
                        return -4;  // 下一页
                    }
                }
            }
        }

        // 收集可见按钮索引
        int[] visibleIndices = new int[visibleCount];
        int idx = 0;
        for (int i = 0; i < items.length; i++) {
            if (isItemVisible(items[i])) {
                visibleIndices[idx++] = i;
            }
        }

        // 检查按钮
        float contentTop = panelY + panelH - PANEL_PAD;
        for (int j = startIdx; j < endIdx; j++) {
            int itemIdx = visibleIndices[j];
            int localIdx = j - startIdx;
            int col = localIdx % cols;
            int row = localIdx / cols;
            float bx = panelX + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = contentTop - PANEL_PAD - pageBarHeight - (row + 1) * BTN_H - row * BTN_GAP;
            if (tx >= bx && tx <= bx + BTN_W && ty >= by && ty <= by + BTN_H) {
                return itemIdx; // 返回实际按钮索引
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
        if (item.keycode == -100) return; // Toggle 类型在 touchUp 处理

        // 频谱调整 +/- 按钮：启动长按定时
        if (item.keycode >= -128 && item.keycode <= -121) {
            int fieldIdx = getFieldIndexFromAdjustButton(item.keycode);
            if (fieldIdx >= 0) {
                editingField = fieldIdx;
                editValue = getFieldValue(fieldIdx);
                adjustDelta = (fieldIdx >= 2) ? 10 : 1; // X/Y=1pixel, W/H=10
                adjustDirection = (item.keycode % 2 != 0) ? -1 : +1; // 奇数=-, 偶数=+ (负奇数%2!=0也成立)
                adjustStartTime = System.nanoTime();
                shortPressCommitted = false;
                return;
            }
        }

        // 频谱调整项 X/Y/W/H 选择器
        if (item.keycode >= -114 && item.keycode <= -111) {
            int fieldIndex = -(item.keycode + 111); // -111→0, -112→1, -113→2, -114→3
            editingField = fieldIndex;
            editValue = getFieldValue(fieldIndex);
            updateFieldLabel(fieldIndex, editValue);
            return;
        }

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

        // 频谱调整 +/- 按钮：停止长按定时
        if (item.keycode >= -128 && item.keycode <= -121) {
            adjustStartTime = 0;
            adjustDirection = 0;
            shortPressCommitted = false;
            // 短按或长按释放时都要提交并保存
            commitAdjust(true);
            return;
        }

        // 频谱调整项 X/Y/W/H 选择器
        if (item.keycode >= -114 && item.keycode <= -111) {
            int fieldIndex = -(item.keycode + 111);
            editingField = fieldIndex;
            editValue = getFieldValue(fieldIndex);
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

    // ─────────────────── 频谱编辑模式 ───────────────────

    /** 从 +/- 按钮获取对应的 field 索引 */
    private int getFieldIndexFromAdjustButton(int keycode) {
        // -121,-122=X+-, -123,-124=Y+-, -125,-126=W+-, -127,-128=H+-
        if (keycode == -121 || keycode == -122) return 0;
        if (keycode == -123 || keycode == -124) return 1;
        if (keycode == -125 || keycode == -126) return 2;
        if (keycode == -127 || keycode == -128) return 3;
        return -1;
    }

    /** 获取指定 field 的当前值 */
    private int getFieldValue(int field) {
        Object mc = kbInput != null ? kbInput.getMainController() : null;
        if (mc instanceof MainController) {
            PlayerConfig pc = ((MainController) mc).getPlayerConfig();
            if (pc != null) {
                switch (field) {
                    case 0: return pc.getSpectrumOffsetX();
                    case 1: return pc.getSpectrumOffsetY();
                    case 2: return pc.getSpectrumOffsetW();
                    case 3: return pc.getSpectrumOffsetH();
                }
            }
        }
        return 0;
    }

    /** 更新指定 field 的标签显示 */
    private void updateFieldLabel(int field, int value) {
        String prefix;
        switch (field) {
            case 0: prefix = "X:"; break;
            case 1: prefix = "Y:"; break;
            case 2: prefix = "W:"; break;
            case 3: prefix = "H:"; break;
            default: return;
        }
        // items 排列：0-11通用 + 12-23频谱调整(X/Y/W/H各3个) + 24-25 Controller Reset
        items[12 + field * 3].label = prefix + " " + value;
    }

    /** 提交调整值到 PlayerConfig 并保存 */
    private void commitAdjust(boolean forceSave) {
        if (editingField < 0) return;
        Object mc = kbInput != null ? kbInput.getMainController() : null;
        if (mc instanceof MainController) {
            MainController main = (MainController) mc;
            PlayerConfig pc = main.getPlayerConfig();
            if (pc != null) {
                switch (editingField) {
                    case 0: pc.setSpectrumOffsetX(editValue); break;
                    case 1: pc.setSpectrumOffsetY(editValue); break;
                    case 2: pc.setSpectrumOffsetW(editValue); break;
                    case 3: pc.setSpectrumOffsetH(editValue); break;
                }
                PlayerConfig.write(main.getConfig().getPlayerpath(), pc);
                Object game = main.getBeatorajaGame();
                if (game != null && game instanceof com.starxh.beatoraja.BeatorajaGame) {
                    ((com.starxh.beatoraja.BeatorajaGame) game).updateSpectrumConfig();
                }
                Gdx.app.log("FloatingMenu", "Spectrum " + editingField + " = " + editValue);
            }
        }
        editingField = -1;
        adjustStartTime = 0;
        adjustDirection = 0;
    }

    /** 取消编辑，恢复原值 */
    private void cancelEdit() {
        if (editingField >= 0) {
            updateFieldLabel(editingField, getFieldValue(editingField));
        }
        editingField = -1;
        adjustStartTime = 0;
        adjustDirection = 0;
    }

    private void exitEdit() {
        editingField = -1;
        adjustStartTime = 0;
        adjustDirection = 0;
    }

    private void refreshSpectrumLabels() {
        for (int i = 0; i < 4; i++) {
            int value = getFieldValue(i);
            String prefix;
            switch (i) {
                case 0: prefix = "X:"; break;
                case 1: prefix = "Y:"; break;
                case 2: prefix = "W:"; break;
                case 3: prefix = "H:"; break;
                default: prefix = "?"; break;
            }
            items[12 + i * 3].label = prefix + " " + value;
        }
    }

    // ─────────────────── 资源释放 ───────────────────

    public void dispose() {
        if (iconTexture != null) { iconTexture.dispose(); iconTexture = null; }
        if (whitePixel != null)  { whitePixel.dispose();  whitePixel = null;  }
    }
}
