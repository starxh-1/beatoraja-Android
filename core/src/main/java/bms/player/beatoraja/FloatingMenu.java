package bms.player.beatoraja;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;

import bms.player.beatoraja.input.KeyBoardInputProcesseor;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.rating.PlayerRatingService;
import bms.player.beatoraja.skin.Skin;
import bms.player.beatoraja.skin.SkinAdjustModel;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.SkinLoader;
import bms.player.beatoraja.skin.SkinType;
import com.starxh.beatoraja.InGameSpectrumConfig;

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

    private boolean expanded = false;
    private boolean visible = true;                     // PLAY 状态时隐藏
    private boolean selectMode = false; // 是否为 MusicSelect 界面
    private boolean keyConfigMode = false; // 是否为 KeyConfig 界面
    private boolean skinSelectMode = false; // 是否为 SkinSelect 界面（皮肤选择/配置）
    private boolean isPlayMode = false; // 是否为 Play 界面
    /** 是否为 Practice 模式（PLAY 界面且 resource.getPlayMode()==PRACTICE）。
     *  Practice 下浮动图标<b>常驻显示</b>（不参与 PLAY 的自动隐藏），
     *  并以 {@link #RESIDENT_ICON_ALPHA} 的不透明度绘制 —— 仅对 practice 生效，
     *  普通游玩 / AUTOPLAY / REPLAY 不受影响。 */
    private boolean practiceMode = false;
    /**
     * 是否为 AUTOPLAY 模式（PLAY 界面且 resource.getPlayMode().mode == AUTOPLAY）。
     * 与 Practice 同属「图标常驻」模式（见 {@link #iconResident()}）——
     * AUTOPLAY 下皮肤调整窗口是唯一的额外操作入口，图标必须常驻可点。
     * <p>🔴 判定只能用 {@code resource.getPlayMode().mode}：{@code BMSPlayer.getMode()}
     * 返回的是谱面类型（7KEYS/5KEYS），且 {@code resource} 是长命对象 —— 在非 PLAY
     * 状态读到的还是上一次的 mode，所以必须同时 gate 在 PLAY 状态。</p>
     */
    private boolean autoplayMode = false;
    /** 常驻模式（Practice / AUTOPLAY）下浮动图标的绘制不透明度（30%，常驻但不抢视线） */
    private static final float RESIDENT_ICON_ALPHA = 0.3f;
    /**
     * RESULT / COURSERESULT 界面模式：浮动图标点击后<b>不展开菜单面板</b>，
     * 而是直接弹出 7K 键位覆盖层（{@link #openDirectKeyOverlay()}），
     * 覆盖层摆放跟随浮动图标位置（与菜单面板同规则）。由 MainController 在状态切换时设置。
     */
    private boolean resultMode = false;
    /** Play 模式时：距上次交互超过此时间则自动隐藏图标（秒） */
    private static final float HIDE_DELAY = 0f;
    /** Play 模式时：距上次交互已过时间（秒） */
    private float sinceLastInteraction = 0f;
    /** Play 模式时：图标是否因超时被隐藏（点击图标区域可重新显示） */
    private boolean playIconHidden = false;

    // ─── In-Game Spectrum 调整页（独立模态页，不参与通用分页）───
    /**
     * 调整页是否打开。<b>仅 PLAY 界面可进入</b>：入口项 keycode 为
     * {@link #SPECTRUM_ENTRY_KEYCODE}，被 {@link #isItemVisible} 限制为只有
     * isPlayMode 时才出现；离开 PLAY（setPlayMode(false)）时强制收起，
     * 避免面板残留在结果等界面。
     */
    private boolean spectrumAdjustOpen = false;
    /** 当前编辑的四个值 X/Y/W/H（进入页面时从"生效值"载入，编辑期间以这份为准） */
    private final int[] spectrumValues = new int[4];
    /** 正在长按的字段（-1 = 没有）与方向（-1 / +1） */
    private int spectrumHoldField = -1;
    private int spectrumHoldDir = 0;
    private long spectrumHoldStartNs = 0;
    private long spectrumHoldLastRepeatNs = 0;
    private static final long SPECTRUM_HOLD_DELAY_NS = 300_000_000L;   // 按住 300ms 后开始连发
    private static final long SPECTRUM_HOLD_REPEAT_NS = 60_000_000L;   // 连发间隔
    private static final long SPECTRUM_HOLD_ACCEL_NS = 400_000_000L;   // 每 400ms 加速一倍
    private static final int SPECTRUM_HOLD_MAX_MULT = 64;
    /** In-Game Spectrum 调整页入口项的 keycode（isItemVisible 用它做"仅 PLAY"判定） */
    private static final int SPECTRUM_ENTRY_KEYCODE = -135;
    /**
     * 皮肤调整窗口入口项的 keycode。
     * <p>{@link #isItemVisible} 用「仅 PLAY 且 AUTOPLAY」把它锁在自动演奏界面 ——
     * 手动游玩时调皮肤没有意义（而且窗口会挡视线）。</p>
     */
    private static final int SKIN_ADJUST_KEYCODE = -136;
    /** Walkure（玩家实力表）按钮的 keycode —— 也作为 Show FPS 在 PLAY 界面的占位锚点 */
    private static final int WALKURE_KEYCODE = -140;
    /** 每个指针当前按下的频谱单元格编码（row*10+col，-1 = 无），仅用于按下高亮 */
    private final int[] pointerSpectrumCell = new int[20];

    // ─── 频谱调整页布局常量 ───
    private static final int SPECTRUM_COLS = 3;      // 值 / [-] / [+]
    private static final int SPECTRUM_ROWS = 4;      // X / Y / W / H
    private static final float SPECTRUM_TITLE_H = 44;
    /** 编码：row*10 + col，col: 0=值 1=[-] 2=[+]；特殊值见 SPECTRUM_HIT_* */
    private static final int SPECTRUM_HIT_NONE = -1;    // 面板内但没点到按钮
    private static final int SPECTRUM_HIT_OUTSIDE = -2; // 面板外
    private static final int SPECTRUM_HIT_BACK = -3;    // 标题栏返回

    // ─── 皮肤调整窗口（AUTOPLAY 中叠加在游玩界面上，<b>不切状态</b>）───
    /**
     * 皮肤调整窗口是否打开。宿主界面为 PLAY（仅 AUTOPLAY）或 MUSICSELECT
     * （入口 keycode 为 {@link #SKIN_ADJUST_KEYCODE}，被 {@link #isItemVisible} 限制）。
     * <p>与频谱页同构的独立模态页：打开期间 {@link #expanded} 保持 true，
     * 窗口内绘制与命中判定共用同一套矩形（见 {@link #hitTestSkinAdjustPage}）。</p>
     */
    private boolean skinAdjustOpen = false;

    /**
     * 开窗时宿主界面的皮肤类型（= 那一刻 {@link #skinAdjustType()} 的结果）。
     * <p>🔴 关窗补做重载时必须比对它 —— 不能只看 {@code skinAdjustType() != null}：
     * {@code MainController.changeState} 在 :389 就把 {@code current} 换成新状态，
     * 而窗口收尾是 :480 的 {@code setSelectMode(false)} 触发的 —— 那一刻
     * {@code getCurrentState()} 已经是新界面，「选曲开着窗口直接进 PLAY」会让
     * {@code skinAdjustType()} 返回 PLAY 的类型，于是白加载一整张 PLAY 皮肤、
     * 还把 {@code BMSPlayer} 刚 {@code create()/prepare()} 好的皮肤换掉。</p>
     * <p>用<b>类型</b>而非状态对象做同一性判断：PLAY→PLAY（重开 / 重试，见
     * {@code BMSPlayer:978}、{@code MusicResult:228}）会重建 {@code BMSPlayer}，
     * 那时窗口仍开着、宿主类型也没变，补做重载是对的。</p>
     */
    private SkinType skinAdjustHostType;

    /** 窗口宽（逻辑像素）—— 固定，不随内容/项数变化，否则换皮肤时面板会跳高度 */
    private static final float SKIN_PANEL_W = 620;
    /** 窗口高（逻辑像素）—— 固定 */
    private static final float SKIN_PANEL_H = 696;
    /** 标题栏高度（与 SPECTRUM_TITLE_H 同值） */
    private static final float SKIN_TITLE_H = 44;
    /** 标题栏右侧关闭按钮宽度（高度 = 标题栏高度，即 64×44） */
    private static final float SKIN_CLOSE_W = 64;
    /** 皮肤行高度（显示 `< 皮肤名 >`，见设计稿 §3） */
    private static final float SKIN_ROW_H = 72;
    /** 皮肤行内左右翻页按钮宽度 */
    private static final float SKIN_ROW_NAV_W = 64;
    /** 参数行高度 / 行间距 / 每页行数 */
    private static final float SKIN_PARAM_ROW_H = 72;
    private static final float SKIN_PARAM_ROW_GAP = 8;
    private static final int SKIN_PARAM_ROWS = 6;
    /** 参数区总高：6×72 + 5×8 = 472（设计稿 §3 的竖排验算） */
    private static final float SKIN_PARAM_AREA_H =
            SKIN_PARAM_ROWS * SKIN_PARAM_ROW_H + (SKIN_PARAM_ROWS - 1) * SKIN_PARAM_ROW_GAP;
    /** 页码栏高度 */
    private static final float SKIN_PAGE_BAR_H = 36;
    /** 参数行的列宽（名称 / 值 / [-] / [+]）与列间距，合计 = 内容宽 572 */
    private static final float SKIN_COL_NAME_W = 280;
    private static final float SKIN_COL_VALUE_W = 140;
    private static final float SKIN_COL_BTN_W = 64;
    private static final float SKIN_COL_GAP = 8;
    /** 参数行 {@code [−]} / {@code [+]} 的按钮高度（比整行矮一点，留出按压反馈的呼吸空间） */
    private static final float SKIN_BTN_H = 48;
    /** 参数改动后等待多久才真正重载皮肤（与皮肤预览的 PREVIEW_RELOAD_DELAY_MS 同值） */
    private static final long SKIN_RELOAD_DELAY_MS = 120;
    /**
     * 参数行长按连发的步长阶梯。
     * <p>不能复用频谱页那套「每 400ms 倍速」：60ms 间隔 × 最大 64 倍，走完 offset 的
     * ±9999 要约 9.4 秒。这里改成按<b>按住总时长</b>直接跳档 —— 密集微调用 1，
     * 粗调用 10 / 100，极端值一步到位用 1000。</p>
     */
    private static final long SKIN_HOLD_DELAY_NS = 400_000_000L;    // 按住 400ms 后开始连发
    private static final long SKIN_HOLD_REPEAT_NS = 60_000_000L;   // 连发间隔
    private static final long SKIN_STEP_TIER1_NS = 1_000_000_000L; // 1s 起，步长 10
    private static final long SKIN_STEP_TIER2_NS = 2_000_000_000L; // 2s 起，步长 100
    private static final long SKIN_STEP_TIER3_NS = 4_000_000_000L; // 4s 起，步长 1000
    /**
     * 窗口左上角（逻辑像素）—— <b>窗口自己的位置</b>，与浮动图标/菜单面板完全无关。
     * <p>独立浮窗语义：首次打开居中，之后拖动到哪就停在哪（关闭再开仍回原位），
     * 位置持久化留给阶段 3。三个字段一起用：{@link #skinPanelPlaced}=false 表示
     * 还没定过位，此时按屏幕居中初始化。</p>
     */
    private float skinPanelX = 0f, skinPanelY = 0f;
    private boolean skinPanelPlaced = false;
    /** 贴边留白：窗口可以拖到接近屏幕边缘，但完全不贴边（否则边框看不见） */
    private static final float SKIN_PANEL_MARGIN = 10;
    /**
     * 窗口最多占逻辑画布的比例 —— 这是「能自由拖动」的硬前提。
     * <p>🔴 逻辑坐标不是固定的 1080p，而是<b>当前皮肤自身的宽高</b>（内置皮肤就是
     * 1280×720）。按设计尺寸 620×696 直接摆上去会顶满 720 的高度，纵向只剩几像素可拖，
     * 表现就是「窗口拖不动」。所以超过这两个比例时整体等比缩小
     * （1080p 及以上画布不受影响，仍是 1 倍）。</p>
     */
    private static final float SKIN_MAX_W_RATIO = 0.60f;
    private static final float SKIN_MAX_H_RATIO = 0.80f;
    /**
     * 拖动中：窗口位置 = {@link #skinDragStartX} + (当前手指 - {@link #skinDragAnchorX})。
     * <p>用「拖动起点 + 本次位移」而非逐帧累加位移 —— 位移被屏幕边界夹住之后，
     * 手指回到原处仍能精确还原，不会产生累积漂移。</p>
     */
    private boolean skinDragging = false;
    private int skinDragPointer = -1;
    private float skinDragAnchorX = 0f, skinDragAnchorY = 0f;
    private float skinDragStartX = 0f, skinDragStartY = 0f;

    /** 参数清单当前页（0 起） */
    private int skinPage = 0;
    /**
     * 皮肤重载请求：参数改动走去抖（{@link #SKIN_RELOAD_DELAY_MS}），
     * 换皮肤走 {@link #skinReloadImmediate} 立即重载 —— 换皮肤是显式操作，不该等。
     */
    private long skinReloadRequestTime = -1;
    private boolean skinReloadImmediate = false;
    /**
     * 参数行连发状态（{@code skinHoldSlot < 0} = 没有连发）。
     * <p>同一时刻只认一个：面板上多指同时按两个 {@code [+]} 没有实际意义，
     * 按下高亮也只需要这一处状态就够。</p>
     */
    private int skinHoldSlot = -1;
    private int skinHoldCol = 0;
    private int skinHoldPointer = -1;
    private long skinHoldStartNs = 0;
    private long skinHoldLastRepeatNs = 0;
    /**
     * 参数行<b>名称</b>列的裁剪缓存（只在「页 + 皮肤」变化时失效）。
     * <p>值列故意不缓存 —— 本窗口会就地改它，缓存就需要额外维护一批失效点，
     * 而数值字符串很短，每帧重新测量几乎无开销。</p>
     */
    private final String[] skinRowNameCache = new String[SKIN_PARAM_ROWS];
    private final boolean[] skinRowTextReady = new boolean[SKIN_PARAM_ROWS];
    private int skinTextCachePage = Integer.MIN_VALUE;
    private SkinHeader skinTextCacheHeader;
    /** 名称列裁剪缓存生效时的窗口缩放 —— 缩放变了（换分辨率）裁剪宽度也得重算 */
    private float skinTextCacheScale = -1f;
    /**
     * 模型变化监听器：只在窗口打开期间注册（{@link #enterSkinAdjust} /
     * {@link #exitSkinAdjust}）。注册早于 {@code model.setType()} 的话，
     * 开窗时重建清单写入的默认值会白白触发一次全量皮肤重载。
     */
    private final SkinAdjustModel.ChangeListener skinModelListener = this::onSkinModelChanged;

    /** 皮肤调整窗口命中判定编码（>=0 预留给后续阶段的参数行 slot*10+col） */
    private static final int SKIN_HIT_NONE = -1;      // 面板内空白
    private static final int SKIN_HIT_OUTSIDE = -2;   // 面板外
    private static final int SKIN_HIT_CLOSE = -4;     // 标题栏右侧关闭
    private static final int SKIN_HIT_TITLE = -5;     // 标题栏空白 → 拖动
    private static final int SKIN_HIT_PREV_SKIN = -6; // 皮肤行左翻
    private static final int SKIN_HIT_NEXT_SKIN = -7; // 皮肤行右翻
    private static final int SKIN_HIT_PREV_PAGE = -8; // 页码栏左翻
    private static final int SKIN_HIT_NEXT_PAGE = -9; // 页码栏右翻

    // ─── 分页 ───
    private static final int ITEMS_PER_PAGE = 12;  // 每页12个：2列×6行普通，或3列×4行频谱调整页
    private int currentPage = 0;

    // ─── 纹理 ───
    private Texture iconTexture;
    private Texture whitePixel;
    private BitmapFont font; // 用于 hitTestPanel 计算文字宽度

    // ─── 按钮定义 ───
    private static class MenuItem {
        /** playInsertBefore 的默认值：不在 PLAY 界面改变顺序 */
        static final int NO_PLAY_ORDER = Integer.MIN_VALUE;
        String label;
        final int keycode;
        final boolean isToggle;
        final boolean showOnSelect;       // 是否在 MusicSelect 界面显示
        final boolean showOnKeyConfig;    // 是否在 KeyConfig 界面显示
        final boolean showOnPlay;         // 是否在 Play 界面显示
        final boolean showOnSkinSelect;   // 是否在 SkinSelect 界面显示（默认 false，避免误显示）
        /**
         * 仅 PLAY 界面的顺序覆盖：填某个 keycode 时，本项在 PLAY 界面被插到该项之前；
         * 其他界面一律保持 {@link #items} 数组里的原顺序。
         * 用于"某按钮在选曲界面位置不变、但在 PLAY 界面要挪到别处"这类需求。
         */
        final int playInsertBefore;
        MenuItem(String label, int keycode) { this(label, keycode, false, true, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle) { this(label, keycode, isToggle, true, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect) { this(label, keycode, isToggle, showOnSelect, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig) { this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay) {
            this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, false);
        }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay, boolean showOnSkinSelect) {
            this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, showOnSkinSelect, NO_PLAY_ORDER);
        }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay, boolean showOnSkinSelect, int playInsertBefore) {
            this.label = label; this.keycode = keycode; this.isToggle = isToggle;
            this.showOnSelect = showOnSelect; this.showOnKeyConfig = showOnKeyConfig;
            this.showOnPlay = showOnPlay; this.showOnSkinSelect = showOnSkinSelect;
            this.playInsertBefore = playInsertBefore;
        }
    }

    // 频谱调整：In-Game Spectrum 入口(-135) 打开独立模态页（drawSpectrumPage /
    // hitTestSpectrumPage），页内按钮不与通用分页列表共享索引空间。
    private final MenuItem[] items = {
        // ── 通用按钮（第1页）───────────────────────
        // 构造参数: (label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, showOnSkinSelect)
        new MenuItem("Touch Key: ON",  -100, true,  true, false, true, false),
        // Walkure 在 PLAY 界面隐藏：那一格由 Show FPS 占用（见其 playInsertBefore）。
        new MenuItem("Walkure",   WALKURE_KEYCODE, false, true, false, false, false),
        new MenuItem("Update Song",   Keys.F2, false, true, false, true, false),
        new MenuItem("Music Player",   -130, false, true, false, true, false),
        new MenuItem("Skin Select",   Keys.F12, false, true, false, true, false),
        new MenuItem("Key Config", Keys.NUM_6, false, true, false, true, true),
        new MenuItem("PLAYOPTION 1", Keys.NUM_5, false, true, false, false, true),
        new MenuItem("Backspace",        Keys.BACKSPACE, false, false, false, false, false),
        new MenuItem("ESC",   Keys.ESCAPE, false, false, true, true, true),
        new MenuItem("Enter",            Keys.ENTER, false, false, true, true, true),
        new MenuItem("PLAYOPTION 2",    -141, false, true, false, false, true),
        new MenuItem("^ UP",        Keys.UP, false, true, true, true, true),
        new MenuItem("v DOWN",      Keys.DOWN, false, true, true, true, true),
        new MenuItem("< LEFT",      Keys.LEFT, false, true, true, true, true),
        new MenuItem("> RIGHT",     Keys.RIGHT, false, false, true, true, true),
        // Show FPS：数组里排在末尾（选曲等界面维持原位置不变）；
        // PLAY 界面用 playInsertBefore 占用 Walkure 那一格（Walkure 在 PLAY 已隐藏）。
        new MenuItem("Show FPS",      Keys.F1, false, true, false, true, false, WALKURE_KEYCODE),
        // ── In-Game Spectrum 调整入口（仅 PLAY 界面；见 isItemVisible）──
        // 调整界面是独立模态页（3列×4行，见 drawSpectrumPage/hitTestSpectrumPage），
        // 不再把 12 个 +/- 按钮塞进通用分页列表 —— 那套做法混用了"可见项索引"与
        // "数组索引"，导致列错位、按 Y 的 ± 会把 X 的 + 覆盖掉。
        // showOnSelect=false + isItemVisible 的"仅 PLAY"硬规则共同把它限制在游玩界面。
        new MenuItem("In-Game Spectrum", SPECTRUM_ENTRY_KEYCODE, false, false, false, true, false),
        // ── 皮肤调整窗口入口（仅 PLAY + AUTOPLAY；见 isItemVisible）──
        // 与 In-Game Spectrum 同构：独立模态页 + 可拖动标题栏 + 关闭时统一落盘。
        // 打开期间不切状态，热重载直接换掉宿主界面上的 Skin 引用
        // （docs/autoplay-skin-window-design.md §7）。
        // showOnSelect = true：选曲界面也能调（调的是 MUSIC SELECT 皮肤）；
        // PLAY 那一侧由 isItemVisible 的硬规则再收紧成「仅 AUTOPLAY」。
        new MenuItem("Skin Adjust", SKIN_ADJUST_KEYCODE, false, true, false, true, false),

        // ── Controller Reset（仅KeyConfig模式）──
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

    // ─── 按键覆盖层（7K 键 1~7） ───
    /**
     * 覆盖层模式：NONE=未激活，NUM5=模拟 NUM5 长按，START=模拟 START 长按，
     * DIRECT=结果界面直接打开（不模拟任何长按键，只需点击图标）。
     */
    private enum HoldKeyType { NONE, NUM5, START, DIRECT }
    /** 当前正在长按模拟的按键类型 */
    private HoldKeyType holdKeyType = HoldKeyType.NONE;
    /** 是否处于长按状态（按下未释放），决定 7K 覆盖层是否显示 */
    private boolean holdKeyHeld = false;
    /** 7KEYS 默认标签（仅在无法读取 kbInput 配置时回退使用） */
    private static final int[] SEVEN_KEYS_KEYCODES_DEFAULT = {
        Keys.Z, Keys.S, Keys.X, Keys.D, Keys.C, Keys.F, Keys.V
    };
    /**
     * 覆盖层按钮数 = 7 个键，按钮索引 i 直接就是核心层 key index（键 i+1 → 槽位 i）。
     * 曾额外在最左加过一个 scratch 按钮，已回退：PLAYOPTION 的调整用不到它，
     * 且多余的槽位映射（scratch = 槽位 7）容易与数字键绑定的坑混淆。
     */
    private static final int K7_BUTTON_COUNT = 7;
    /** 缓存覆盖层各按钮显示名称（[0..6] = 键 1..7），避免每帧 Keys.toString 分配 String 触发 GC 压力 */
    private final String[] cached7KKeyNames = new String[K7_BUTTON_COUNT];
    /** 缓存覆盖层各按钮当前 keycode（-1 表示未初始化），用于检测配置变更以刷新名称 */
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

    /** 浮动菜单位置配置：0=上中 1=上右 2=下中 3=下右（读取失败时回退 0） */
    private int floatingMenuPosition() {
        Config config = null;
        if (kbInput != null && kbInput.getMainController() instanceof MainController) {
            config = ((MainController) kbInput.getMainController()).getConfig();
        }
        return (config != null) ? config.getFloatingMenuPosition() : 0;
    }

    private void updateIconPosition() {
        int pos = floatingMenuPosition();
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
        // 界面切走（或菜单被隐藏）时收尾：把仍"按着"的模拟按键释放掉。
        // 触摸事件不会再来（touchUp 不再到达），不释放就会把核心层槽位留在 true。
        if (!v && visible) {
            releaseStuckPresses();
        }
        this.visible = v;
        // 移除 if (!v) expanded = false; 以保持展开状态
    }

    /**
     * 界面切换时的收尾：释放所有"只按下、没抬起"的模拟按键。
     *
     * <p>这是个容易漏的坑：按下走 touchDown（立即发 keyChanged(true)），释放走 touchUp——
     * 一旦界面被切走，touchUp 永远不会到来，核心层槽位就永久留在 true，
     * 表现为"进入下一界面后某个 lane 一直亮着，必须重新按一下对应物理键才复位"。</p>
     */
    private void releaseStuckPresses() {
        // 1) 覆盖层（NUM5/START 长按）及其内部按住的 7K 键
        if (holdKeyHeld) {
            releaseHoldKey();
        }
        // 2) 展开面板里按住尚未抬起的菜单项
        for (int p = 0; p < pointerPressedIndex.length; p++) {
            int idx = pointerPressedIndex[p];
            if (idx >= 0 && idx < items.length && kbInput != null) {
                // 内部带 keycode 范围校验，伪 keycode（-100/-130 等）会被安全忽略
                kbInput.setSimulatedKeyState(items[idx].keycode, false);
            }
            pointerPressedIndex[p] = -1;
            pointer7KKey[p] = -1;
            if (p < pointerConsuming.length) {
                pointerConsuming[p] = false;
            }
        }
    }

    public boolean isVisible() { return visible; }

    /** 设置是否为 Select 界面（影响按钮过滤） */
    public void setSelectMode(boolean selectMode) {
        // 离开选曲界面（进入 PLAY/RESULT 等）时收尾。必须放在这里：PLAY 下浮动菜单
        // 往往仍然 visible（showFloatingMenuInPlay 默认开），setVisible 不会触发，
        // 覆盖层按着没抬起的 PLAYOPTION 按键就会一路带进 play（某个 lane 常亮）。
        if (this.selectMode && !selectMode) {
            releaseStuckPresses();
            // 选曲界面的皮肤调整窗口（调 MUSIC SELECT 皮肤）也必须在这里收掉。
            // 🔴 不能只靠 setPlayMode(false)：从选曲进 PLAY 时 setPlayMode(true) 走的是
            //    另一条分支，不会关窗；窗口残留下去的话 model 的 type 还是 MUSIC_SELECT，
            //    下一次重载会把选曲皮肤装到 BMSPlayer 上。
            if (skinAdjustOpen) exitSkinAdjust();
        }
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
        } else {
            // 离开 PLAY（进结果、回选曲等）时强制收起模态页并落盘，
            // 否则模态页会残留在其他界面。MainController 只在状态切换时调用本方法。
            if (spectrumAdjustOpen) exitSpectrumAdjust();
            if (skinAdjustOpen) exitSkinAdjust();
        }
    }

    /** 设置是否为 Practice 模式（图标常驻显示 + 30% 不透明度，仅对 practice 生效） */
    public void setPracticeMode(boolean practiceMode) {
        this.practiceMode = practiceMode;
        if (practiceMode) {
            // 常驻显示：清掉 PLAY 模式的超时隐藏状态，避免进入 practice 时图标已被标记隐藏
            sinceLastInteraction = 0f;
            playIconHidden = false;
        }
    }

    /** 设置是否为 AUTOPLAY 模式（图标常驻显示 + 30% 不透明度，仅对 autoplay 生效） */
    public void setAutoplayMode(boolean autoplayMode) {
        this.autoplayMode = autoplayMode;
        if (autoplayMode) {
            // 常驻显示：清掉 PLAY 模式的超时隐藏状态（HIDE_DELAY=0 会立刻把图标标成隐藏）
            sinceLastInteraction = 0f;
            playIconHidden = false;
        }
    }

    /**
     * 是否为「图标常驻」模式（Practice / AUTOPLAY）。
     * <p>这两类游玩下浮动图标不参与 PLAY 的自动隐藏，并以
     * {@link #RESIDENT_ICON_ALPHA} 的不透明度绘制 —— 因为它们都依赖浮动菜单作为
     * 唯一的额外操作入口（Practice：练习菜单；AUTOPLAY：皮肤调整窗口）。</p>
     */
    private boolean iconResident() {
        return practiceMode || autoplayMode;
    }

    /**
     * 设置是否为 RESULT / COURSERESULT 界面。
     * <p>为 true 时：图标点击<b>不展开菜单面板</b>，直接弹出按键覆盖层
     * （7K 键 1~7，供结果界面的 CHANGE_GRAPH / REPLAY_* / OK 等键位使用）。
     * <p>为 false 时（离开结果界面）：关闭可能还开着的覆盖层并释放按住的键，
     * 避免模拟按键残留在选曲界面。
     */
    public void setResultMode(boolean resultMode) {
        this.resultMode = resultMode;
        if (resultMode) {
            // 结果界面不显示菜单面板：收起残留的展开状态与两个模态页
            if (spectrumAdjustOpen) exitSpectrumAdjust();
            if (skinAdjustOpen) exitSkinAdjust();
            expanded = false;
        } else if (holdKeyType == HoldKeyType.DIRECT) {
            releaseHoldKey();
        }
    }

    /** 结果界面直接打开按键覆盖层：无需长按 NUM5/START，点击图标即可 */
    private void openDirectKeyOverlay() {
        holdKeyHeld = true;
        holdKeyType = HoldKeyType.DIRECT;
        expanded = false;
        // 立刻算一次布局：否则"图标按下"与"按键按下"落在同一帧时，
        // 命中判定会用上一帧（甚至是别的界面）的旧布局
        calculate7KOverlayLayout();
        Gdx.app.log("FloatingMenu", "result key overlay: open (7KEYS)");
    }

    /** 判断按钮是否在当前界面显示 */
    private boolean isItemVisible(MenuItem item) {
        if (selectMode && !item.showOnSelect) return false;
        if (keyConfigMode && !item.showOnKeyConfig) return false;
        if (skinSelectMode && !item.showOnSkinSelect) return false;
        if (isPlayMode && !item.showOnPlay) return false;
        // In-Game Spectrum 调整入口：只在 PLAY 界面出现。
        // 上面四条是"某模式生效时要求对应标记"，未设置任何模式的状态（RESULT 等）
        // 会全部放行，所以"仅 PLAY"必须是一条独立硬规则。
        if (item.keycode == SPECTRUM_ENTRY_KEYCODE && !isPlayMode) return false;
        // 皮肤调整窗口入口：只有两个宿主界面放行 ——
        // ① PLAY 且 AUTOPLAY：手动游玩时调皮肤没有意义，而且窗口会挡住谱面；
        // ② 选曲界面：调的是 MUSIC SELECT 皮肤，与 PLAY 侧互不干扰。
        // 同样是一条独立硬规则（理由同上）。
        if (item.keycode == SKIN_ADJUST_KEYCODE && !((isPlayMode && autoplayMode) || selectMode)) return false;
        return true;
    }

    /**
     * 组装当前界面"可见按钮"的原始索引序列。
     *
     * <p>顺序 = {@link #items} 数组顺序；仅 PLAY 界面额外应用
     * {@link MenuItem#playInsertBefore} 的顺序覆盖：声明该项的按钮<b>占用锚点项的位置</b>，
     * 这样"选曲等界面位置不变、PLAY 界面换个位置"的需求不必改数组顺序。</p>
     *
     * <p>锚点项本身在 PLAY 界面被隐藏时，它的<b>位置依然有效</b>
     * （例：Show FPS 占用已隐藏的 Walkure 那一格）。</p>
     *
     * <p>绘制（drawPanel）与命中判定（hitTestPanel）<b>必须</b>共用本方法，
     * 否则会出现"看到的按钮"和"点到的按钮"不一致。</p>
     */
    private int[] buildVisibleIndexOrder() {
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            MenuItem item = items[i];
            if (isPlayMode) {
                // 走到锚点位置：先把声明"占用这个位置"的可见项放进来。
                // 放在可见性判定之前，所以锚点自身被隐藏时其位置仍然可用。
                for (int k = 0; k < items.length; k++) {
                    if (items[k].playInsertBefore == item.keycode && isItemVisible(items[k])) {
                        order.add(k);
                    }
                }
                // 带顺序覆盖的项不在数组原位出现（已由锚点位置插入）
                if (item.playInsertBefore != MenuItem.NO_PLAY_ORDER) continue;
            }
            if (!isItemVisible(item)) continue;
            order.add(i);
        }

        int[] res = new int[order.size()];
        for (int j = 0; j < res.length; j++) res[j] = order.get(j);
        return res;
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
        // 该纹理 96×96，绘制到 ICON_SIZE=77 → 【缩小】绘制。libGDX 的 Texture 默认 Nearest，
        // 缩小时会丢像素、圆角边缘抖动；显式 Linear（缩小走 minFilter，这里两个都设）。
        // 注意：本纹理是 app 自绘 UI，不经过 SkinObjectRenderer，SkinTextureFilterPolicy 管不到。
        iconTexture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
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

        // Play 模式：无操作则自动隐藏图标（Practice / AUTOPLAY 常驻显示，不参与自动隐藏）
        if (isPlayMode && !iconResident() && visible && !expanded) {
            sinceLastInteraction += delta;
            if (sinceLastInteraction >= HIDE_DELAY) {
                playIconHidden = true;
            }
        }

        // 频谱调整页：长按 [-] / [+] 连发（300ms 后开始，每 60ms 一次，按住越久步长越大）
        if (spectrumAdjustOpen && spectrumHoldField >= 0 && spectrumHoldDir != 0) {
            long now = System.nanoTime();
            long elapsed = now - spectrumHoldStartNs;
            if (elapsed >= SPECTRUM_HOLD_DELAY_NS
                    && now - spectrumHoldLastRepeatNs >= SPECTRUM_HOLD_REPEAT_NS) {
                spectrumHoldLastRepeatNs = now;
                long accelSteps = (elapsed - SPECTRUM_HOLD_DELAY_NS) / SPECTRUM_HOLD_ACCEL_NS;
                int mult = 1;
                for (long i = 0; i < accelSteps && mult < SPECTRUM_HOLD_MAX_MULT; i++) {
                    mult = Math.min(mult * 2, SPECTRUM_HOLD_MAX_MULT);
                }
                spectrumStep(spectrumHoldField, spectrumHoldDir * mult);
            }
        }
        // 指针异常丢失（切后台等）：停掉连发，避免一直改值
        if (spectrumHoldField >= 0 && !Gdx.input.isTouched()) {
            stopSpectrumHold(true);
        }
        // 指针异常丢失（切后台等）：结束窗口拖动，避免窗口卡在半路
        if (skinDragging && (skinDragPointer < 0 || skinDragPointer >= pointerConsuming.length
                || !Gdx.input.isTouched(skinDragPointer))) {
            skinDragging = false;
            skinDragPointer = -1;
        }
        // 皮肤调整窗口：参数行 [-] / [+] 长按连发
        // （按住 400ms 后开始，每 60ms 一次；步长按按住总时长跳档，见 skinStepForElapsed）
        if (skinAdjustOpen && skinHoldSlot >= 0) {
            long now = System.nanoTime();
            long elapsed = now - skinHoldStartNs;
            if (elapsed >= SKIN_HOLD_DELAY_NS
                    && now - skinHoldLastRepeatNs >= SKIN_HOLD_REPEAT_NS) {
                skinHoldLastRepeatNs = now;
                skinStep(itemAt(skinModel(), skinHoldSlot), skinDirOf(skinHoldCol),
                        skinStepForElapsed(elapsed));
            }
        }
        // 指针异常丢失（切后台等）：停掉连发，避免一直改值
        if (skinHoldSlot >= 0 && !Gdx.input.isTouched()) {
            stopSkinHold();
        }
        // 皮肤调整窗口：皮肤重载。换皮肤立即（显式操作），改参数去抖 120ms
        // —— 连按时每帧重载整张皮肤会把界面拖死。
        if (skinAdjustOpen && (skinReloadImmediate
                || (skinReloadRequestTime > 0
                    && System.currentTimeMillis() - skinReloadRequestTime >= SKIN_RELOAD_DELAY_MS))) {
            skinReloadImmediate = false;
            skinReloadRequestTime = -1;
            reloadCurrentSkin();
        }

        // ─── 设置投影矩阵到逻辑坐标 ───
        sprite.setProjectionMatrix(menuProj.setToOrtho2D(0, 0, logicW, logicH));

        sprite.begin();
        // 确保使用正常的混合模式，防止皮肤（如 Note 爆发效果）残留的加算模式导致菜单发光
        sprite.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Play 模式超时隐藏：图标不绘制，但仍响应触摸
        // 皮肤调整窗口打开时同样不绘制 —— 视觉上只剩这一个独立浮窗（命中判定见 touchDown）
        boolean showIcon = !(isPlayMode && playIconHidden) && !skinAdjustOpen;

        if (showIcon) {
            // 绘制浮动图标（Practice / AUTOPLAY 用 30% 不透明度常驻显示）
            sprite.setColor(1, 1, 1, iconResident() ? RESIDENT_ICON_ALPHA : 0.55f);
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
        // 0. 独立模态页（各自的布局与命中判定），不参与通用分页列表
        if (spectrumAdjustOpen) {
            drawSpectrumPage(sprite, font);
            return;
        }
        if (skinAdjustOpen) {
            drawSkinAdjustPage(sprite, font);
            return;
        }

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

        // 收集可见按钮索引（含 PLAY 界面的顺序覆盖；绘制与命中判定同一份顺序）
        int[] visibleIndices = buildVisibleIndexOrder();

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
            // 页码
            String pageText = (currentPage + 1) + "/" + info.totalPages;
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

    // ─────────────────── In-Game Spectrum 调整页 ───────────────────

    /** 调整页是否打开（渲染层用它决定是否在其他界面也预览频谱） */
    public boolean isSpectrumAdjustOpen() {
        return spectrumAdjustOpen;
    }

    private MainController mainControllerOrNull() {
        if (kbInput == null) return null;
        Object mc = kbInput.getMainController();
        return (mc instanceof MainController) ? (MainController) mc : null;
    }

    /**
     * 进入调整页：把<b>当前生效</b>的值载入编辑状态。
     * 关键：把生效值物化进 PlayerConfig —— PlayerConfig 里 0 表示"未设置"（回退到
     * 皮肤 json），若不在进入时落定，界面会一直显示 0 且加减后语义混乱。
     */
    private void enterSpectrumAdjust() {
        MainController mc = mainControllerOrNull();
        if (mc != null) {
            int[] v = InGameSpectrumConfig.resolve(mc);
            System.arraycopy(v, 0, spectrumValues, 0, 4);
            InGameSpectrumConfig.apply(mc, v[0], v[1], v[2], v[3]);
            InGameSpectrumConfig.save(mc);
        }
        spectrumAdjustOpen = true;
        stopSpectrumHold(false);
    }

    /** 离开调整页：停止连发并落盘 */
    private void exitSpectrumAdjust() {
        spectrumAdjustOpen = false;
        stopSpectrumHold(true);
    }

    /** 单步调整：X/Y 步长 1，W/H 步长 10；改完立即应用到渲染器（不落盘，退出时统一保存） */
    private void spectrumStep(int field, int steps) {
        if (field < 0 || field >= 4 || steps == 0) return;
        int delta = (field >= 2) ? 10 : 1;
        spectrumValues[field] += delta * steps;
        MainController mc = mainControllerOrNull();
        if (mc != null) {
            InGameSpectrumConfig.apply(mc, spectrumValues[0], spectrumValues[1],
                    spectrumValues[2], spectrumValues[3]);
        }
    }

    private void stopSpectrumHold(boolean save) {
        if (spectrumHoldField >= 0 && save) {
            MainController mc = mainControllerOrNull();
            if (mc != null) {
                InGameSpectrumConfig.save(mc);
            }
        }
        spectrumHoldField = -1;
        spectrumHoldDir = 0;
        spectrumHoldStartNs = 0;
        spectrumHoldLastRepeatNs = 0;
    }

    /**
     * 调整页命中判定。返回：SPECTRUM_HIT_BACK / SPECTRUM_HIT_OUTSIDE /
     * SPECTRUM_HIT_NONE（面板内空白）/ 或 row*10+col（col: 0 值, 1 [-], 2 [+]）。
     */
    private int hitTestSpectrumPage(float tx, float ty) {
        PanelLayout info = calculateSpectrumPanelLayout();
        if (tx < info.x || tx > info.x + info.w || ty < info.y || ty > info.y + info.h) {
            return SPECTRUM_HIT_OUTSIDE;
        }
        float[] r = new float[4];
        spectrumBackRect(info, r);
        if (tx >= r[0] && tx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3]) {
            return SPECTRUM_HIT_BACK;
        }
        for (int row = 0; row < SPECTRUM_ROWS; row++) {
            for (int col = 0; col < SPECTRUM_COLS; col++) {
                spectrumCellRect(info, row, col, r);
                if (tx >= r[0] && tx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3]) {
                    return row * 10 + col;
                }
            }
        }
        return SPECTRUM_HIT_NONE;
    }

    private void drawSpectrumPage(SpriteBatch sprite, BitmapFont font) {
        final float border = 2;
        PanelLayout info = calculateSpectrumPanelLayout();
        float[] r = new float[4];
        GlyphLayout glyph = new GlyphLayout();

        // 面板背景 + 边框
        sprite.setColor(0.1f, 0.1f, 0.15f, 0.85f);
        sprite.draw(whitePixel, info.x, info.y, info.w, info.h);
        sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
        sprite.draw(whitePixel, info.x, info.y, info.w, border);
        sprite.draw(whitePixel, info.x, info.y + info.h - border, info.w, border);
        sprite.draw(whitePixel, info.x, info.y, border, info.h);
        sprite.draw(whitePixel, info.x + info.w - border, info.y, border, info.h);

        // 标题栏：左侧返回 + 居中标题
        spectrumBackRect(info, r);
        sprite.setColor(0.2f, 0.25f, 0.4f, 0.9f);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);
        font.setColor(0.6f, 0.85f, 1f, 0.95f);
        glyph.setText(font, "<");
        font.draw(sprite, "<", r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);

        String title = "In-Game Spectrum Adjust";
        font.setColor(0.85f, 0.85f, 0.9f, 0.95f);
        glyph.setText(font, title);
        float barY = info.y + info.h - PANEL_PAD - SPECTRUM_TITLE_H;
        font.draw(sprite, title, info.x + (info.w - glyph.width) / 2,
                barY + (SPECTRUM_TITLE_H + glyph.height) / 2);

        // 4 行 × 3 列：值 / [-] / [+]
        final String[] fieldNames = { "X", "Y", "W", "H" };
        for (int row = 0; row < SPECTRUM_ROWS; row++) {
            for (int col = 0; col < SPECTRUM_COLS; col++) {
                spectrumCellRect(info, row, col, r);

                // 按下高亮
                boolean pressed = false;
                for (int p = 0; p < pointerSpectrumCell.length && !pressed; p++) {
                    if (pointerSpectrumCell[p] == row * 10 + col) pressed = true;
                }
                if (pressed) {
                    sprite.setColor(0.3f, 0.5f, 0.8f, 0.95f);
                } else if (col == 0) {
                    sprite.setColor(0.16f, 0.18f, 0.26f, 0.75f);   // 值不点，颜色略暗区分
                } else {
                    sprite.setColor(0.2f, 0.2f, 0.3f, 0.7f);
                }
                sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);

                String label;
                if (col == 0) {
                    label = fieldNames[row] + ": " + spectrumValues[row];
                } else {
                    label = (col == 1) ? "[-]" : "[+]";
                }
                font.setColor(col == 0 ? 0.9f : 1f, col == 0 ? 0.95f : 1f, 1f, 0.95f);
                glyph.setText(font, label);
                font.draw(sprite, label, r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);
            }
        }

        // 底部操作提示（放在最后一行下方）
        String hint = "Y = " + (spectrumValues[1]) + "px from BOTTOM   (long-press -/+ to repeat)";
        font.setColor(0.55f, 0.6f, 0.7f, 0.9f);
        glyph.setText(font, hint);
        font.draw(sprite, hint, info.x + (info.w - glyph.width) / 2, info.y + PANEL_PAD * 0.6f);
    }

    // ─────────────────── NUM5 长按模式：7K 覆盖层 ───────────────────

    /** 7K 覆盖层缓存的布局参数（每帧重算） */
    private float k7BtnW, k7BtnH;
    private float k7PanelX, k7PanelY;
    private float k7TitleY, k7CloseY;
    /** 覆盖层块的水平中心（标题与关闭按钮据此居中；结果界面下会偏离屏幕中心） */
    private float k7CenterX;

    /** 计算按键覆盖层布局：整体高度约屏幕高度的 K7_TARGET_SCREEN_RATIO */
    private void calculate7KOverlayLayout() {
        // 总垂直空间 = 标题 + 间距 + 按钮 + 间距 + 关闭按钮
        float totalH = K7_TITLE_H + 12 + K7_CLOSE_H + 12;
        // 按钮高度 = 目标高度 - 其他元素占用
        float targetH = logicH * K7_TARGET_SCREEN_RATIO;
        k7BtnH = Math.max(60, targetH - totalH);

        // 按钮宽度：7 个键 + 6 个间隙，整体占屏幕宽度 ~55%
        float maxTotalW = logicW * 0.55f;
        k7BtnW = (maxTotalW - (K7_BUTTON_COUNT - 1) * K7_BTN_GAP) / K7_BUTTON_COUNT;
        k7BtnW = Math.max(60, Math.min(k7BtnW, 140));

        float totalW = K7_BUTTON_COUNT * k7BtnW + (K7_BUTTON_COUNT - 1) * K7_BTN_GAP;
        float blockH = K7_TITLE_H + 12 + k7BtnH + 12 + K7_CLOSE_H;

        float blockX;
        float blockTop;
        if (holdKeyType == HoldKeyType.DIRECT) {
            // 结果界面直接打开的覆盖层：跟随浮动图标摆放（与菜单面板同一套锚定规则）
            blockX = anchoredOverlayX(totalW);
            blockTop = anchoredOverlayTop(blockH);
        } else {
            // PLAYOPTION 1/2 的覆盖层：维持原有的屏幕居中
            blockX = (logicW - totalW) / 2f;
            blockTop = (logicH + blockH) / 2f;
        }

        k7PanelX = blockX;
        k7CenterX = blockX + totalW / 2f;
        k7TitleY = blockTop - K7_TITLE_H;
        k7PanelY = k7TitleY - 12 - k7BtnH;
        k7CloseY = k7PanelY - 12 - K7_CLOSE_H;
    }

    /** 覆盖层左边缘（锚定到浮动图标，规则与 {@link #anchorPanel} 一致，含屏幕边界保护） */
    private float anchoredOverlayX(float w) {
        int pos = floatingMenuPosition();
        float x = (pos == 1 || pos == 3) ? iconX + ICON_SIZE - w : iconX + (ICON_SIZE - w) / 2f;
        if (x < 10) x = 10;
        if (x + w > logicW - 10) x = logicW - w - 10;
        return x;
    }

    /** 覆盖层顶边 y 坐标（底部图标向上弹出，顶部图标向下弹出，含屏幕边界保护） */
    private float anchoredOverlayTop(float h) {
        int pos = floatingMenuPosition();
        float top = (pos >= 2) ? (iconY + ICON_SIZE + 8 + h) : (iconY - 8);
        if (top > logicH - 10) top = logicH - 10;
        if (top - h < 10) top = h + 10;
        return top;
    }

    /** 检测触摸是否在按键覆盖层的某个按钮上，返回按钮索引 0~6（= 键 1~7，也等于核心层槽位），否则返回 -1
     * @param stickyKey 当前已按下的按钮索引（-1 表示无），用于"粘住"避免手指抖动时释放按键 */
    private int hitTest7KKey(float tx, float ty, int stickyKey) {
        if (ty < k7PanelY || ty > k7PanelY + k7BtnH) return -1;

        // 先检查手指当前实际命中哪个按钮
        int actualHit = -1;
        for (int i = 0; i < K7_BUTTON_COUNT; i++) {
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

    /** 检测触摸是否在按键覆盖层的"关闭"按钮上（矩形与绘制共用 k7CenterX/k7CloseY） */
    private boolean hitTest7KClose(float tx, float ty) {
        float closeW = 180;
        float cx = k7CenterX - closeW / 2f;
        return tx >= cx && tx <= cx + closeW && ty >= k7CloseY && ty <= k7CloseY + K7_CLOSE_H;
    }

    /**
     * 发送 7K 按键状态到核心层（绕过 setSimulatedKeyState 的 release bug）。
     * keyIdx 是<b>槽位</b>索引（0~6 对应 7K 第 1~7 个 lane），由 LaneProperty 决定映射。
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
     * 释放当前覆盖层模拟的按键（NUM5 / START / 结果界面直接模式），
     * 同时关闭覆盖层并释放所有还按着的 7K 键。
     * 用于关闭按钮、图标再次点击、按钮二次点击、切界面等所有收尾路径。
     */
    private void releaseHoldKey() {
        if (holdKeyType == HoldKeyType.NUM5 && kbInput != null) {
            kbInput.setSimulatedKeyState(Keys.NUM_5, false);
        } else if (holdKeyType == HoldKeyType.START) {
            sendStartKey(false);
        }
        // 兜底：释放覆盖层里所有仍按下的键 —— 切界面/指针丢失时不会走 touchUp，
        // 不释放会把核心层 keystate 留在 true 上（结果界面会当成一直按着）
        for (int p = 0; p < pointer7KKey.length; p++) {
            if (pointer7KKey[p] >= 0) {
                send7KKey(pointer7KKey[p], false);
                pointer7KKey[p] = -1;
            }
        }
        holdKeyType = HoldKeyType.NONE;
        holdKeyHeld = false;
    }

    /** 绘制按键覆盖层：7 个键 + 标题 + 关闭按钮 */
    private void draw7KOverlay(SpriteBatch sprite, BitmapFont font) {
        calculate7KOverlayLayout();
        GlyphLayout glyph = new GlyphLayout();

        // 读取当前用户配置的键位（键 1~7，用户在 Key Config 中可改）
        int[] userKeys = (kbInput != null) ? kbInput.getKeys() : null;
        refresh7KKeyNamesCache(userKeys);

        // 标题（块上方居中）
        font.setColor(0.5f, 0.8f, 1f, 0.95f);
        String title;
        if (holdKeyType == HoldKeyType.DIRECT) {
            title = "Result Keys: 7KEYS (tap icon to close)";
        } else {
            String heldName = (holdKeyType == HoldKeyType.START) ? "START" : "NUM5";
            title = heldName + " Long-Press: 7KEYS (tap again to release)";
        }
        glyph.setText(font, title);
        font.draw(sprite, title, k7CenterX - glyph.width / 2f, k7TitleY + K7_TITLE_H * 0.7f);

        // 7 个键（按钮索引 i = 键 i+1 = 核心层槽位 i）
        for (int i = 0; i < K7_BUTTON_COUNT; i++) {
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

            // 标签（大字）：1~7
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

        // 关闭按钮（覆盖层下方，与块中心对齐）
        float closeW = 180;
        float cx = k7CenterX - closeW / 2f;
        sprite.setColor(0.6f, 0.2f, 0.2f, 0.85f);
        sprite.draw(whitePixel, cx, k7CloseY, closeW, K7_CLOSE_H);
        sprite.setColor(1, 1, 1, 0.9f);
        sprite.draw(whitePixel, cx, k7CloseY + K7_CLOSE_H - 2, closeW, 2);
        sprite.draw(whitePixel, cx, k7CloseY, closeW, 2);
        sprite.draw(whitePixel, cx, k7CloseY, 2, K7_CLOSE_H);
        sprite.draw(whitePixel, cx + closeW - 2, k7CloseY, 2, K7_CLOSE_H);

        font.setColor(1, 1, 1, 0.95f);
        String closeLabel;
        if (holdKeyType == HoldKeyType.DIRECT) {
            closeLabel = "Close";
        } else {
            closeLabel = (holdKeyType == HoldKeyType.START) ? "Release START" : "Release NUM5";
        }
        glyph.setText(font, closeLabel);
        font.draw(sprite, closeLabel, cx + (closeW - glyph.width) / 2f, k7CloseY + (K7_CLOSE_H + glyph.height) / 2f);
    }

    /**
     * 刷新覆盖层各按钮显示名称的缓存（[0..6] = 键 1..7）。
     * 仅在 keycode 实际变化时（如 Key Config 修改后）重新调用 Keys.toString，
     * 避免每帧分配短 String 触发 GC 压力。
     */
    private void refresh7KKeyNamesCache(int[] userKeys) {
        for (int i = 0; i < K7_BUTTON_COUNT; i++) {
            // 按钮 i 使用的键位槽：键 k → 槽位 k-1（与发送时的槽位索引一致）
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
        int cols;
        /**
         * 等比缩放（1 = 设计尺寸）。只有皮肤调整窗口会用到 —— 它的设计尺寸按 1080p 画布定，
         * 而逻辑坐标是「皮肤自身的宽高」（内置皮肤只有 1280×720），必须整体缩小。
         */
        float scale = 1f;
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

        res.cols = 2;
        int rows = (itemCount + res.cols - 1) / res.cols;

        res.w = res.cols * BTN_W + (res.cols - 1) * BTN_GAP + PANEL_PAD * 2;
        res.h = rows * BTN_H + (rows - 1) * BTN_GAP + PANEL_PAD * 2 + (res.pageBarHeight > 0 ? res.pageBarHeight + 4 : 0);

        anchorPanel(res);
        return res;
    }

    /** 面板锚定：跟随浮动图标的位置，并做屏幕边界保护（通用列表页与频谱调整页共用） */
    private void anchorPanel(PanelLayout res) {
        int pos = floatingMenuPosition();

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
    }

    // ─── In-Game Spectrum 调整页（独立模态页）───

    /** 频谱调整页布局：3 列（值 / [-] / [+]）× 4 行（X / Y / W / H）+ 标题栏 */
    private PanelLayout calculateSpectrumPanelLayout() {
        PanelLayout res = new PanelLayout();
        res.cols = SPECTRUM_COLS;
        res.w = SPECTRUM_COLS * BTN_W + (SPECTRUM_COLS - 1) * BTN_GAP + PANEL_PAD * 2;
        res.h = SPECTRUM_ROWS * BTN_H + (SPECTRUM_ROWS - 1) * BTN_GAP + PANEL_PAD * 2 + SPECTRUM_TITLE_H;
        anchorPanel(res);
        return res;
    }

    /**
     * 频谱调整页单个单元格的矩形（draw 与 hitTest 共用同一份计算，避免两者不一致）。
     *
     * @param row 0..3 = X/Y/W/H
     * @param col 0 = 值显示, 1 = [-], 2 = [+]
     */
    private void spectrumCellRect(PanelLayout info, int row, int col, float[] out) {
        float contentTop = info.y + info.h - PANEL_PAD - SPECTRUM_TITLE_H;
        out[0] = info.x + PANEL_PAD + col * (BTN_W + BTN_GAP);
        out[1] = contentTop - (row + 1) * BTN_H - row * BTN_GAP;
        out[2] = BTN_W;
        out[3] = BTN_H;
    }

    /** 标题栏返回按钮矩形 */
    private void spectrumBackRect(PanelLayout info, float[] out) {
        float barY = info.y + info.h - PANEL_PAD - SPECTRUM_TITLE_H;
        out[0] = info.x + PANEL_PAD;
        out[1] = barY;
        out[2] = BTN_H;
        out[3] = SPECTRUM_TITLE_H;
    }

    // ─────────────────── 皮肤调整窗口（AUTOPLAY 中叠加，不切状态）───────────────────

    /** 窗口是否打开 */
    public boolean isSkinAdjustOpen() {
        return skinAdjustOpen;
    }

    /**
     * 当前界面可调皮肤的类型；不可调时返回 {@code null}。
     *
     * <p>两个宿主界面（详见设计稿 §7）：</p>
     * <ul>
     *   <li><b>PLAY</b>（{@code BMSPlayer}）→ 谱面类型对应的皮肤（7KEYS / 5KEYS / …）。</li>
     *   <li><b>MUSICSELECT</b>（{@code MusicSelector}）→ {@code SkinType.MUSIC_SELECT}。</li>
     * </ul>
     *
     * <p>判定读 {@code MainController.getCurrentState()}，<b>不读</b> {@link #isPlayMode} /
     * {@link #selectMode}：那两个是渲染循环写进来的模式标志，状态切换的时序上可能晚一拍。</p>
     */
    private SkinType skinAdjustType() {
        MainController mc = mainControllerOrNull();
        if (mc == null) {
            return null;
        }
        MainState state = mc.getCurrentState();
        if (state instanceof bms.player.beatoraja.play.BMSPlayer) {
            return ((bms.player.beatoraja.play.BMSPlayer) state).getSkinType();
        }
        if (state instanceof bms.player.beatoraja.select.MusicSelector) {
            return SkinType.MUSIC_SELECT;
        }
        return null;
    }

    /**
     * 打开皮肤调整窗口。
     * <p>🔴 <b>不切状态</b>：窗口直接叠加在宿主界面（{@code BMSPlayer} 或
     * {@code MusicSelector}）上，热重载走 {@code SkinLoader.load} +
     * {@code MainState.setSkin}（见设计稿 §7）。</p>
     */
    private void enterSkinAdjust() {
        MainController mc = mainControllerOrNull();
        SkinAdjustModel model = (mc != null) ? mc.getSkinAdjustModel() : null;
        SkinType type = skinAdjustType();
        if (model == null || type == null) {
            Gdx.app.log("FloatingMenu", "skin adjust: current state has no adjustable skin, ignored");
            return;
        }

        skinDragging = false;
        skinDragPointer = -1;
        // 位置故意不重置：独立浮窗关掉再打开应回到用户摆好的位置（持久化见设计稿阶段 3）
        stopSkinHold();
        skinPage = 0;
        skinReloadRequestTime = -1;
        skinReloadImmediate = false;
        resetSkinTextCache();

        // 皮肤清单只扫一次/进程。🔴 会执行 Lua 皮肤脚本（LuaSkinLoader.loadHeader），
        // 只能在渲染线程 —— 这与 SKINCONFIG 界面 create() 做的完全是同一件事。
        model.ensureScanned();
        // 按宿主界面正在用的那张皮肤重建参数清单
        model.selectCurrent(type);
        // 🔴 注册必须放在 selectCurrent 之后：重建清单会写入缺失的默认值并派发变更，
        //    提前注册的话开窗就白白触发一次全量皮肤重载。
        model.addChangeListener(skinModelListener);

        // 记下宿主类型：关窗补做重载时要靠它判断「宿主还是不是同一个」
        skinAdjustHostType = type;
        skinAdjustOpen = true;
        Gdx.app.log("FloatingMenu", "skin adjust window: open (type=" + model.getType()
                + ", skins=" + model.getSkins().size() + ", items=" + model.getItemCount() + ")");
    }

    /**
     * 关闭皮肤调整窗口并落盘。
     * <p>🔴 必须走 {@code MainController.saveConfig()} —— 它同时写
     * {@code Config} 与 {@code PlayerConfig}；皮肤参数在 PlayerConfig 的
     * {@code skin[type.getId()]} 里，只写 Config 会丢。</p>
     */
    private void exitSkinAdjust() {
        // 关窗时若还有没跑完的参数重载，必须先补做一次 —— 值早就写进 SkinConfig 了，
        // 只有「重载」还压在去抖里。不补的话「改完立刻关窗」看到的是旧皮肤，
        // 要等下一首歌才生效。
        boolean pendingReload = skinReloadImmediate || skinReloadRequestTime > 0;
        SkinType hostType = skinAdjustHostType;
        skinAdjustHostType = null;
        skinAdjustOpen = false;
        stopSkinHold();
        skinDragging = false;
        skinDragPointer = -1;
        skinReloadRequestTime = -1;
        skinReloadImmediate = false;
        SkinAdjustModel model = skinModel();
        if (model != null) {
            model.removeChangeListener(skinModelListener);
        }
        if (pendingReload && hostType != null && skinAdjustType() == hostType) {
            // 🔴 只在「宿主还是开窗时那一个界面」时补做。不能写成 skinAdjustType() != null：
            //    changeState 里 current 在 :389 就换掉了，而本方法是 :480 的
            //    setSelectMode(false) 触发的 —— 那一刻读到的已经是新界面的类型，
            //    「选曲开着窗口直接进 PLAY」会白加载一整张 PLAY 皮肤，还会把 BMSPlayer
            //    刚 create()/prepare() 好的皮肤顶掉。那种情况下值已写进 config，
            //    下次进入该界面自然生效。
            reloadCurrentSkin();
        }
        MainController mc = mainControllerOrNull();
        if (mc != null) {
            mc.saveConfig();
        }
        Gdx.app.log("FloatingMenu", "skin adjust window: closed (config saved)");
    }

    /** 共用的皮肤调整模型（由 MainController 持有，与 SKINCONFIG 界面是同一个实例） */
    private SkinAdjustModel skinModel() {
        MainController mc = mainControllerOrNull();
        return (mc != null) ? mc.getSkinAdjustModel() : null;
    }

    /** 模型侧有参数被写入了：记下时刻，由 {@link #render} 去抖后统一重载一次 */
    private void onSkinModelChanged() {
        if (!skinAdjustOpen) {
            return;
        }
        skinReloadRequestTime = System.currentTimeMillis();
    }

    /**
     * 重载宿主界面正在用的皮肤 —— 不切状态，直接换掉 {@code MainState} 上的 Skin 引用。
     *
     * <p>四条硬约束（详见设计稿 §7）：</p>
     * <ol>
     *   <li><b>先 {@code load} 新、再 {@code setSkin}</b>：{@code PixmapResourcePool} 是
     *       maxgen=1 的世代池，{@code load} 内部会 disposeOld；顺序反过来会让旧皮肤
     *       被释放两次 / 新皮肤引用到已释放纹理。</li>
     *   <li>{@code skin == null} 时<b>保留旧皮肤</b>，绝不能 {@code setSkin(null)} ——
     *       {@code MainState.setSkin} 会先 {@code dispose()} 掉旧皮肤，而
     *       {@code BMSPlayer.render()} 见 null 就 {@code changeState(MUSICSELECT)}、
     *       {@code MusicSelector.render()} 见 null 直接 NPE。</li>
     *   <li>宿主界面不在「可调皮肤状态」时（{@link #skinAdjustType()} 返回 null）直接放弃：
     *       那说明状态已经切走了，再加载只会白花一次全量 load，正好卡在切换上。
     *       值已经写进 config，下次进入该界面自然会用上。
     *       🔴 但「宿主被换成了<b>另一个</b>可调界面」这件事它测不出来 ——
     *       {@code changeState} 会先换掉 {@code current} 再回调关窗，所以调用方
     *       （关窗补做）必须自己比对 {@link #skinAdjustHostType}，见 {@code exitSkinAdjust}。</li>
     *   <li>{@code MusicSelector} 覆写了 {@code setSkin()}、会按新皮肤重建原生搜索框，
     *       调用方不需要额外处理 —— 但顺序仍是 load → setSkin → prepare。</li>
     * </ol>
     */
    private void reloadCurrentSkin() {
        MainController mc = mainControllerOrNull();
        SkinType type = (mc != null) ? skinAdjustType() : null;
        if (type == null) {
            return;
        }
        MainState state = mc.getCurrentState();
        if (state == null) {
            return;
        }
        SkinConfig cfg = mc.getPlayerConfig().getSkin()[type.getId()];
        if (cfg == null) {
            return;
        }
        Skin skin = SkinLoader.load(state, type, cfg);
        if (skin != null) {
            state.setSkin(skin);
            skin.prepare(state);
            Gdx.app.log("FloatingMenu", "skin adjust: skin reloaded (" + type + ")");
        } else {
            Gdx.app.log("FloatingMenu", "skin adjust: skin load failed, keeping current skin");
        }
    }

    /** 换皮肤（同类型内循环）：立即重载，不走去抖 */
    private void skinCycle(int diff) {
        SkinAdjustModel model = skinModel();
        if (model == null || !model.cycleSkin(diff)) {
            return;
        }
        // 清单整个重建了，按住的那个 ItemBase 已经作废（槽位含义也变了）
        stopSkinHold();
        skinPage = 0;
        resetSkinTextCache();
        skinReloadRequestTime = -1;
        skinReloadImmediate = true;
        SkinHeader header = model.getSelectedSkinHeader();
        Gdx.app.log("FloatingMenu", "skin adjust: switched to "
                + (header != null ? header.getName() : "(none)"));
    }

    /** 翻页：槽位 → 清单项的对应关系变了，按住的那个必须停掉 */
    private void skinSetPage(int page) {
        if (page == skinPage) {
            return;
        }
        stopSkinHold();
        skinPage = page;
    }

    /** 参数清单总页数（至少 1 页，便于页码栏画 `1/1`） */
    private int skinTotalPages(SkinAdjustModel model) {
        int n = (model != null) ? model.getItemCount() : 0;
        return Math.max(1, (n + SKIN_PARAM_ROWS - 1) / SKIN_PARAM_ROWS);
    }

    // ─────────────────── 参数改值（阶段 2） ───────────────────

    /** {@code col} 1 = {@code [−]}（-1），2 = {@code [+]}（+1） */
    private static int skinDirOf(int col) {
        return (col == 1) ? -1 : 1;
    }

    /**
     * 按住越久步长越大：&lt;1s 走 1、1s 起走 10、2s 起走 100、4s 起走 1000。
     * <p>做成阶梯而不是频谱页那种倍速，是因为 offset 的用处很分明 ——
     * 精细对齐用 1，粗调画布用 100，±9999 的极端值一步到位用 1000。
     * 倍速会在手一抖时直接跳过目标值，阶梯每档都有一个稳定的停留区间。</p>
     */
    private static int skinStepForElapsed(long elapsedNs) {
        if (elapsedNs >= SKIN_STEP_TIER3_NS) return 1000;
        if (elapsedNs >= SKIN_STEP_TIER2_NS) return 100;
        if (elapsedNs >= SKIN_STEP_TIER1_NS) return 10;
        return 1;
    }

    /**
     * 按下 {@code [−]} / {@code [+]}：先立刻走一步（所以点按就是精确的 ±1），
     * 之后由 {@link #render()} 在按住满 {@link #SKIN_HOLD_DELAY_NS} 后接管连发。
     */
    private void startSkinHold(int pointer, int slot, int col) {
        stopSkinHold();
        skinHoldPointer = pointer;
        skinHoldSlot = slot;
        skinHoldCol = col;
        skinHoldStartNs = System.nanoTime();
        skinHoldLastRepeatNs = skinHoldStartNs;
        skinStep(itemAt(skinModel(), slot), skinDirOf(col), 1);
    }

    private void stopSkinHold() {
        skinHoldSlot = -1;
        skinHoldCol = 0;
        skinHoldPointer = -1;
        skinHoldStartNs = 0;
        skinHoldLastRepeatNs = 0;
    }

    /**
     * 改一步参数值。
     * <p>{@link SkinAdjustModel.ItemBase#isWrapAround()} 决定到边界后的行为：
     * option / file 循环，offset 夹住不动。</p>
     * <p>值没变就<b>不</b>调用 {@code setValue} —— 它内部会无条件通知模型，
     * 在边界上一直按住 {@code [+]} 会白排一次皮肤重载。</p>
     */
    private void skinStep(SkinAdjustModel.ItemBase item, int dir, int magnitude) {
        if (item == null) {
            return;
        }
        int min = item.getMin();
        int max = item.getMax();
        int next = item.getvalue() + dir * magnitude;
        if (item.isWrapAround()) {
            int span = max - min + 1;
            if (span <= 0) {
                return;
            }
            // 用手写取模而不是 Math.floorMod：本项目 minSdk 21，那要靠
            // coreLibraryDesugaring 兜（android/build.gradle 已开，PreviewNoteLayer /
            // PreviewPlayValues 也在用），这里不必再引入这层依赖
            next = min + (((next - min) % span) + span) % span;
        } else {
            if (next < min) next = min;
            if (next > max) next = max;
        }
        if (next != item.getvalue()) {
            item.setValue(next);
        }
    }

    /**
     * 点值区 = 归零。
     * <p>只有 offset 有中性值（0）。option / file 是枚举列表，「归零」等于替你静默
     * 选了第一项 —— 比不响应更糟，所以对它们直接忽略（见
     * {@link SkinAdjustModel.ItemBase#hasNeutralValue()}）。</p>
     */
    private void skinResetValue(SkinAdjustModel.ItemBase item) {
        if (item == null || !item.hasNeutralValue()) {
            return;
        }
        if (item.getvalue() != 0) {
            item.setValue(0);
        }
    }

    /** 参数行文字缓存作废（换皮肤 / 翻页 / 参数变化 / 窗口缩放变化后调用） */
    private void resetSkinTextCache() {
        skinTextCachePage = Integer.MIN_VALUE;
        skinTextCacheHeader = null;
        skinTextCacheScale = -1f;
        for (int i = 0; i < SKIN_PARAM_ROWS; i++) {
            skinRowTextReady[i] = false;
        }
    }

    /**
     * 按像素宽度裁剪文本（超出用 {@code ..} 结尾）。
     * <p>参数名与文件名都可能很长，不裁剪会压到值列 / 越出面板。
     * 二分而不是线性试长度，减少每帧 GlyphLayout 的次数。</p>
     */
    private String fitText(BitmapFont font, GlyphLayout glyph, String text, float maxW) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        glyph.setText(font, text);
        if (glyph.width <= maxW) {
            return text;
        }
        int lo = 1, hi = text.length();
        String best = "";
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            String cut = text.substring(0, mid) + "..";
            glyph.setText(font, cut);
            if (glyph.width <= maxW) {
                best = cut;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /** 皮肤目录名 —— 皮肤名可能重名，用小字给目录名做区分（设计稿 §5.2） */
    private String skinDirLabel(SkinAdjustModel model) {
        if (model == null || model.getConfig() == null || model.getConfig().getPath() == null) {
            return "";
        }
        String p = model.getConfig().getPath().replace('\\', '/');
        int last = p.lastIndexOf('/');
        if (last <= 0) {
            return "";
        }
        String parent = p.substring(0, last);
        int prev = parent.lastIndexOf('/');
        String dir = (prev >= 0) ? parent.substring(prev + 1) : parent;
        return dir.isEmpty() ? "" : "(" + dir + ")";
    }

    /**
     * 窗口布局：固定设计尺寸 × {@link #skinPanelScale()} + <b>窗口自己的位置</b>
     * （不锚定浮动图标、不锚定菜单面板）。
     * <p>这是「独立浮窗」的关键 —— 位置只由 {@link #skinPanelX}/{@link #skinPanelY}
     * 决定，可拖到屏幕任意位置；{@link #anchorPanel} 那套锚定只服务于菜单列表页与频谱页。</p>
     */
    private PanelLayout calculateSkinAdjustLayout() {
        PanelLayout res = new PanelLayout();
        res.cols = 1;
        res.scale = skinPanelScale();
        res.w = SKIN_PANEL_W * res.scale;
        res.h = SKIN_PANEL_H * res.scale;
        if (!skinPanelPlaced) {
            // 首次打开：屏幕居中（独立弹窗的自然默认位置）
            skinPanelX = (logicW - res.w) / 2f;
            skinPanelY = (logicH - res.h) / 2f;
            skinPanelPlaced = true;
        }
        clampSkinPanel(res.w, res.h);
        res.x = skinPanelX;
        res.y = skinPanelY;
        return res;
    }

    /**
     * 窗口的等比缩放系数。
     * <p>设计尺寸 620×696 是按 1080p 皮肤画布定的，但逻辑坐标是「皮肤自身的宽高」。
     * 在 1280×720 的画布上，0.80 的高度上限会把窗口压到 ~576 高，上下各留出可拖的余量；
     * 1080p 及以上封顶为 1（不放大）。</p>
     */
    private float skinPanelScale() {
        if (logicW <= 0 || logicH <= 0) {
            return 1f;
        }
        float s = Math.min(logicW * SKIN_MAX_W_RATIO / SKIN_PANEL_W,
                logicH * SKIN_MAX_H_RATIO / SKIN_PANEL_H);
        return Math.min(1f, s);
    }

    /**
     * 把窗口位置夹进屏幕。回写 {@link #skinPanelX}/{@link #skinPanelY}，
     * 所以拖动时即使手指跑到屏幕外，窗口也只停在边上；手指回退到界内即精确跟回。
     * <p>窗口比屏幕还大时（极窄的逻辑分辨率）居中且不可拖 —— 此时没有合法位置。</p>
     */
    private void clampSkinPanel(float w, float h) {
        if (w + SKIN_PANEL_MARGIN * 2 >= logicW) {
            skinPanelX = (logicW - w) / 2f;
        } else {
            skinPanelX = Math.max(SKIN_PANEL_MARGIN,
                    Math.min(skinPanelX, logicW - w - SKIN_PANEL_MARGIN));
        }
        if (h + SKIN_PANEL_MARGIN * 2 >= logicH) {
            skinPanelY = (logicH - h) / 2f;
        } else {
            skinPanelY = Math.max(SKIN_PANEL_MARGIN,
                    Math.min(skinPanelY, logicH - h - SKIN_PANEL_MARGIN));
        }
    }

    // ─── 皮肤窗口的几何：全部按 info.scale 等比缩放（设计尺寸 620×696，见 skinPanelScale）───

    /** 标题栏整条矩形（= 拖动命中区，绘制与命中判定共用；右侧关闭按钮除外） */
    private void skinTitleBarRect(PanelLayout info, float[] out) {
        final float u = info.scale;
        out[0] = info.x + PANEL_PAD * u;
        out[1] = info.y + info.h - (PANEL_PAD + SKIN_TITLE_H) * u;
        out[2] = info.w - PANEL_PAD * u * 2;
        out[3] = SKIN_TITLE_H * u;
    }

    /** 标题栏右侧关闭按钮（绘制与命中判定共用） */
    private void skinCloseRect(PanelLayout info, float[] out) {
        skinTitleBarRect(info, out);
        final float w = SKIN_CLOSE_W * info.scale;
        out[0] = out[0] + out[2] - w;
        out[2] = w;
    }

    /** 参数区顶边（= 皮肤行下沿再让开一个行间距），逐行向下排 */
    private float skinParamAreaTop(PanelLayout info) {
        final float u = info.scale;
        return info.y + info.h - (PANEL_PAD + SKIN_TITLE_H + SKIN_PARAM_ROW_GAP
                + SKIN_ROW_H + SKIN_PARAM_ROW_GAP) * u;
    }

    /** 皮肤行整条矩形（绘制与命中判定共用） */
    private void skinRowRect(PanelLayout info, float[] out) {
        skinTitleBarRect(info, out);
        out[1] = out[1] - (SKIN_PARAM_ROW_GAP + SKIN_ROW_H) * info.scale;
        out[3] = SKIN_ROW_H * info.scale;
    }

    /** 皮肤行左侧翻页按钮 */
    private void skinRowPrevRect(PanelLayout info, float[] out) {
        skinRowRect(info, out);
        out[2] = SKIN_ROW_NAV_W * info.scale;
    }

    /** 皮肤行右侧翻页按钮 */
    private void skinRowNextRect(PanelLayout info, float[] out) {
        skinRowRect(info, out);
        final float w = SKIN_ROW_NAV_W * info.scale;
        out[0] = out[0] + out[2] - w;
        out[2] = w;
    }

    /** 参数行 slot（0..SKIN_PARAM_ROWS-1）整条矩形 */
    private void skinParamRowRect(PanelLayout info, int slot, float[] out) {
        final float u = info.scale;
        out[0] = info.x + PANEL_PAD * u;
        out[1] = skinParamAreaTop(info)
                - ((slot + 1) * SKIN_PARAM_ROW_H + slot * SKIN_PARAM_ROW_GAP) * u;
        out[2] = info.w - PANEL_PAD * u * 2;
        out[3] = SKIN_PARAM_ROW_H * u;
    }

    /**
     * 参数行内某一列的矩形，绘制与命中判定共用。
     *
     * <p>列宽取自 §3 的固定规格：面板内宽 572 = 名称 280 + 间隙 8 + 值 140 + 间隙 8
     * + {@code [−]} 64 + 间隙 8 + {@code [+]} 64，正好铺满。</p>
     *
     * <p>{@code col}：0 = 值区（含名称列，点击归零），1 = {@code [−]}，2 = {@code [+]}。
     * 两个按钮在垂直方向内缩到 {@link #SKIN_BTN_H}，与频谱页的做法一致。</p>
     */
    private void skinParamCellRect(PanelLayout info, int slot, int col, float[] out) {
        final float u = info.scale;
        skinParamRowRect(info, slot, out);
        if (col == 1 || col == 2) {
            float btnX = out[0] + (SKIN_COL_NAME_W + SKIN_COL_GAP + SKIN_COL_VALUE_W + SKIN_COL_GAP
                    + (col == 2 ? SKIN_COL_BTN_W + SKIN_COL_GAP : 0f)) * u;
            float inset = (SKIN_PARAM_ROW_H - SKIN_BTN_H) / 2f * u;
            out[0] = btnX;
            out[1] = out[1] + inset;
            out[2] = SKIN_COL_BTN_W * u;
            out[3] = SKIN_BTN_H * u;
        } else {
            out[2] = (SKIN_COL_NAME_W + SKIN_COL_GAP + SKIN_COL_VALUE_W) * u;
        }
    }

    /** 参数槽位 slot 对应的清单项（按 {@link #skinPage} 折算；空槽或越界返回 null） */
    private SkinAdjustModel.ItemBase itemAt(SkinAdjustModel model, int slot) {
        if (model == null) {
            return null;
        }
        return model.getItem(skinPage * SKIN_PARAM_ROWS + slot);
    }

    /** 页码栏整条矩形 */
    private void skinPageBarRect(PanelLayout info, float[] out) {
        skinParamRowRect(info, SKIN_PARAM_ROWS - 1, out);
        out[1] = out[1] - (SKIN_PARAM_ROW_GAP + SKIN_PAGE_BAR_H) * info.scale;
        out[3] = SKIN_PAGE_BAR_H * info.scale;
    }

    /** 页码栏左翻区（左 1/3） */
    private void skinPagePrevRect(PanelLayout info, float[] out) {
        skinPageBarRect(info, out);
        out[2] = out[2] / 3f;
    }

    /** 页码栏右翻区（右 1/3） */
    private void skinPageNextRect(PanelLayout info, float[] out) {
        skinPageBarRect(info, out);
        out[0] = out[0] + out[2] - out[2] / 3f;
        out[2] = out[2] / 3f;
    }

    /** 点是否落在矩形内（命中判定统一入口，避免每处重复写四个比较） */
    private static boolean inRect(float tx, float ty, float[] r) {
        return tx >= r[0] && tx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3];
    }

    /**
     * 窗口命中判定。返回 SKIN_HIT_* 之一；参数行返回 {@code slot*10+col}
     * （col: 0 = 值区，1 = {@code [−]}，2 = {@code [+]}，与频谱页同一套编码）。
     */
    private int hitTestSkinAdjustPage(float tx, float ty) {
        PanelLayout info = calculateSkinAdjustLayout();
        if (tx < info.x || tx > info.x + info.w || ty < info.y || ty > info.y + info.h) {
            return SKIN_HIT_OUTSIDE;
        }
        float[] r = new float[4];
        skinCloseRect(info, r);
        if (inRect(tx, ty, r)) return SKIN_HIT_CLOSE;
        skinRowPrevRect(info, r);
        if (inRect(tx, ty, r)) return SKIN_HIT_PREV_SKIN;
        skinRowNextRect(info, r);
        if (inRect(tx, ty, r)) return SKIN_HIT_NEXT_SKIN;
        skinPagePrevRect(info, r);
        if (inRect(tx, ty, r)) return SKIN_HIT_PREV_PAGE;
        skinPageNextRect(info, r);
        if (inRect(tx, ty, r)) return SKIN_HIT_NEXT_PAGE;
        // 参数行：空槽位不参与命中（点空行只被消费，不产生动作）
        SkinAdjustModel model = skinModel();
        for (int slot = 0; slot < SKIN_PARAM_ROWS; slot++) {
            if (itemAt(model, slot) == null) {
                continue;
            }
            for (int col = 0; col < 3; col++) {
                skinParamCellRect(info, slot, col, r);
                if (inRect(tx, ty, r)) return slot * 10 + col;
            }
        }
        // 标题栏放在最后：它在皮肤行/页码栏上方，互不重叠，但拖动命中必须让位给按钮
        skinTitleBarRect(info, r);
        if (inRect(tx, ty, r)) return SKIN_HIT_TITLE;
        return SKIN_HIT_NONE;
    }

    private void drawSkinAdjustPage(SpriteBatch sprite, BitmapFont font) {
        PanelLayout info = calculateSkinAdjustLayout();
        final float u = info.scale;
        final float border = 2 * u;
        float[] r = new float[4];
        GlyphLayout glyph = new GlyphLayout();
        float barY = info.y + info.h - (PANEL_PAD + SKIN_TITLE_H) * u;
        SkinAdjustModel model = skinModel();

        // 窗口按逻辑画布等比缩过，字体必须跟着缩（720p 画布上不缩的话文字会溢出行框）。
        // 🔴 末尾必须恢复 1f —— systemfont 是全局共享的，菜单页/频谱页都按 1 倍排版。
        font.getData().setScale(u);

        // 面板背景 + 边框（与频谱页同色系）
        sprite.setColor(0.1f, 0.1f, 0.15f, 0.85f);
        sprite.draw(whitePixel, info.x, info.y, info.w, info.h);
        sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
        sprite.draw(whitePixel, info.x, info.y, info.w, border);
        sprite.draw(whitePixel, info.x, info.y + info.h - border, info.w, border);
        sprite.draw(whitePixel, info.x, info.y, border, info.h);
        sprite.draw(whitePixel, info.x + info.w - border, info.y, border, info.h);

        // 标题栏底（整条，比标题按钮浅一层 —— 暗示"这里可以拖"）
        skinTitleBarRect(info, r);
        sprite.setColor(0.14f, 0.16f, 0.24f, 0.75f);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);

        // 居中标题（左侧不再放返回按钮：`<` 这个字形留给下面皮肤行的翻页，
        // 免得「想翻皮肤却把窗口关了」。关闭走 X / ESC·BACK / 点面板外。）
        // 带上皮肤类型名：同一个窗口现在有两个宿主界面（PLAY / 选曲界面），
        // 标题得能自证「我在调哪一张」。
        SkinType adjustType = (model != null) ? model.getType() : null;
        String title = (adjustType != null) ? ("Skin Adjust · " + adjustType.getName()) : "Skin Adjust";
        // 宽度预算要避开右侧关闭按钮，否则长类型名（MUSIC SELECT）会压到 X 上
        title = fitText(font, glyph, title, info.w - (PANEL_PAD * 2 + SKIN_CLOSE_W + 8) * u);
        font.setColor(0.85f, 0.85f, 0.9f, 0.95f);
        glyph.setText(font, title);
        font.draw(sprite, title, info.x + (info.w - glyph.width) / 2,
                barY + (SKIN_TITLE_H * u + glyph.height) / 2);

        // 右侧关闭按钮
        skinCloseRect(info, r);
        sprite.setColor(0.45f, 0.18f, 0.18f, 0.9f);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);
        font.setColor(1f, 0.9f, 0.9f, 0.95f);
        glyph.setText(font, "X");
        font.draw(sprite, "X", r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);

        // 皮肤行（`< 皮肤名 >`）
        drawSkinRow(sprite, font, info, model, glyph);

        // 参数区：固定 6 行/页
        int totalPages = skinTotalPages(model);
        if (skinPage >= totalPages) skinPage = totalPages - 1;
        if (skinPage < 0) skinPage = 0;
        SkinHeader header = (model != null) ? model.getSelectedSkinHeader() : null;
        if (skinTextCachePage != skinPage || skinTextCacheHeader != header
                || skinTextCacheScale != u) {
            resetSkinTextCache();
            skinTextCachePage = skinPage;
            skinTextCacheHeader = header;
            skinTextCacheScale = u;
        }
        for (int slot = 0; slot < SKIN_PARAM_ROWS; slot++) {
            drawSkinParamRow(sprite, font, info, model, slot, glyph);
        }

        // 页码栏
        int itemCount = (model != null) ? model.getItemCount() : 0;
        drawSkinPageBar(sprite, font, info, itemCount, totalPages, glyph);

        // 🔴 还原全局字体缩放：systemfont 由 MainController 持有并共享给菜单页等
        font.getData().setScale(1f);
    }

    private void drawSkinRow(SpriteBatch sprite, BitmapFont font, PanelLayout info,
                             SkinAdjustModel model, GlyphLayout glyph) {
        float[] r = new float[4];
        skinRowRect(info, r);
        sprite.setColor(0.16f, 0.18f, 0.24f, 0.8f);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);

        // 只有一个候选时箭头照样灰掉：按下去只会重载同一张皮肤，白花一次全量 load。
        // 选曲界面（MUSIC SELECT）常常只有一张皮肤，这条主要就是为它加的。
        // 模型侧 SkinAdjustModel.cycleSkin 同样会直接返回 false，视觉与行为一致。
        boolean hasList = model != null && model.getSkins().size() > 1;
        float navR = hasList ? 0.25f : 0.15f;
        float navG = hasList ? 0.35f : 0.16f;
        float navB = hasList ? 0.55f : 0.22f;
        float navA = hasList ? 0.9f : 0.5f;
        float glyphA = hasList ? 0.95f : 0.4f;

        skinRowPrevRect(info, r);
        sprite.setColor(navR, navG, navB, navA);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);
        font.setColor(0.6f, 0.85f, 1f, glyphA);
        glyph.setText(font, "<");
        font.draw(sprite, "<", r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);

        skinRowNextRect(info, r);
        sprite.setColor(navR, navG, navB, navA);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);
        font.setColor(0.6f, 0.85f, 1f, glyphA);
        glyph.setText(font, ">");
        font.draw(sprite, ">", r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);

        // 中间两行：皮肤名 + 目录名（皮肤名可能重名）
        final float u = info.scale;
        float textLeft = info.x + (PANEL_PAD + SKIN_ROW_NAV_W + SKIN_COL_GAP) * u;
        float maxW = info.w - (PANEL_PAD * 2 + SKIN_ROW_NAV_W * 2 + SKIN_COL_GAP * 2) * u;
        skinRowRect(info, r);

        SkinHeader header = (model != null) ? model.getSelectedSkinHeader() : null;
        String name = (header != null) ? header.getName() : "(no skin in this type)";
        name = fitText(font, glyph, name, maxW);
        font.setColor(0.9f, 0.93f, 1f, 0.95f);
        glyph.setText(font, name);
        font.draw(sprite, name, textLeft + (maxW - glyph.width) / 2, r[1] + r[3] * 0.68f);

        String dir = skinDirLabel(model);
        if (!dir.isEmpty()) {
            dir = fitText(font, glyph, dir, maxW);
            font.setColor(0.55f, 0.62f, 0.75f, 0.9f);
            glyph.setText(font, dir);
            font.draw(sprite, dir, textLeft + (maxW - glyph.width) / 2, r[1] + r[3] * 0.28f);
        }
    }

    private void drawSkinParamRow(SpriteBatch sprite, BitmapFont font, PanelLayout info,
                                  SkinAdjustModel model, int slot, GlyphLayout glyph) {
        float[] r = new float[4];
        skinParamRowRect(info, slot, r);
        SkinAdjustModel.ItemBase item = itemAt(model, slot);

        if (item == null) {
            // 空行也画一块暗底：面板高度固定，不随项数变化
            sprite.setColor(0.13f, 0.145f, 0.19f, 0.6f);
            sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);
            return;
        }

        // 整行底
        sprite.setColor(0.09f, 0.11f, 0.16f, 0.8f);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);

        // 值区底色比按钮暗一档，暗示「这里不是按钮」
        skinParamCellRect(info, slot, 0, r);
        sprite.setColor(0.12f, 0.14f, 0.2f, 0.7f);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);

        drawSkinStepButton(sprite, font, info, slot, 1,
                skinHoldSlot == slot && skinHoldCol == 1, glyph);
        drawSkinStepButton(sprite, font, info, slot, 2,
                skinHoldSlot == slot && skinHoldCol == 2, glyph);

        // 名称随「页 + 皮肤」变，可以缓存；值会被本窗口改掉，每帧重新测量
        // （短字符串不会触发 fitText 里的二分，开销可忽略）
        final float u = info.scale;
        if (!skinRowTextReady[slot]) {
            skinRowNameCache[slot] = fitText(font, glyph, item.getCategoryName(),
                    (SKIN_COL_NAME_W - 16) * u);
            skinRowTextReady[slot] = true;
        }

        skinParamRowRect(info, slot, r);
        String name = skinRowNameCache[slot];
        font.setColor(0.85f, 0.88f, 0.95f, 0.95f);
        glyph.setText(font, name);
        font.draw(sprite, name, r[0] + 8 * u, r[1] + (r[3] + glyph.height) / 2);

        String value = fitText(font, glyph, item.getDisplayValue(), (SKIN_COL_VALUE_W - 8) * u);
        font.setColor(0.6f, 0.85f, 1f, 0.95f);
        glyph.setText(font, value);
        float valueRight = r[0] + (SKIN_COL_NAME_W + SKIN_COL_GAP + SKIN_COL_VALUE_W) * u;
        font.draw(sprite, value, valueRight - glyph.width, r[1] + (r[3] + glyph.height) / 2);
    }

    /** 参数行的 {@code [−]} / {@code [+]} 按钮（{@code col} 1 = −，2 = +） */
    private void drawSkinStepButton(SpriteBatch sprite, BitmapFont font, PanelLayout info,
                                    int slot, int col, boolean pressed, GlyphLayout glyph) {
        float[] r = new float[4];
        skinParamCellRect(info, slot, col, r);
        if (pressed) {
            sprite.setColor(0.3f, 0.5f, 0.8f, 0.95f);
        } else {
            sprite.setColor(0.2f, 0.2f, 0.3f, 0.7f);
        }
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);
        String label = (col == 1) ? "[-]" : "[+]";
        font.setColor(1f, 1f, 1f, 0.95f);
        glyph.setText(font, label);
        font.draw(sprite, label, r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);
    }

    private void drawSkinPageBar(SpriteBatch sprite, BitmapFont font, PanelLayout info,
                                 int itemCount, int totalPages, GlyphLayout glyph) {
        final float u = info.scale;
        float[] bar = new float[4];
        float[] r = new float[4];
        skinPageBarRect(info, bar);
        sprite.setColor(0.15f, 0.15f, 0.2f, 0.5f);
        sprite.draw(whitePixel, bar[0], bar[1], bar[2], bar[3]);

        boolean canPrev = skinPage > 0;
        boolean canNext = skinPage < totalPages - 1;

        skinPagePrevRect(info, r);
        font.setColor(0.5f, 0.8f, 1f, canPrev ? 0.9f : 0.25f);
        glyph.setText(font, "<");
        font.draw(sprite, "<", r[0] + 12 * u, r[1] + (r[3] + glyph.height) / 2);

        skinPageNextRect(info, r);
        font.setColor(0.5f, 0.8f, 1f, canNext ? 0.9f : 0.25f);
        glyph.setText(font, ">");
        font.draw(sprite, ">", r[0] + r[2] - 12 * u - glyph.width,
                r[1] + (r[3] + glyph.height) / 2);

        String text;
        if (itemCount <= 0) {
            text = "no parameters - skin header not loaded";
        } else {
            int from = skinPage * SKIN_PARAM_ROWS + 1;
            int to = Math.min(itemCount, from + SKIN_PARAM_ROWS - 1);
            // 显示「项数」而不只是页码：一个 offset 名最多产出 6 条，条数≠参数个数
            text = "Page " + (skinPage + 1) + "/" + totalPages
                    + "    " + from + "-" + to + " / " + itemCount;
        }
        font.setColor(0.7f, 0.7f, 0.7f, 0.9f);
        glyph.setText(font, text);
        font.draw(sprite, text, bar[0] + (bar[2] - glyph.width) / 2,
                bar[1] + (bar[3] + glyph.height) / 2);
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
            // 7K 键 1~7（按钮索引 i = 核心层槽位 i）
            int k7Idx = hitTest7KKey(tx, ty, pointer7KKey[pointer]);
            if (k7Idx >= 0) {
                pointer7KKey[pointer] = k7Idx;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                send7KKey(k7Idx, true);
                return true;
            }
            // 点击浮动图标：关闭覆盖层并释放按住的键。
            // 结果界面（DIRECT）不展开菜单面板，只关闭覆盖层。
            if (hitTestIcon(tx, ty)) {
                releaseHoldKey();
                if (!resultMode) {
                    expanded = true;
                    justExpandedByIcon = true;
                }
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
            // 🔴 皮肤窗口打开时图标不绘制（视觉上只剩这一个独立窗口），所以此时也必须
            //    屏蔽图标命中 —— 否则窗口外的空白处会藏着一个看不见的「收起菜单」按钮。
            if (!skinAdjustOpen && hitTestIcon(tx, ty)) {
                if (spectrumAdjustOpen) exitSpectrumAdjust();
                expanded = false;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }

            // 频谱调整页：独立模态，命中判定与绘制共用同一套矩形
            if (spectrumAdjustOpen) {
                int hit = hitTestSpectrumPage(tx, ty);
                if (hit == SPECTRUM_HIT_OUTSIDE) {
                    // 点面板外：只退出调整页、不关整个菜单（避免误触把菜单一起收掉）
                    exitSpectrumAdjust();
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SPECTRUM_HIT_BACK) {
                    exitSpectrumAdjust();
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                pointerSpectrumCell[pointer] = (hit >= 0) ? hit : -1;
                if (hit >= 0) {
                    int row = hit / 10;
                    int col = hit % 10;
                    if (col == 1 || col == 2) {
                        // [-] / [+]：立即走一步，并开启长按连发
                        spectrumHoldField = row;
                        spectrumHoldDir = (col == 1) ? -1 : 1;
                        spectrumHoldStartNs = System.nanoTime();
                        spectrumHoldLastRepeatNs = spectrumHoldStartNs;
                        spectrumStep(row, spectrumHoldDir);
                    }
                }
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }

            // 皮肤调整窗口：独立模态，命中判定与绘制共用同一套矩形
            if (skinAdjustOpen) {
                int hit = hitTestSkinAdjustPage(tx, ty);
                if (hit == SKIN_HIT_OUTSIDE) {
                    // 点面板外：只关窗口、不关整个菜单（与频谱页同语义，避免误触把菜单一起收掉）
                    exitSkinAdjust();
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SKIN_HIT_CLOSE) {
                    exitSkinAdjust();
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SKIN_HIT_PREV_SKIN) {
                    skinCycle(-1);
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SKIN_HIT_NEXT_SKIN) {
                    skinCycle(1);
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SKIN_HIT_PREV_PAGE) {
                    if (skinPage > 0) skinSetPage(skinPage - 1);
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SKIN_HIT_NEXT_PAGE) {
                    if (skinPage < skinTotalPages(skinModel()) - 1) skinSetPage(skinPage + 1);
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SKIN_HIT_TITLE) {
                    // 标题栏空白：开始拖动（直接改窗口自己的位置）
                    skinDragging = true;
                    skinDragPointer = pointer;
                    skinDragAnchorX = tx;
                    skinDragAnchorY = ty;
                    skinDragStartX = skinPanelX;
                    skinDragStartY = skinPanelY;
                } else if (hit >= 0) {
                    // 参数行：col 0 = 值区（点一下归零），1 / 2 = [−] / [+]
                    int slot = hit / 10;
                    int col = hit % 10;
                    if (col == 0) {
                        skinResetValue(itemAt(skinModel(), slot));
                    } else {
                        startSkinHold(pointer, slot, col);
                    }
                }
                // 面板内其余区域一律模态消费，穿透不到游戏
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
                // 结果界面：不做菜单面板，图标点击直接弹出 7K 键位覆盖层
                // （覆盖层摆放跟随图标位置，见 calculate7KOverlayLayout 的 DIRECT 分支）
                if (resultMode) {
                    openDirectKeyOverlay();
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
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
            // 覆盖层：释放对应的 7K 键
            int k7Idx = pointer7KKey[pointer];
            if (k7Idx >= 0) {
                send7KKey(k7Idx, false);
                pointer7KKey[pointer] = -1;
                justExpandedByIcon = false;
                pointerPressedIndex[pointer] = -1;
                pointerConsuming[pointer] = false;
                return true;
            }
            // 频谱调整页：抬手即停止连发并落盘
            if (spectrumAdjustOpen) {
                pointerSpectrumCell[pointer] = -1;
                stopSpectrumHold(true);
            }
            // 皮肤调整窗口：抬手即结束拖动（窗口停在原地，不回弹）；参数连发同理
            if (skinAdjustOpen && pointer == skinDragPointer) {
                skinDragging = false;
                skinDragPointer = -1;
            }
            if (skinAdjustOpen && pointer == skinHoldPointer) {
                stopSkinHold();
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
            // 覆盖层：始终跟随手指，按下当前命中键、释放之前的键
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
                // 皮肤调整窗口：拖动标题栏移动窗口；其他命中一律不处理（模态）
                if (skinAdjustOpen) {
                    if (skinDragging && pointer == skinDragPointer) {
                        // 边界夹取交给 clampSkinPanel（每帧在 calculateSkinAdjustLayout 里跑），
                        // 这里只按「起点 + 本次位移」记原始意图，被夹住后手指回位才能精确还原
                        skinPanelX = skinDragStartX + (tx - skinDragAnchorX);
                        skinPanelY = skinDragStartY + (ty - skinDragAnchorY);
                    }
                    return true;
                }
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
        // 频谱调整页：ESC / BACK 返回
        if (spectrumAdjustOpen) {
            if (keycode == Keys.ESCAPE || keycode == Keys.BACK) {
                exitSpectrumAdjust();
                return true;
            }
        }
        // 皮肤调整窗口：ESC / BACK 关闭并落盘。
        // 🔴 必须把它吃掉：FloatingMenu 是 InputMultiplexer 的第一位，返回 false 的话
        // ESCAPE 会落到 ControlInputProcessor:205 的无条件 stopPlay()——
        // AUTOPLAY 下那等于直接中止演奏回选曲。
        if (skinAdjustOpen) {
            if (keycode == Keys.ESCAPE || keycode == Keys.BACK) {
                exitSkinAdjust();
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
            // 释放可能按住的覆盖层按键
            int k7Idx = pointer7KKey[pointer];
            if (k7Idx >= 0) {
                send7KKey(k7Idx, false);
            }
            pointer7KKey[pointer] = -1;
            pointerPressedIndex[pointer] = -1;
            pointerConsuming[pointer] = false;
            if (pointer == skinDragPointer) {
                skinDragging = false;
                skinDragPointer = -1;
            }
            if (pointer == skinHoldPointer) {
                stopSkinHold();
            }
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

        // 收集可见按钮索引（含 PLAY 界面的顺序覆盖；绘制与命中判定同一份顺序）
        int[] visibleIndices = buildVisibleIndexOrder();

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

        if (item.keycode == -100 || item.keycode == -130 || item.keycode == WALKURE_KEYCODE
                || item.keycode == SPECTRUM_ENTRY_KEYCODE || item.keycode == SKIN_ADJUST_KEYCODE) {
            return; // Toggle/action 类型（含两个模态页入口）在 touchUp 处理
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

        if (item.keycode == -100 || item.keycode == -130 || item.keycode == WALKURE_KEYCODE
                || item.keycode == SPECTRUM_ENTRY_KEYCODE || item.keycode == SKIN_ADJUST_KEYCODE) {
            handleToggle(item);
            return;
        }

        // 长按模式（NUM5/START）是切换式，不在 touchUp 时释放
        if (item.keycode == Keys.NUM_5 || item.keycode == -141) {
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
                } else if (item.keycode == WALKURE_KEYCODE) {
                    // Player Rating entry - show in WebView via AndroidLauncher
                    showPlayerRating();
                } else if (item.keycode == SPECTRUM_ENTRY_KEYCODE) {
                    // In-Game Spectrum 调整页（仅 PLAY 界面可见）
                    enterSpectrumAdjust();
                } else if (item.keycode == SKIN_ADJUST_KEYCODE) {
                    // 皮肤调整窗口（仅 PLAY + AUTOPLAY 可见）
                    enterSkinAdjust();
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
