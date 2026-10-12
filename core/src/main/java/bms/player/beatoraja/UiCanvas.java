package bms.player.beatoraja;

/**
 * 「设计画布」——按固定基准分辨率排版 UI 的缩放换算。
 *
 * <p>浮动菜单（{@link FloatingMenu}）与内建播放界面（{@code play.MusicPlayer}）里的
 * 尺寸常量（按钮宽高、列表行高、封面尺寸、字号……）都是按 1280×720 定的。游戏逻辑尺寸
 * （= 皮肤尺寸 = {@link Config#getResolution()}）变大时，若直接把这些常量当逻辑单位用，
 * 元素在屏幕上占的比例就会变小（逻辑 1080p 下缩成 2/3）。</p>
 *
 * <p>做法：这些界面内部**固定**排布在 {@code DESIGN_W×DESIGN_H} 的画布上，
 * 用 {@link #scale(int, int)} 算出「画布 → 逻辑空间」的系数，把这系数交给投影矩阵
 * （画布铺满整个 GL 视口）；触摸坐标则除以同一系数换算回画布坐标。于是元素在屏幕上
 * 占的比例与逻辑分辨率无关 —— 逻辑 1920×1080 时画布仍是 1280×720。</p>
 *
 * <p>缩放取 {@code min(宽比, 高比)}：逻辑宽高比不是 16:9 时取小的那个，保证两个方向
 * 都不超出原有占比（例：4:3 下一排 4 个按钮本来就能占满宽度，只按高度放大就会溢出）。
 * 另外「画布宽高比 == 逻辑宽高比」恒成立，所以等比映射到视口时不会变形。</p>
 *
 * <p>1280×720 时 scale = 1、画布 = 逻辑尺寸 ⇒ 与改造前逐位相同。
 * 详见 {@code docs/resolution-logical-space.md}。</p>
 */
public final class UiCanvas {

    /** 设计基准宽（与本项目内置皮肤的设计分辨率一致）。 */
    public static final float DESIGN_W = 1280;
    /** 设计基准高。 */
    public static final float DESIGN_H = 720;

    /** 下限：极端小画布下元素会挤在一起，但至少不退化成 0。 */
    private static final float MIN_SCALE = 0.35f;
    /** 上限：防止异常输入把字号放大到失控。 */
    private static final float MAX_SCALE = 8f;

    private UiCanvas() {
    }

    /**
     * 逻辑尺寸 → 设计画布尺寸的缩放系数。
     * <p>1280×720 时为 1；非法输入（&le;0）时回退 1（调用方应自行兜底）。</p>
     */
    public static float scale(int logicW, int logicH) {
        if (logicW <= 0 || logicH <= 0) {
            return 1f;
        }
        final float s = Math.min(logicW / DESIGN_W, logicH / DESIGN_H);
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    /** 逻辑尺寸 → 画布宽度（界面内部排版用，基准 1280）。 */
    public static float canvasW(int logicW, int logicH) {
        return logicW / scale(logicW, logicH);
    }

    /** 逻辑尺寸 → 画布高度（界面内部排版用，基准 720）。 */
    public static float canvasH(int logicW, int logicH) {
        return logicH / scale(logicW, logicH);
    }

    /**
     * 逻辑空间 1 单位在 GL 视口里占多少**物理像素**。
     *
     * <p>与 {@link #scale(int, int)} 是两件事：{@code scale} 管「设计画布 ↔ 逻辑空间」，
     * 这个方法管「逻辑空间 ↔ 物理像素」。前者保证元素占屏**比例**恒定，
     * 但字形 / 自绘位图是**固定像素**的位图 —— 物理像素变多就必须按同样倍率
     * 提高光栅化分辨率，否则就是拿小图去铺大屏，边缘必然发虚
     * （{@code FloatingMenu} / {@code MusicPlayer} 的「字体模糊」就是这个）。</p>
     *
     * <p>取 {@code min(宽比, 高比)}：这与 {@link MainController} 的 letterbox / pillarbox
     * 视口计算结果**恒等**（letterbox 时 {@code screenW/logicW < screenH/logicH}，
     * pillarbox 时反之），所以它正好等于「视口高 / 逻辑高」。
     * 顺便：非 16:9 逻辑下两轴比例不同，取小的那个也避免了过度放大导致图集膨胀。</p>
     *
     * <p>非法输入（&le;0）返回 1（= 老行为，不做高 DPI 光栅化）。</p>
     */
    public static float pixelRatio(int logicW, int logicH, int screenW, int screenH) {
        if (logicW <= 0 || logicH <= 0 || screenW <= 0 || screenH <= 0) {
            return 1f;
        }
        return Math.min(screenW / (float) logicW, screenH / (float) logicH);
    }
}
