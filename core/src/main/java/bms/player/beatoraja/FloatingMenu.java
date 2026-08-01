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
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.rating.PlayerRatingService;

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
    /** 频谱调整页开始的按钮索引（0-based） */
    private static final int SPECTRUM_START = 15;

    private boolean expanded = false;
    private boolean visible = true;                     // PLAY 状态时隐藏
    private boolean selectMode = false; // 是否为 MusicSelect 界面
    private boolean keyConfigMode = false; // 是否为 KeyConfig 界面
    private boolean skinSelectMode = false; // 是否为 SkinSelect 界面（皮肤选择/配置）
    private boolean isPlayMode = false; // 是否为 Play 界面
    /** Play 模式时：距上次交互超过此时间则自动隐藏图标（秒） */
    private static final float HIDE_DELAY = 0f;
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
        final boolean showOnSelect;       // 是否在 MusicSelect 界面显示
        final boolean showOnKeyConfig;    // 是否在 KeyConfig 界面显示
        final boolean showOnPlay;         // 是否在 Play 界面显示
        final boolean showOnSkinSelect;   // 是否在 SkinSelect 界面显示（默认 false，避免误显示）
        MenuItem(String label, int keycode) { this(label, keycode, false, true, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle) { this(label, keycode, isToggle, true, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect) { this(label, keycode, isToggle, showOnSelect, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig) { this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay) {
            this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, false);
        }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay, boolean showOnSkinSelect) {
            this.label = label; this.keycode = keycode; this.isToggle = isToggle;
            this.showOnSelect = showOnSelect; this.showOnKeyConfig = showOnKeyConfig;
            this.showOnPlay = showOnPlay; this.showOnSkinSelect = showOnSkinSelect;
        }
    }

    // 频谱调整：-111~114=X/Y/W/H选择器, -121~122=X+/- -123~124=Y+/- -125~126=W+/- -127~128=H+/-
    // 结构：6个通用 + 13个频谱调整（独占1页）+ 2个Controller Reset
    private final MenuItem[] items = {
        // ── 通用按钮（第1页）───────────────────────
        // 构造参数: (label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, showOnSkinSelect)
        new MenuItem("Touch Key: ON",  -100, true,  true, false, true, false),
        new MenuItem("Show FPS",      Keys.F1, false, true, false, true, false),
        new MenuItem("Update Song",   Keys.F2, false, true, false, true, false),
        new MenuItem("Music Player",   -130, false, true, false, true, false),
        new MenuItem("Skin Select",   Keys.F12, false, true, false, true, false),
        new MenuItem("Key Config", Keys.NUM_6, false, true, false, true, true),
        new MenuItem("NUM 5", Keys.NUM_5, false, true, false, false, true),
        new MenuItem("Backspace",        Keys.BACKSPACE, false, false, false, false, false),
        new MenuItem("ESC",   Keys.ESCAPE, false, false, true, true, true),
        new MenuItem("Enter",            Keys.ENTER, false, false, true, false, true),
        new MenuItem("START Hold",    -141, false, true, false, false, true),
        new MenuItem("^ UP",        Keys.UP, false, true, true, true, true),
        new MenuItem("v DOWN",      Keys.DOWN, false, true, true, true, true),
        new MenuItem("< LEFT",      Keys.LEFT, false, true, true, true, true),
        new MenuItem("> RIGHT",     Keys.RIGHT, false, false, true, false, true),
        new MenuItem("Walkure",       -140, false, true, false, true, false),

        // ── 频谱调整（第2页，12项，独立使用3列布局）─────
        // showOnKeyConfig=false：频谱调整仅在 Select/Play 界面显示
        new MenuItem("X: 0", -111, false, true, false, true, false),
        new MenuItem("[-]", -121, false, true, false, true, false),
        new MenuItem("[+]", -122, false, true, false, true, false),
        new MenuItem("Y: 0", -112, false, true, false, true, false),
        new MenuItem("[-]", -123, false, true, false, true, false),
        new MenuItem("[+]", -124, false, true, false, true, false),
        new MenuItem("W: 0", -113, false, true, false, true, false),
        new MenuItem("[-]", -125, false, true, false, true, false),
        new MenuItem("[+]", -126, false, true, false, true, false),
        new MenuItem("H: 0", -114, false, true, false, true, false),
        new MenuItem("[-]", -127, false, true, false, true, false),
        new MenuItem("[+]", -128, false, true, false, true, false),

        // ── Controller Reset（第3页，仅KeyConfig模式）──
        new MenuItem("NUM 8", Keys.NUM_8, false, false, true, false, false),
        new MenuItem("NUM 2", Keys.NUM_2, false, false, true, false, false),
        new MenuItem("DELETE", Keys.FORWARD_DEL, false, false, true, false, false),

    };

    private final Matrix4 menuProj = new Matrix4();

    // ─── 触摸与反馈状态 ───
    private float lastTouchX = 0, lastTouchY = 0;
    /** 每个指针是否被菜单消费（阻止穿透到游戏层） */
    private boolean[] pointerConsuming = new boolean[20];
    /** 每个指针正按下的按钮索引（-1 表示未按下按钮） */
    private int[] pointerPressedIndex = new int[20];
    /** 每个指针在 7K 覆盖层中按下的键索引（-1 表示未按下任何 7K 键） */
    private int[] pointer7KKey = new int[20];
    /** 每个按钮点击后的临时高亮计时器 */
    private final float[] flashTimers = new float[items.length];
    private static final float FLASH_DURATION = 0f; // 亮起常驻
    /** 标记是否刚通过图标点击展开了菜单，用于在 touchUp 时忽略图标区域的抬起事件 */
    private boolean justExpandedByIcon = false;

    // ─── NUM5 长按模式（7K 覆盖层） ───
    /** 长按模式类型：NONE=未激活，NUM5=模拟 NUM5 长按，START=模拟 START 长按 */
    private enum HoldKeyType { NONE, NUM5, START }
    /** 当前正在长按模拟的按键类型 */
    private HoldKeyType holdKeyType = HoldKeyType.NONE;
    /** 是否处于长按状态（按下未释放），决定 7K 覆盖层是否显示 */
    private boolean holdKeyHeld = false;
    /** 7KEYS 默认标签（仅在无法读取 kbInput 配置时回退使用） */
    private static final int[] SEVEN_KEYS_KEYCODES_DEFAULT = {
        Keys.Z, Keys.S, Keys.X, Keys.D, Keys.C, Keys.F, Keys.V
    };
    /** 缓存 7K 各键显示名称，避免每帧 Keys.toString 分配 String 触发 GC 压力 */
    private final String[] cached7KKeyNames = new String[7];
    /** 缓存 7K 各键当前 keycode（-1 表示未初始化），用于检测配置变更以刷新名称 */
    private final int[] cached7KKeycodes = new int[] {-1, -1, -1, -1, -1, -1, -1};
    /** 覆盖层整体目标占比（屏幕短边），用于按屏幕尺寸自适应 */
    private static final float K7_TARGET_SCREEN_RATIO = 0.30f;
    private static final float K7_BTN_GAP = 8;
    /** 标题/关闭按钮固定高度 */
    private static final float K7_TITLE_H = 32;
    private static final float K7_CLOSE_H = 44;

    // ─── 引用 ───
    private KeyBoardInputProcesseor kbInput;

    public FloatingMenu() {
        updateIconPosition();
        createTextures();
        for (int i = 0; i < pointer7KKey.length; i++) {
            pointer7KKey[i] = -1;
        }
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
        Config config = null;
        if (kbInput != null && kbInput.getMainController() instanceof MainController) {
            config = ((MainController) kbInput.getMainController()).getConfig();
        }

        int pos = (config != null) ? config.getFloatingMenuPosition() : 0;
        switch (pos) {
            case 1: // Top Right
                iconX = logicW - ICON_SIZE - ICON_MARGIN;
                iconY = logicH - ICON_SIZE - ICON_MARGIN;
                break;
            case 2: // Bottom Center
                iconX = (logicW - ICON_SIZE) / 2;
                iconY = ICON_MARGIN;
                break;
            case 3: // Bottom Right
                iconX = logicW - ICON_SIZE - ICON_MARGIN;
                iconY = ICON_MARGIN;
                break;
            default: // 0: Top Center
                iconX = (logicW - ICON_SIZE) / 2;
                iconY = logicH - ICON_SIZE - ICON_MARGIN;
                break;
        }
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

    /** 设置是否为 SkinSelect 界面（影响按钮过滤） */
    public void setSkinSelectMode(boolean skinSelectMode) {
        this.skinSelectMode = skinSelectMode;
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
        if (selectMode && !item.showOnSelect) return false;
        if (keyConfigMode && !item.showOnKeyConfig) return false;
        if (skinSelectMode && !item.showOnSkinSelect) return false;
        if (isPlayMode && !item.showOnPlay) return false;
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

        // 7K 覆盖层：检测已断开但未收到 touchUp 的指针（应用切后台等异常路径）
        if (holdKeyHeld) {
            for (int p = 0; p < pointer7KKey.length; p++) {
                int k7 = pointer7KKey[p];
                if (k7 >= 0 && (p >= pointerConsuming.length || !pointerConsuming[p] || !Gdx.input.isTouched(p))) {
                    send7KKey(k7, false);
                    pointer7KKey[p] = -1;
                }
            }
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
        sprite.setProjectionMatrix(menuProj.setToOrtho2D(0, 0, logicW, logicH));

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

        // 长按模式（NUM5/START）：绘制 7K 覆盖层
        if (holdKeyHeld && font != null) {
            draw7KOverlay(sprite, font);
        }

        sprite.setColor(1, 1, 1, 1);  // 重置颜色
        sprite.end();
    }

    private void drawPanel(SpriteBatch sprite, BitmapFont font) {
        // 1. 获取基础布局参数
        PanelLayout info = calculatePanelLayout();

        // 面板背景
        sprite.setColor(0.1f, 0.1f, 0.15f, 0.85f);
        sprite.draw(whitePixel, info.x, info.y, info.w, info.h);

        // 面板边框
        sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
        float border = 2;
        sprite.draw(whitePixel, info.x, info.y, info.w, border);
        sprite.draw(whitePixel, info.x, info.y + info.h - border, info.w, border);
        sprite.draw(whitePixel, info.x, info.y, border, info.h);
        sprite.draw(whitePixel, info.x + info.w - border, info.y, border, info.h);

        // 收集可见按钮索引
        int visibleCount = 0;
        for (MenuItem item : items) { if (isItemVisible(item)) visibleCount++; }
        int[] visibleIndices = new int[visibleCount];
        int idx = 0;
        for (int i = 0; i < items.length; i++) {
            if (isItemVisible(items[i])) {
                visibleIndices[idx++] = i;
            }
        }

        // GlyphLayout 在翻页和按钮中都要用
        GlyphLayout glyph = new GlyphLayout();

        // 绘制分页指示器和翻页按钮（面板顶部）
        if (info.totalPages > 1) {
            float pageY = info.y + info.h - PANEL_PAD - info.pageBarHeight;
            // 分页栏背景
            sprite.setColor(0.15f, 0.15f, 0.2f, 0.5f);
            sprite.draw(whitePixel, info.x + border, pageY, info.w - border * 2, info.pageBarHeight);

            // 左右翻页箭头
            sprite.setColor(0.4f, 0.6f, 1f, 0.9f);
            if (currentPage > 0) {
                font.setColor(0.5f, 0.8f, 1f, 0.9f);
                font.draw(sprite, "<", info.x + PANEL_PAD, pageY + info.pageBarHeight - 8);
            }
            if (currentPage < info.totalPages - 1) {
                font.setColor(0.5f, 0.8f, 1f, 0.9f);
                String rightArrow = ">";
                glyph.setText(font, rightArrow);
                float arrowX = info.x + info.w - PANEL_PAD - glyph.width;
                font.draw(sprite, rightArrow, arrowX, pageY + info.pageBarHeight - 8);
            }
            // 页码：频谱调整页显示"Spectrum"，其他页显示"页码"
            String pageText;
            if (info.isSpectrumPage) {
                pageText = "In-Game Spectrum Adjust";
            } else {
                pageText = (currentPage + 1) + "/" + info.totalPages;
            }
            font.setColor(0.7f, 0.7f, 0.7f, 0.9f);
            glyph.setText(font, pageText);
            float pageTextX = info.x + (info.w - glyph.width) / 2;
            font.draw(sprite, pageText, pageTextX, pageY + info.pageBarHeight - 8);
        }

        // 按钮（从顶部开始布局）
        float contentTop = info.y + info.h - PANEL_PAD - (info.pageBarHeight > 0 ? info.pageBarHeight + 4 : 0);
        for (int j = info.startIdx; j < info.endIdx; j++) {
            int itemIdx = visibleIndices[j];
            MenuItem item = items[itemIdx];

            int localIdx = j - info.startIdx;
            int row = localIdx / info.cols;
            int col = localIdx % info.cols;

            float bx = info.x + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = contentTop - (row + 1) * BTN_H - row * BTN_GAP;

            // 绘制按钮背景
            boolean pressed = false;
            for (int p = 0; p < pointerPressedIndex.length; p++) {
                if (pointerPressedIndex[p] == itemIdx) { pressed = true; break; }
            }

            if (pressed) {
                sprite.setColor(0.3f, 0.5f, 0.8f, 0.95f);
            } else if (flashTimers[itemIdx] > 0) {
                sprite.setColor(0.5f, 0.7f, 1.0f, 0.9f);
            } else {
                sprite.setColor(0.2f, 0.2f, 0.3f, 0.7f);
            }
            sprite.draw(whitePixel, bx, by, BTN_W, BTN_H);

            // 按钮文字
            font.setColor(1, 1, 1, 0.9f);
            glyph.setText(font, item.label);
            font.draw(sprite, item.label, bx + (BTN_W - glyph.width) / 2, by + (BTN_H + glyph.height) / 2);

            // 如果是开关项，绘制状态指示
            if (item.isToggle) {
                boolean active = isToggleActive(item);
                sprite.setColor(active ? Color.CYAN : Color.GRAY);
                sprite.draw(whitePixel, bx + 4, by + 4, 8, BTN_H - 8);
            }
        }
    }

    private boolean isToggleActive(MenuItem item) {
        if (item.keycode == -100) { // Touch Key
            if (kbInput != null && kbInput.getMainController() instanceof MainController) {
                return ((MainController) kbInput.getMainController()).getConfig().isShowTouchKey();
            }
        }
        return false;
    }

    // ─────────────────── NUM5 长按模式：7K 覆盖层 ───────────────────

    /** 7K 覆盖层缓存的布局参数（每帧重算） */
    private float k7BtnW, k7BtnH;
    private float k7PanelX, k7PanelY;
    private float k7TitleY, k7CloseY;

    /** 计算 7K 覆盖层布局：整体高度约屏幕高度的 K7_TARGET_SCREEN_RATIO */
    private void calculate7KOverlayLayout() {
        // 总垂直空间 = 标题 + 间距 + 按钮 + 间距 + 关闭按钮
        float totalH = K7_TITLE_H + 12 + K7_CLOSE_H + 12;
        // 按钮高度 = 目标高度 - 其他元素占用
        float targetH = logicH * K7_TARGET_SCREEN_RATIO;
        k7BtnH = Math.max(60, targetH - totalH);

        // 按钮宽度：让 7 个按钮 + 6 个间隙居中后占屏幕宽度 ~50%
        float maxTotalW = logicW * 0.55f;
        k7BtnW = (maxTotalW - 6 * K7_BTN_GAP) / 7f;
        k7BtnW = Math.max(60, Math.min(k7BtnW, 140));

        float totalW = 7 * k7BtnW + 6 * K7_BTN_GAP;
        k7PanelX = (logicW - totalW) / 2f;
        // 让 title + buttons + close 三段整体居中
        float blockH = K7_TITLE_H + 12 + k7BtnH + 12 + K7_CLOSE_H;
        float blockTop = (logicH + blockH) / 2f;
        k7TitleY = blockTop - K7_TITLE_H;
        k7PanelY = k7TitleY - 12 - k7BtnH;
        k7CloseY = k7PanelY - 12 - K7_CLOSE_H;
    }

    /** 检测触摸是否在 7K 覆盖层的某个键上，返回 0~6 的键索引，否则返回 -1
     * @param stickyKey 当前已按下的键索引（-1 表示无），用于"粘住"避免手指抖动时释放按键 */
    private int hitTest7KKey(float tx, float ty, int stickyKey) {
        if (ty < k7PanelY || ty > k7PanelY + k7BtnH) return -1;

        // 先检查手指当前实际命中哪个键
        int actualHit = -1;
        for (int i = 0; i < 7; i++) {
            float bx = k7PanelX + i * (k7BtnW + K7_BTN_GAP);
            if (tx >= bx && tx <= bx + k7BtnW) {
                actualHit = i;
                break;
            }
        }

        // 已按下某键时采用"粘住"策略：
        // - 手指滑入间隙/边缘抖动 → 保持当前按键，避免长按时被反复 release/press
        // - 手指明确滑到另一键上 → 切换到新键
        // - 手指滑到面板 Y 范围外（hitTest 入口已拦截）→ 保持当前按键
        if (stickyKey >= 0) {
            if (actualHit == stickyKey) return stickyKey;
            if (actualHit >= 0) return actualHit; // 明确切到另一键
            return stickyKey; // 抖动/间隙：保持
        }

        // 未按下任何键：标准命中测试
        return actualHit;
    }

    /** 检测触摸是否在 7K 覆盖层的"关闭"按钮上 */
    private boolean hitTest7KClose(float tx, float ty) {
        float closeW = 180;
        float cx = (logicW - closeW) / 2f;
        return tx >= cx && tx <= cx + closeW && ty >= k7CloseY && ty <= k7CloseY + K7_CLOSE_H;
    }

    /**
     * 发送 7K 按键状态到核心层（绕过 setSimulatedKeyState 的 release bug）。
     * 使用逻辑 key index（0~6 对应 7K 第 1~7 个 lane），由 LaneProperty 决定映射。
     */
    private void send7KKey(int keyIdx, boolean pressed) {
        if (kbInput == null) return;
        Object mc = kbInput.getMainController();
        if (!(mc instanceof MainController)) return;
        BMSPlayerInputProcessor input = ((MainController) mc).getInputProcessor();
        if (input == null) return;

        long microtime;
        long startTime = input.getStartTime();
        if (startTime != 0) {
            microtime = System.nanoTime() / 1000 - startTime;
        } else {
            microtime = System.nanoTime() / 1000;
        }
        input.setKeyChanged(keyIdx, pressed, microtime);
    }

    /**
     * 发送 START 按键状态到核心层。
     * 通过 BMSPlayerInputProcessor.startChanged() 直接设置，与 KeyBoardInputProcessor.poll()
     * 中处理物理 START 的路径保持一致，绕过 keystate 中转以避免 toggle 的 release 失效。
     */
    private void sendStartKey(boolean pressed) {
        if (kbInput == null) return;
        Object mc = kbInput.getMainController();
        if (!(mc instanceof MainController)) return;
        BMSPlayerInputProcessor input = ((MainController) mc).getInputProcessor();
        if (input == null) return;
        input.startChanged(pressed);
    }

    /**
     * 释放当前长按模拟的按键（NUM5 或 START）。同时关闭 7K 覆盖层。
     * 用于关闭按钮、图标再次点击、按钮二次点击等所有收尾路径。
     */
    private void releaseHoldKey() {
        if (holdKeyType == HoldKeyType.NUM5 && kbInput != null) {
            kbInput.setSimulatedKeyState(Keys.NUM_5, false);
        } else if (holdKeyType == HoldKeyType.START) {
            sendStartKey(false);
        }
        holdKeyType = HoldKeyType.NONE;
        holdKeyHeld = false;
    }

    /** 绘制 7K 覆盖层：7 个键 + 标题 + 关闭按钮 */
    private void draw7KOverlay(SpriteBatch sprite, BitmapFont font) {
        calculate7KOverlayLayout();
        GlyphLayout glyph = new GlyphLayout();

        // 读取当前用户配置的 7 个 lane 键位（用户在 Key Config 中可改）
        int[] userKeys = (kbInput != null) ? kbInput.getKeys() : null;
        refresh7KKeyNamesCache(userKeys);

        // 标题（上方居中）
        font.setColor(0.5f, 0.8f, 1f, 0.95f);
        String heldName = (holdKeyType == HoldKeyType.START) ? "START" : "NUM5";
        String title = heldName + " Long-Press: 7KEYS (tap again to release)";
        glyph.setText(font, title);
        font.draw(sprite, title, (logicW - glyph.width) / 2f, k7TitleY + K7_TITLE_H * 0.7f);

        // 7 个键
        for (int i = 0; i < 7; i++) {
            float bx = k7PanelX + i * (k7BtnW + K7_BTN_GAP);

            boolean pressed = false;
            for (int p = 0; p < pointer7KKey.length; p++) {
                if (pointer7KKey[p] == i) { pressed = true; break; }
            }

            if (pressed) {
                sprite.setColor(0.3f, 0.5f, 0.8f, 0.95f);
            } else {
                sprite.setColor(0.2f, 0.2f, 0.3f, 0.6f);
            }
            sprite.draw(whitePixel, bx, k7PanelY, k7BtnW, k7BtnH);

            sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
            float border = 2;
            sprite.draw(whitePixel, bx, k7PanelY, k7BtnW, border);
            sprite.draw(whitePixel, bx, k7PanelY + k7BtnH - border, k7BtnW, border);
            sprite.draw(whitePixel, bx, k7PanelY, border, k7BtnH);
            sprite.draw(whitePixel, bx + k7BtnW - border, k7PanelY, border, k7BtnH);

            // 键号（大字）
            font.setColor(1, 1, 1, 0.95f);
            String num = String.valueOf(i + 1);
            glyph.setText(font, num);
            font.draw(sprite, num, bx + (k7BtnW - glyph.width) / 2f, k7PanelY + k7BtnH * 0.66f);

            // 对应键盘按键（小字）：使用缓存的显示名称，避免每帧 Keys.toString 分配
            font.setColor(0.7f, 0.7f, 0.7f, 0.85f);
            String keyName = cached7KKeyNames[i];
            glyph.setText(font, keyName);
            font.draw(sprite, keyName, bx + (k7BtnW - glyph.width) / 2f, k7PanelY + k7BtnH * 0.28f);
        }

        // 关闭按钮（覆盖层下方居中）
        float closeW = 180;
        float cx = (logicW - closeW) / 2f;
        sprite.setColor(0.6f, 0.2f, 0.2f, 0.85f);
        sprite.draw(whitePixel, cx, k7CloseY, closeW, K7_CLOSE_H);
        sprite.setColor(1, 1, 1, 0.9f);
        sprite.draw(whitePixel, cx, k7CloseY + K7_CLOSE_H - 2, closeW, 2);
        sprite.draw(whitePixel, cx, k7CloseY, closeW, 2);
        sprite.draw(whitePixel, cx, k7CloseY, 2, K7_CLOSE_H);
        sprite.draw(whitePixel, cx + closeW - 2, k7CloseY, 2, K7_CLOSE_H);

        font.setColor(1, 1, 1, 0.95f);
        String closeLabel = (holdKeyType == HoldKeyType.START) ? "Release START" : "Release NUM5";
        glyph.setText(font, closeLabel);
        font.draw(sprite, closeLabel, cx + (closeW - glyph.width) / 2f, k7CloseY + (K7_CLOSE_H + glyph.height) / 2f);
    }

    /**
     * 刷新 7K 覆盖层各键显示名称的缓存。仅在 keycode 实际变化时（如 Key Config 修改后）
     * 重新调用 Keys.toString，避免每帧分配短 String 触发 GC 压力。
     */
    private void refresh7KKeyNamesCache(int[] userKeys) {
        for (int i = 0; i < 7; i++) {
            int keycode;
            if (userKeys != null && i < userKeys.length && userKeys[i] >= 0) {
                keycode = userKeys[i];
            } else {
                keycode = SEVEN_KEYS_KEYCODES_DEFAULT[i];
            }
            if (cached7KKeycodes[i] != keycode) {
                cached7KKeycodes[i] = keycode;
                cached7KKeyNames[i] = Keys.toString(keycode);
            }
        }
    }

    private static class PanelLayout {
        float x, y, w, h;
        float pageBarHeight;
        int totalPages;
        int startIdx, endIdx;
        boolean isSpectrumPage;
        int cols;
    }

    private PanelLayout calculatePanelLayout() {
        PanelLayout res = new PanelLayout();

        // 统计可见按钮
        int visibleCount = 0;
        for (MenuItem item : items) {
            if (isItemVisible(item)) visibleCount++;
        }

        res.totalPages = (visibleCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (currentPage >= res.totalPages) currentPage = Math.max(0, res.totalPages - 1);

        res.pageBarHeight = (res.totalPages > 1) ? 36 : 0;
        res.startIdx = currentPage * ITEMS_PER_PAGE;
        res.endIdx = Math.min(res.startIdx + ITEMS_PER_PAGE, visibleCount);
        int itemCount = res.endIdx - res.startIdx;

        res.isSpectrumPage = (SPECTRUM_START >= res.startIdx && SPECTRUM_START < res.endIdx);
        res.cols = res.isSpectrumPage ? 3 : 2;
        int rows = (itemCount + res.cols - 1) / res.cols;

        res.w = res.cols * BTN_W + (res.cols - 1) * BTN_GAP + PANEL_PAD * 2;
        res.h = rows * BTN_H + (rows - 1) * BTN_GAP + PANEL_PAD * 2 + (res.pageBarHeight > 0 ? res.pageBarHeight + 4 : 0);

        // 定位逻辑
        Config config = null;
        if (kbInput != null && kbInput.getMainController() instanceof MainController) {
            config = ((MainController) kbInput.getMainController()).getConfig();
        }
        int pos = (config != null) ? config.getFloatingMenuPosition() : 0;

        // Y轴：底部图标向上弹出，顶部图标向下弹出
        if (pos >= 2) { // Bottom Center, Bottom Right
            res.y = iconY + ICON_SIZE + 8;
        } else { // Top Center, Top Right
            res.y = iconY - res.h - 8;
        }

        // X轴：居中或对齐右侧
        if (pos == 1 || pos == 3) { // Top Right, Bottom Right
            res.x = iconX + ICON_SIZE - res.w;
        } else { // Center
            res.x = iconX + (ICON_SIZE - res.w) / 2;
        }

        // 边界保护
        if (res.x < 10) res.x = 10;
        if (res.x + res.w > logicW - 10) res.x = logicW - res.w - 10;
        if (res.y < 10) res.y = 10;
        if (res.y + res.h > logicH - 10) res.y = logicH - res.h - 10;

        return res;
    }

    // ─────────────────── InputProcessor 事件驱动触摸处理 ───────────────────

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!visible || pointer >= pointerConsuming.length) return false;

        float tx = screenToLogicX(screenX);
        float ty = screenToLogicY(screenY);
        lastTouchX = screenX;
        lastTouchY = screenY;

        // 长按模式（NUM5/START）下的 7K 覆盖层：先于菜单面板处理
        if (holdKeyHeld) {
            // 关闭按钮
            if (hitTest7KClose(tx, ty)) {
                releaseHoldKey();
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                pointer7KKey[pointer] = -1;
                Gdx.app.log("FloatingMenu", "7K overlay: closed via close button");
                return true;
            }
            // 7K 键 1~7
            int k7Idx = hitTest7KKey(tx, ty, pointer7KKey[pointer]);
            if (k7Idx >= 0) {
                pointer7KKey[pointer] = k7Idx;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                send7KKey(k7Idx, true);
                return true;
            }
            // 点击浮动图标：关闭覆盖层并释放长按键（用户重开菜单后可再次点击释放）
            if (hitTestIcon(tx, ty)) {
                releaseHoldKey();
                expanded = true;
                justExpandedByIcon = true;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                pointer7KKey[pointer] = -1;
                return true;
            }
            // 覆盖层模态：消费其他触摸防止穿透到游戏
            pointerConsuming[pointer] = true;
            pointerPressedIndex[pointer] = -1;
            pointer7KKey[pointer] = -1;
            return true;
        }

        if (expanded) {
            // 展开状态：检查是否点击了图标（关闭菜单）
            if (hitTestIcon(tx, ty)) {
                expanded = false;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            // 检查是否点到了按钮
            int itemHit = hitTestPanel(tx, ty);
            if (itemHit >= 0) {
                pointerPressedIndex[pointer] = itemHit;
                pressButton(itemHit);
                pointerConsuming[pointer] = true;
                return true;
            }
            // 处理翻页
            if (itemHit == -3) {
                if (currentPage > 0) currentPage--;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            if (itemHit == -4) {
                int visibleCount = 0;
                for (MenuItem item : items) {
                    if (isItemVisible(item)) visibleCount++;
                }
                int totalPages = (visibleCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
                if (currentPage < totalPages - 1) currentPage++;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            // 面板区域内空白或点击面板外任何地方 → 关闭菜单并消费事件
            expanded = false;
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
            // 7K 覆盖层：释放对应的 7KEYS 键
            int k7Idx = pointer7KKey[pointer];
            if (k7Idx >= 0) {
                send7KKey(k7Idx, false);
                pointer7KKey[pointer] = -1;
                justExpandedByIcon = false;
                pointerPressedIndex[pointer] = -1;
                pointerConsuming[pointer] = false;
                return true;
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
            // 7K 覆盖层：始终跟随手指，按下当前命中键、释放之前的键
            if (holdKeyHeld) {
                float tx = screenToLogicX(screenX);
                float ty = screenToLogicY(screenY);
                int newHit = hitTest7KKey(tx, ty, pointer7KKey[pointer]);
                int prevHit = pointer7KKey[pointer];
                if (newHit != prevHit) {
                    if (prevHit >= 0) {
                        send7KKey(prevHit, false);
                    }
                    pointer7KKey[pointer] = newHit;
                    if (newHit >= 0) {
                        send7KKey(newHit, true);
                    }
                }
                return true;
            }
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
            // 释放可能按住的 7K 键
            int k7Idx = pointer7KKey[pointer];
            if (k7Idx >= 0) {
                send7KKey(k7Idx, false);
            }
            pointer7KKey[pointer] = -1;
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

        PanelLayout info = calculatePanelLayout();

        if (tx < info.x || tx > info.x + info.w || ty < info.y || ty > info.y + info.h) {
            return -2;  // 不在面板区域内
        }

        // 检查是否点击了翻页箭头（顶部）
        if (info.totalPages > 1) {
            float pageY = info.y + info.h - PANEL_PAD - info.pageBarHeight;
            if (ty >= pageY && ty <= pageY + info.pageBarHeight) {
                // 检查左箭头
                if (currentPage > 0 && tx >= info.x + PANEL_PAD - 20 && tx <= info.x + PANEL_PAD + 30) {
                    return -3;  // 上一页
                }
                // 检查右箭头
                if (currentPage < info.totalPages - 1) {
                    GlyphLayout layout = new GlyphLayout();
                    layout.setText(font, ">");
                    float arrowX = info.x + info.w - PANEL_PAD - layout.width;
                    if (tx >= arrowX - 20 && tx <= arrowX + layout.width + 20) {
                        return -4;  // 下一页
                    }
                }
            }
        }

        // 收集可见按钮索引
        int visibleCount = 0;
        for (MenuItem item : items) { if (isItemVisible(item)) visibleCount++; }
        int[] visibleIndices = new int[visibleCount];
        int idx = 0;
        for (int i = 0; i < items.length; i++) {
            if (isItemVisible(items[i])) {
                visibleIndices[idx++] = i;
            }
        }

        float contentTop = info.y + info.h - PANEL_PAD - (info.pageBarHeight > 0 ? info.pageBarHeight + 4 : 0);
        for (int j = info.startIdx; j < info.endIdx; j++) {
            int itemIdx = visibleIndices[j];
            int localIdx = j - info.startIdx;
            int row = localIdx / info.cols;
            int col = localIdx % info.cols;

            float bx = info.x + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = contentTop - (row + 1) * BTN_H - row * BTN_GAP;

            if (tx >= bx && tx <= bx + BTN_W && ty >= by && ty <= by + BTN_H) {
                return itemIdx;
            }
        }

        return -1;
    }

    private int hitTestButton(float tx, float ty) {
        return hitTestPanel(tx, ty);
    }

    private void pressButton(int index) {
        if (index < 0 || index >= items.length) return;
        MenuItem item = items[index];

        // 长按模式（NUM5/START）：第一次按下模拟 keydown 不释放（再按一次释放）
        if (item.keycode == Keys.NUM_5 || item.keycode == -141) {
            if (!holdKeyHeld) {
                holdKeyHeld = true;
                holdKeyType = (item.keycode == Keys.NUM_5) ? HoldKeyType.NUM5 : HoldKeyType.START;
                if (holdKeyType == HoldKeyType.NUM5) {
                    if (kbInput != null) {
                        kbInput.setSimulatedKeyState(Keys.NUM_5, true);
                    }
                } else {
                    sendStartKey(true);
                }
                expanded = false; // 关闭菜单面板，仅保留浮动图标
                Gdx.app.log("FloatingMenu", holdKeyType + " long-press: DOWN, overlay shown");
            } else {
                releaseHoldKey();
                Gdx.app.log("FloatingMenu", "long-press: UP, overlay hidden");
            }
            return;
        }

        if (item.keycode == -100 || item.keycode == -130 || item.keycode == -140) return; // Toggle/action 类型在 touchUp 处理

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

        if (item.keycode == -100 || item.keycode == -130 || item.keycode == -140) {
            handleToggle(item);
            return;
        }

        // 长按模式（NUM5/START）是切换式，不在 touchUp 时释放
        if (item.keycode == Keys.NUM_5 || item.keycode == -141) {
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
                } else if (item.keycode == -130) {
                    // Music Player entry
                    MainState current = ((MainController) mainController).getCurrentState();
                    if (current instanceof bms.player.beatoraja.select.MusicSelector) {
                        if (((bms.player.beatoraja.select.MusicSelector) current).getBarManager().getSelected() instanceof bms.player.beatoraja.select.bar.SongBar) {
                            ((MainController) mainController).changeState(MainState.MainStateType.MUSICPLAYER);
                        }
                    }
                } else if (item.keycode == -140) {
                    // Player Rating entry - show in WebView via AndroidLauncher
                    showPlayerRating();
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
        // items 排列：0-13通用 + 14Rating + 15-26频谱调整(X/Y/W/H各3个) + 27-29 Controller Reset
        items[15 + field * 3].label = prefix + " " + value;
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
            items[13 + i * 3].label = prefix + " " + value;
        }
    }

    // ─────────────────── 玩家实力表 ───────────────────

    private PlayerRatingService ratingService;

    private void showPlayerRating() {
        if (kbInput == null || !(kbInput.getMainController() instanceof MainController)) {
            Gdx.app.log("FloatingMenu", "Cannot show rating: MainController not available");
            return;
        }
        MainController mc = (MainController) kbInput.getMainController();

        // Initialize service lazily
        if (ratingService == null) {
            ratingService = new PlayerRatingService();
        }

        try {
            String json = ratingService.computeRating(mc);

            Class<?> clazz = Class.forName("com.starxh.beatoraja.android.AndroidLauncher");
            java.lang.reflect.Method method = clazz.getMethod("showRatingWebView", String.class);
            method.invoke(null, json);
        } catch (Exception e) {
            Gdx.app.log("FloatingMenu", "Failed to show rating: " + e.getMessage());
        }
    }

    // ─────────────────── 资源释放 ───────────────────

    public void dispose() {
        if (iconTexture != null) { iconTexture.dispose(); iconTexture = null; }
        if (whitePixel != null)  { whitePixel.dispose();  whitePixel = null;  }
    }
}
