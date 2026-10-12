package bms.player.beatoraja;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

import com.badlogic.gdx.*;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.*;
import java.lang.StringBuilder;

import bms.player.beatoraja.AudioConfig.DriverType;
import bms.player.beatoraja.MainState.MainStateType;
import bms.player.beatoraja.MessageRenderer.Message;
import bms.player.beatoraja.audio.*;
import bms.player.beatoraja.config.KeyConfiguration;
import bms.player.beatoraja.config.SkinConfiguration;
import bms.player.beatoraja.decide.MusicDecide;
import bms.player.beatoraja.external.*;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyCommand;
import bms.player.beatoraja.ir.*;
import bms.player.beatoraja.play.BMSPlayer;
import bms.player.beatoraja.play.MusicPlayer;
import bms.player.beatoraja.play.TargetProperty;
import bms.player.beatoraja.result.CourseResult;
import bms.player.beatoraja.result.MusicResult;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.select.bar.TableBar;
import bms.player.beatoraja.skin.SkinAdjustModel;
import bms.player.beatoraja.skin.SkinLoader;
import bms.player.beatoraja.skin.SkinObject.SkinOffset;
import bms.player.beatoraja.skin.SkinProperty;
import bms.player.beatoraja.song.*;
import bms.tool.mdprocessor.MusicDownloadProcessor;

/**
 * アプリケーションのルートクラス (Android 适配修复完整版)
 *
 * @author exch / Modified for Android
 */
public class MainController {

    private static final String VERSION = "beatoraja " + Version.VALUE + " (Android)";

    public static final boolean debug = false;
    public static final int debugTextXpos = 10;

    private final long boottime = System.currentTimeMillis();
    private final Calendar cl = Calendar.getInstance();
    private long mouseMovedTime;

    private BMSPlayer bmsplayer;
    private MusicPlayer musicPlayer;
    private MusicDecide decide;
    private MusicSelector selector;
    private MusicResult result;
    private CourseResult gresult;
    private KeyConfiguration keyconfig;
    private SkinConfiguration skinconfig;
    /**
     * 皮肤清单与参数清单的唯一实现 —— SKINCONFIG 界面与 AUTOPLAY 的皮肤调整窗口
     * （FloatingMenu）共用同一个实例。
     * <p>🔴 不要在别处 new 第二个：{@link SkinAdjustModel#ensureScanned()} 会执行
     * Lua 皮肤脚本，且扫描结果没有跨实例缓存。</p>
     */
    private SkinAdjustModel skinAdjustModel;

    private AudioDriver audio;
    private PlayerResource resource;
    private BitmapFont systemfont;
    private BitmapFont systemfont18;
    /** systemfont 用的 FreeTypeFontGenerator，保留引用用于 Android resume() 时重建字体纹理 */
    private FreeTypeFontGenerator systemfontGenerator;
    /** systemfont 对应的字体文件 FileHandle，resume 时重新创建 generator 用 */
    private FileHandle systemfontFileHandle;

    // ── systemfont 的排版字号 & 高 DPI 光栅化 ─────────────────────────────────
    /**
     * systemfont / systemfont18 的**设计字号**（pt）。
     * <p>🔴 这不是「纹理尺寸」，而是**排版基准**：FloatingMenu / MusicPlayer /
     * LaneRenderer / PracticeConfiguration / FPS / 触摸坐标 全部按这两个字号排的版。
     * 实际光栅化尺寸 = 该值 × {@link #systemFontDpi}。</p>
     */
    private static final int SYSTEM_FONT_SIZE = 24;
    private static final int SYSTEM_FONT_SIZE_SMALL = 18;
    /**
     * 光栅化倍率上限。倍率每 +1，字体图集面积 ×4：当前预生成字符集约 3350 个字形
     * （VL-Gothic 在 0x4E00-0x5FFF 里只覆盖 2915 个），1 倍 ≈ 3 页 1024² RGBA（≈12MB），
     * 2 倍 ≈ 8 页（≈32MB）。4K 屏理论上要 3 倍（≈72MB），就停在这里 ——
     * 宁可轻微放大，也不拿显存冒险。
     */
    private static final float MAX_FONT_DPI = 2f;
    /**
     * 当前生效的字体光栅化倍率（= 逻辑空间 1 单位占多少物理像素）。
     * 1 = 老行为（按设计字号光栅化）；屏幕比逻辑尺寸大时 &gt; 1。
     */
    private float systemFontDpi = 1f;
    private MessageRenderer messageRenderer;
    private MainState current;
    private TimerManager timer;
    private Config config;
    private PlayerConfig player;
    private BMSPlayerMode auto;
    private boolean songUpdated;
    private IRStatus[] ir;
    private RivalDataAccessor rivals = new RivalDataAccessor();
    private RankingDataCache ircache = new RankingDataCache();
    private SpriteBatch sprite;
    private File bmsfile;
    private BMSPlayerInputProcessor input;
    private boolean showfps;
    private PlayDataAccessor playdata;
    private SystemSoundManager sound;
    private Thread screenshot;
    /**
     * 1000Hz 输入轮询线程。
     * 关键：dispose() 必须设 pollingRunning=false 并 interrupt()，否则 1000Hz 循环在 app 退出后继续运行,
     * 持续消耗 CPU,且 input 已被释放后 input.poll() 可能 NPE,导致进程无法被 Android 正常回收。
     */
    private Thread inputPollingThread;
    private volatile boolean pollingRunning = false;
    /** 正在 dispose 标志，防止 dispose 过程中渲染线程继续访问已释放的资源 */
    private volatile boolean disposing = false;
    /**
     * dispose() 是否已执行完毕。见 dispose() 方法头的注释：
     * 本方法有 UI 线程(exit())与 GL 线程(libGDX ldestroy)两条调用路径，必须幂等。
     */
    private volatile boolean disposed = false;
    private MusicDownloadProcessor download;
    /** MusicSelector是否已初始化过（避免每次切回都重新create导致歌曲扫描） */
    private boolean selectorInitialized = false;

    /**
     * 「成绩 / 回放已经写入完成，选曲界面需要重读这几个 sha256」的跨线程信箱。
     *
     * <p>成绩（{@code MusicResult.ScoreWriteThread}）与回放（{@code ReplayWriteThread}）的写入都是
     * fire-and-forget 的后台线程，而 {@code MusicSelector.prepare()} 里的 {@code refreshAfterPlay()}
     * 只在「刚回到选曲界面那一瞬间」读一次。写入慢于那次读 ⇒ 界面永远显示旧成绩，
     * 且 {@code selector} 实例与它的 {@code ScoreDataCache} 全程复用 ⇒ 只能重启恢复。</p>
     *
     * <p>写入线程完成后往这里投递 sha256（{@link #requestScoreRefresh(String)}，任意线程可调）；
     * GL 线程在 {@code MusicSelector.render()} 里取走并重读
     * （{@link #consumeScoreRefresh()}）。</p>
     *
     * <p>用队列而不是集合：投递方是后台线程、消费方是 GL 线程，队列的 poll 循环天然不会
     * 「消费期间新投递的被 clear 掉」。重复项无害（重读一次而已），在消费侧去重。</p>
     */
    private final ConcurrentLinkedQueue<String> pendingScoreRefresh = new ConcurrentLinkedQueue<>();

    /** BeatorajaGame实例引用，用于FloatingMenu回调更新频谱配置 */
    private Object beatorajaGame;

    public static final int offsetCount = SkinProperty.OFFSET_MAX + 1;
    private final SkinOffset[] offset = new SkinOffset[offsetCount];

    protected TextureRegion black;
    protected TextureRegion white;
    private Texture touchPointerTexture;
    private long lastTouchActivity = 0;
    private boolean wasTouching = false;
    private FloatingMenu floatingMenu;

    /** 缓存平台类型，避免每帧调用 Gdx.app.getType() */
    private final boolean isAndroid = com.badlogic.gdx.Gdx.app != null ? com.badlogic.gdx.Gdx.app.getType() == Application.ApplicationType.Android : false;

    private final Array<MainStateListener> stateListener = new Array<MainStateListener>();

    private int detectedRefreshRate = 120; // 记录检测到的原始刷新率

    public MainController(File f, Config config, PlayerConfig player, BMSPlayerMode auto, boolean songUpdated) {
        Config.updateConfigPath();
        PlayerConfig.updateConfigPath();
        this.auto = auto;
        this.songUpdated = songUpdated;

        for(int i = 0;i < offset.length;i++) {
            offset[i] = new SkinOffset();
        }

        // 若 config 为 null（Android 启动时），从磁盘读取已保存的配置
        if (config == null) {
            config = Config.read();
            com.badlogic.gdx.Gdx.app.log("MainController", "Config loaded from disk: " + config.getPlayername());
        }
        this.config = config;

        if(player == null) {
            player = PlayerConfig.readPlayerConfig(config.getPlayerpath(), config.getPlayername());
            com.badlogic.gdx.Gdx.app.log("MainController", "PlayerConfig loaded from disk for: " + config.getPlayername());
        }
        this.player = player;
        this.bmsfile = f;

        if (config.isEnableIpfs()) {
            File ipfspath = new File("ipfs").getAbsoluteFile();
            if (!ipfspath.exists()) ipfspath.mkdirs();
            List<String> roots = new ArrayList<>(Arrays.asList(getConfig().getBmsroot()));
            if (ipfspath.exists() && !roots.contains(ipfspath.toString())) {
                roots.add(ipfspath.toString());
                getConfig().setBmsroot(roots.toArray(new String[roots.size()]));
            }
        }

        playdata = new PlayDataAccessor(config);

        Array<IRStatus> irarray = new Array<IRStatus>();
        for(IRConfig irconfig : player.getIrconfig()) {
            final IRConnection ir = IRConnectionManager.getIRConnection(irconfig.getIrname());
            if(ir != null) {
                if(irconfig.getUserid().length() > 0 && irconfig.getPassword().length() > 0) {
                    IRResponse<IRPlayerData> response = ir.login(new IRAccount(irconfig.getUserid(), irconfig.getPassword(), ""));
                    if(response.isSucceeded()) {
                        irarray.add(new IRStatus(irconfig, ir, response.getData()));
                    } else {
                        Logger.getGlobal().warning("IRへのログイン失敗 : " + response.getMessage());
                    }
                }
            }
        }
        ir = irarray.toArray(IRStatus.class);

        rivals.update(this);

        // Android 屏蔽 PortAudio 驱动
        timer = new TimerManager();
        sound = new SystemSoundManager(this);

        // Android 平台：根据屏幕刷新率设置帧率上限
        if (isAndroid) {
            // 优先尝试从 AndroidLauncher 反射获取由 WindowManager 检测到的准确物理刷新率。
            // libGDX 的 getDisplayModes() 在应用启动初期可能无法获取完整的硬件模式列表。
            float launcherRR = 60f;
            try {
                Class<?> launcherClass = Class.forName("com.starxh.beatoraja.android.AndroidLauncher");
                launcherRR = launcherClass.getField("maxRefreshRate").getFloat(null);
            } catch (Exception e) {
                int maxRR = Gdx.graphics.getDisplayMode().refreshRate;
                for (com.badlogic.gdx.Graphics.DisplayMode mode : Gdx.graphics.getDisplayModes()) {
                    if (mode.refreshRate > maxRR) maxRR = mode.refreshRate;
                }
                launcherRR = maxRR;
            }
            this.detectedRefreshRate = Math.round(launcherRR);
            Gdx.app.log("beatoraja", "Hardware max refresh rate detected: " + detectedRefreshRate + "Hz");

            // 将内部帧率限制设为与物理刷新率一致。
            // 这样既能跑满 120Hz，又能触发 render() 中的 Choreographer VSync 相位对齐逻辑。
            config.setMaxFramePerSecond(detectedRefreshRate);
            Gdx.app.log("beatoraja", "Android: enable VSync-aligned frame limiter at " + detectedRefreshRate + "fps");

            // ─── 积极请求高刷新率 (Android 11+ Frame Rate API) ───
            updateFrameRateAPI(detectedRefreshRate);
        }
    }

    public SkinOffset getOffset(int index) { return offset[index]; }
    public SongDatabaseAccessor getSongDatabase() { return MainLoader.getScoreDatabaseAccessor(); }
    public PlayDataAccessor getPlayDataAccessor() { return playdata; }
    public RivalDataAccessor getRivalDataAccessor() { return rivals; }
    public RankingDataCache getRankingDataCache() { return ircache; }
    public SpriteBatch getSpriteBatch() { return sprite; }
    public PlayerResource getPlayerResource() { return resource; }
    public bms.player.beatoraja.select.MusicSelector getMusicSelector() { return selector; }
    public Config getConfig() { return config; }
    public PlayerConfig getPlayerConfig() { return player; }
    /** 皮肤清单与参数清单的唯一实现（SKINCONFIG 界面 / AUTOPLAY 皮肤调整窗口共用） */
    public SkinAdjustModel getSkinAdjustModel() { return skinAdjustModel; }
    public BitmapFont getSystemFont18() { return systemfont18; }
    public Object getBeatorajaGame() { return beatorajaGame; }
    public void setBeatorajaGame(Object game) { this.beatorajaGame = game; }

    /**
     * 构建日语字符集字符串，用于 FreeTypeFontGenerator 生成包含假名/汉字的 BitmapFont。
     * 包含: ASCII 可印字符 + 平假名 + 片假名 + 常用汉字范围 + 半角片假名 + 常用符号。
     * FreeTypeFontGenerator 会按 font 文件实际覆盖度 pick，实际不存在的字形会跳过不报错。
     */
    private static String buildJapaneseCharacters() {
        StringBuilder sb = new StringBuilder();
        // ASCII 可印字符 (0x20-0x7E)
        for (int i = 0x20; i <= 0x7E; i++) sb.append((char) i);
        // 平假名: \u3040-\u309F
        for (int i = 0x3040; i <= 0x309F; i++) sb.append((char) i);
        // 片假名: \u30A0-\u30FF
        for (int i = 0x30A0; i <= 0x30FF; i++) sb.append((char) i);
        // 半角片假名: \uFF65-\uFF9F
        for (int i = 0xFF65; i <= 0xFF9F; i++) sb.append((char) i);
        // 常用汉字 (CJK Unified Ideographs): \u4E00-\u9FA5
        // 包含日语常用汉字及中文汉字。使用 incremental 模式时，此处仅作为预生成集合。
        for (int i = 0x4E00; i <= 0x5FFF; i++) sb.append((char) i);
        // 追加全角符号、数字、英大文字 (FF01-FF5E)
        for (int i = 0xFF01; i <= 0xFF5E; i++) sb.append((char) i);
        return sb.toString();
    }

    /**
     * 逻辑空间 1 单位在 GL 视口里占多少物理像素 —— 决定字体该按多少倍光栅化。
     *
     * <p>与 {@link UiCanvas#scale(int, int)} 是两件事：那个管「设计画布 ↔ 逻辑空间」，
     * 这个管「逻辑空间 ↔ 物理像素」。元素尺寸由前者保证占屏比例恒定，
     * 但字形是固定像素的位图 —— 物理像素变多就必须按同样倍率提高光栅化分辨率。</p>
     *
     * <p>{@code create()} 里取 {@code Gdx.graphics.getWidth()/getHeight()} 是安全的：
     * libGDX Android 后端在 {@code AndroidGraphics.onSurfaceChanged()} 里**先**把真实
     * surface 尺寸写进 width/height，**再**调 {@code create()} 与 {@code resize()}
     * （见 AndroidGraphics:307-321）。配合 {@code FillResolutionStrategy}
     * （AndroidLauncher 显式设置），surface = 屏幕原生分辨率。</p>
     */
    private float computeSystemFontDpi() {
        final Resolution res = config.getResolution();
        final float ratio = UiCanvas.pixelRatio(res.width, res.height,
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        return Math.max(1f, Math.min(MAX_FONT_DPI, ratio));
    }

    /**
     * 生成 systemfont / systemfont18。{@code create()} 与 Android {@code resume()}
     * （GL 上下文重建后纹理全失效）共用这一处。
     *
     * <p>🔴 <b>为什么要有高 DPI 光栅化</b>：这两个字体按 24pt / 18pt 排版，
     * 而 GL 视口是屏幕原生分辨率。例：2560×1600 屏跑 1280×720 逻辑 ⇒
     * 1 逻辑单位 = 2 物理像素，24pt 的字实际要占 48px。
     * 拿 24px 的字形纹理去铺 = 2 倍放大，边缘必然发虚 ——
     * FloatingMenu / MusicPlayer 的「字体模糊」就是这个，与分辨率无关。</p>
     *
     * <p><b>做法</b>：按 {@link #systemFontDpi} 放大光栅化尺寸（24 → 48px），
     * 再用 {@code getData().setScale(设计字号 / 光栅化字号)} 把「世界单位尺寸」压回 24。
     * 排版**逐位不变**（setScale 只按比例等比改 lineHeight / ascent / pad 等度量），
     * 而纹素与物理像素变成 1:1。字形宽高由
     * {@code BitmapFontCache.addGlyph()} 在绘制时乘 scaleX/scaleY；
     * incremental 的惰性字形也用 {@code ascent / scaleY} 反算基线
     * （见 FreeTypeFontGenerator.FreeTypeBitmapFontData.getGlyph）
     * ⇒ 与增量渲染兼容，也让 {@code FloatingMenu} 里临时的 setScale 能按基准值还原。</p>
     *
     * <p>🔴 <b>两个字号必须各用独立的 FreeTypeFontParameter</b>：incremental 模式下
     * {@code data.parameter = parameter} 存的是**引用**，之后再改 {@code parameter.size}
     * 会连带改掉前一个字体的惰性字形尺寸。（老代码共用一个 parameter，
     * 于是 24pt 的 systemfont 遇到缺字回退时实际按 18px 生成。）</p>
     */
    private void generateSystemFonts() {
        // resolveFontFileHandle() 在 fontpath 为 null/空 时返回 null，而
        // FreeTypeFontGenerator(null) 会先在 fontFile.nameWithoutExtension() 抛 NPE ——
        // NPE 不是 GdxRuntimeException，会直接穿过 create() 的 catch 把启动打崩。
        // 显式换成 GdxRuntimeException：create() 记一条 severe 后继续（字体为 null 的降级
        // 各绘制点都有 null 守卫），且 systemfontFileHandle 保持 null ⇒ resume() 不会重试。
        if (systemfontFileHandle == null) {
            throw new GdxRuntimeException("systemfont path 未设置/解析失败，跳过字体生成");
        }

        systemFontDpi = computeSystemFontDpi();

        // 🔴 用局部变量承接，全部成功后再一次性写回字段：
        // 中途失败（OOM 等）时字段保持原值，不会留下「systemfont 有了、systemfont18 还是 null」
        // 这种半初始化状态；新建的 generator 也在 catch 里回收，不泄漏 FreeType face。
        final FreeTypeFontGenerator gen = new FreeTypeFontGenerator(systemfontFileHandle);
        try {
            // 字符集只构建一次；generateData() 内部会 toCharArray() 复制，两个 parameter 共享安全。
            final String chars = buildJapaneseCharacters();

            final FreeTypeFontParameter p24 = new FreeTypeFontParameter();
            p24.incremental = true; // 启用增量渲染，支持全量 CJK 字符
            p24.characters = chars;
            p24.size = Math.max(1, Math.round(SYSTEM_FONT_SIZE * systemFontDpi));
            final BitmapFont f24 = gen.generateFont(p24);
            f24.getData().setScale(SYSTEM_FONT_SIZE / (float) p24.size);

            // Pre-generate 18pt version for LaneRenderer and PracticeConfiguration
            final FreeTypeFontParameter p18 = new FreeTypeFontParameter();
            p18.incremental = true;
            p18.characters = chars;
            p18.size = Math.max(1, Math.round(SYSTEM_FONT_SIZE_SMALL * systemFontDpi));
            final BitmapFont f18 = gen.generateFont(p18);
            f18.getData().setScale(SYSTEM_FONT_SIZE_SMALL / (float) p18.size);

            // ── 两个都生成成功，才替换引用 ──
            systemfontGenerator = gen;
            systemfont = f24;
            systemfont18 = f18;

            Gdx.app.log("MainController", "systemfont dpi=" + systemFontDpi
                    + " raster=" + p24.size + "pt/" + p18.size + "pt"
                    + " baseScale=" + f24.getData().scaleX + "/" + f18.getData().scaleX
                    + " screen=" + Gdx.graphics.getWidth() + "x" + Gdx.graphics.getHeight());
        } catch (RuntimeException | Error e) {
            try { gen.dispose(); } catch (Throwable ignore) {}
            throw e;
        }
    }

    /**
     * 解析字体文件 FileHandle，支持 Android 多路径查找：
     * 1. beatoraja.root 相对路径（外部存储皮肤/字体）
     * 2. internal（APK assets）
     * 3. absolute（绝对路径）
     * Desktop 直接使用 internal。
     */
    public static FileHandle resolveFontFileHandle(String fontpath) {
        if (fontpath == null || fontpath.isEmpty()) return null;
        String path = fontpath.replace("\\", "/");

        if (com.badlogic.gdx.Gdx.app.getType() == Application.ApplicationType.Android) {
            // 1. beatoraja.root 相对路径
            String root = System.getProperty("beatoraja.root", null);
            if (root != null && !path.startsWith("/")) {
                String rootPath = root + "/" + path;
                FileHandle fh = com.badlogic.gdx.Gdx.files.absolute(rootPath);
                com.badlogic.gdx.Gdx.app.log("FontDebug", "resolveFont [beatoraja.root]: " + rootPath + " -> exists=" + fh.exists());
                if (fh.exists()) return fh;
            }
            // 2. absolute
            FileHandle fhAbs = com.badlogic.gdx.Gdx.files.absolute(path);
            com.badlogic.gdx.Gdx.app.log("FontDebug", "resolveFont [absolute]: " + path + " -> exists=" + fhAbs.exists());
            if (fhAbs.exists()) return fhAbs;
            // 3. internal (APK assets)
            FileHandle fhInt = com.badlogic.gdx.Gdx.files.internal(path);
            com.badlogic.gdx.Gdx.app.log("FontDebug", "resolveFont [internal]: " + path + " -> exists=" + fhInt.exists());
            if (fhInt.exists()) return fhInt;
            // fallback: 返回 internal（后续会抛明确错误）
            com.badlogic.gdx.Gdx.app.error("FontDebug", "resolveFont FAILED: " + path + " not found in any location (beatoraja.root=" + root + ")");
            return com.badlogic.gdx.Gdx.files.internal(path);
        } else {
            return com.badlogic.gdx.Gdx.files.internal(path);
        }
    }

    // --- 性能 API 反射缓存 ---
    private java.lang.reflect.Method setFrameRateMethod = null;


    private void updateFrameRateAPI(int targetFPS) {
        if (!isAndroid) return;
        try {
            Object graphics = Gdx.graphics;
            java.lang.reflect.Field viewField = graphics.getClass().getDeclaredField("view");
            viewField.setAccessible(true);
            Object view = viewField.get(graphics);

            if (view != null) {
                java.lang.reflect.Method getHolder = view.getClass().getMethod("getHolder");
                Object holder = getHolder.invoke(view);
                if (holder != null) {
                    java.lang.reflect.Method getSurface = holder.getClass().getMethod("getSurface");
                    Object surface = getSurface.invoke(holder);
                    if (surface != null) {
                        java.lang.reflect.Method isValid = surface.getClass().getMethod("isValid");
                        if (Boolean.TRUE.equals(isValid.invoke(surface))) {
                            if (setFrameRateMethod == null) {
                                setFrameRateMethod = surface.getClass().getMethod("setFrameRate", float.class, int.class);
                            }
                            // FRAME_RATE_COMPATIBILITY_DEFAULT (0)：游戏应使用默认兼容模式
                            // 不使用 FIXED_SOURCE（视频模式），不使用 CHANGE_FRAME_RATE_ALWAYS
                            setFrameRateMethod.invoke(surface, (float) targetFPS, 0);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // 静默失败
        }
    }

    public void changeState(MainStateType state) {
        // 保险：任何状态切换都先尝试关闭文本输入模式，防止搜索框干扰
        if (input != null && input.getKeyBoardInputProcesseor() != null) {
            input.getKeyBoardInputProcesseor().setTextInputMode(false);
        }

        // 在切换状态时，再次积极请求高刷新率
        if (isAndroid) {
            // 保持内部限帧与物理刷新率同步，维持 VSync 对齐稳定性。
            config.setMaxFramePerSecond(detectedRefreshRate);

            // 依然积极请求物理最高刷新率，防止系统在界面切换时降频
            updateFrameRateAPI(detectedRefreshRate);
        }

        MainState newState = null;
        switch (state) {
            case MUSICSELECT:
                newState = selector;
                break;
            case DECIDE: newState = decide; break;
            case PLAY:
                if (bmsplayer != null) { bmsplayer.dispose(); }
                // 不再调用 System.gc()：
                // - ART/HotSpot 都不保证立即执行,可能延后到下次分配时反而触发长 STW
                // - 实际想要的是"老 BMSPlayer 释放的内存尽快可复用",交给年轻代自然晋升即可
                bmsplayer = new BMSPlayer(this, resource);
                newState = bmsplayer;
                break;
            case MUSICPLAYER:
                if (musicPlayer != null) { musicPlayer.dispose(); }
                musicPlayer = new MusicPlayer(this);
                newState = musicPlayer;
                break;
            case RESULT: newState = result; break;
            case COURSERESULT: newState = gresult; break;
            case CONFIG: newState = keyconfig; break;
            case SKINCONFIG: newState = skinconfig; break;
        }

        if (newState != null && current != newState) {
            if(current != null) {
                current.shutdown();
                current.setSkin(null);
            }
            // MusicSelector只在首次创建时初始化，后续切换跳过create()但需要加载skin
            if (state == MainStateType.MUSICSELECT) {
                if (!selectorInitialized) {
                    newState.create();
                    selectorInitialized = true;
                } else if (newState.getSkin() == null) {
                    // 跳过create时需要手动加载skin
                    newState.loadSkin(bms.player.beatoraja.skin.SkinType.MUSIC_SELECT);
                }
            } else {
                newState.create();
            }
            if(newState.getSkin() != null) { newState.getSkin().prepare(newState); }
            current = newState;
            timer.setMainState(newState);
            current.prepare();

            // 切换状态时，强制清理输入状态，防止前一个界面的模拟按键（如 Result 界的 ESCAPE）泄露到新界面
            if (input != null && input.getKeyBoardInputProcesseor() != null) {
                input.getKeyBoardInputProcesseor().clear();
            }

            updateMainStateListener(0);
        }

        // Android RESULT界面触摸转ESCAPE（同时保留浮动菜单：点图标弹按键覆盖层，其余触摸退出结果界面）
        if (isAndroid && (state == MainStateType.RESULT || state == MainStateType.COURSERESULT)) {
            final com.badlogic.gdx.InputProcessor escapeMapper = new com.badlogic.gdx.InputProcessor() {
                private long lastTouchTime = 0;
                @Override
                public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                    long now = System.nanoTime() / 1000;
                    if (now - lastTouchTime > 200000) {
                        lastTouchTime = now;
                        input.getKeyBoardInputProcesseor().simulateKeyPress(com.badlogic.gdx.Input.Keys.ESCAPE);
                    }
                    return true;
                }
                @Override public boolean touchUp(int screenX, int screenY, int pointer, int button) { return true; }
                @Override public boolean touchDragged(int screenX, int screenY, int pointer) { return true; }
                @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) { return true; }
                @Override
                public boolean keyDown(int keycode) {
                    // Android back键在 AndroidLauncher.onKeyDown 中已被映射为 ESCAPE，
                    // 此处拦截 ESCAPE 并触发与触摸相同的退出行为
                    if (keycode == com.badlogic.gdx.Input.Keys.ESCAPE || keycode == com.badlogic.gdx.Input.Keys.BACK) {
                        input.getKeyBoardInputProcesseor().simulateKeyPress(com.badlogic.gdx.Input.Keys.ESCAPE);
                        return true;
                    }
                    return false;
                }
                @Override public boolean keyUp(int keycode) { return false; }
                @Override public boolean keyTyped(char character) { return false; }
                @Override public boolean mouseMoved(int screenX, int screenY) { return false; }
                @Override public boolean scrolled(float amountX, float amountY) { return false; }
            };
            // RESULT/COURSERESULT 也要浮动菜单：图标常驻，点击图标<b>不展开菜单面板</b>，
            // 直接弹出"7K 键 + scratch"覆盖层（见 FloatingMenu#setResultMode）。
            // 触摸事件先给浮动菜单（命中图标或覆盖层则消费），未命中的触摸再落到
            // escapeMapper → ESCAPE，保持原有的"点一下退出结果界面"行为。
            if (floatingMenu != null) {
                floatingMenu.setVisible(true);
                floatingMenu.setSelectMode(false);
                floatingMenu.setKeyConfigMode(false);
                floatingMenu.setSkinSelectMode(false);
                floatingMenu.setPlayMode(false);
                floatingMenu.setPracticeMode(false);
                floatingMenu.setAutoplayMode(false);
                floatingMenu.setResultMode(true);
                Gdx.input.setInputProcessor(new InputMultiplexer(floatingMenu, escapeMapper));
            } else {
                Gdx.input.setInputProcessor(escapeMapper);
            }
        } else {
            // 浮动菜单：在 PLAY / DECIDE / MUSICPLAYER 状态隐藏（除非 config 允许在 PLAY 显示）
            if (floatingMenu != null) {
                boolean menuVisible = state != MainStateType.PLAY && state != MainStateType.DECIDE && state != MainStateType.MUSICPLAYER;
                // Practice 模式：浮动图标常驻显示，不受 isShowFloatingMenuInPlay 配置限制
                // （常驻 + 30% 不透明度绘制，见 FloatingMenu#setPracticeMode）
                final boolean practicePlay = state == MainStateType.PLAY
                        && resource != null && resource.getPlayMode() != null
                        && resource.getPlayMode().mode == BMSPlayerMode.Mode.PRACTICE;
                if (practicePlay) {
                    menuVisible = true;
                }
                // AUTOPLAY 模式：浮动图标同样常驻 —— 它是皮肤调整窗口的唯一入口
                // （见 FloatingMenu#setAutoplayMode / SKIN_ADJUST_KEYCODE）。
                // 🔴 判定只能用 resource.getPlayMode().mode：BMSPlayer.getMode() 返回的是
                //    谱面类型（7KEYS 等），而且 resource 是长命对象 —— 不 gate 在 PLAY
                //    状态的话，在选曲界面读到的还是上一次的 mode。
                final boolean autoplayPlay = state == MainStateType.PLAY
                        && resource != null && resource.getPlayMode() != null
                        && resource.getPlayMode().mode == BMSPlayerMode.Mode.AUTOPLAY;
                if (autoplayPlay) {
                    menuVisible = true;
                }
                if (state == MainStateType.PLAY && config != null && config.isShowFloatingMenuInPlay()) {
                    menuVisible = true;
                }
                // MUSICSELECT 必须常驻浮动菜单（无论默认皮肤还是自定义皮肤，避免被其他路径覆盖）
                if (state == MainStateType.MUSICSELECT) {
                    menuVisible = true;
                }
                floatingMenu.setVisible(menuVisible);
                floatingMenu.setSelectMode(state == MainStateType.MUSICSELECT);
                floatingMenu.setKeyConfigMode(state == MainStateType.CONFIG);
                floatingMenu.setSkinSelectMode(state == MainStateType.SKINCONFIG);
                floatingMenu.setPlayMode(state == MainStateType.PLAY);
                floatingMenu.setPracticeMode(practicePlay);
                floatingMenu.setAutoplayMode(autoplayPlay);
                // 非 RESULT 状态：确保结果界面的"图标直出按键覆盖层"模式已退出
                floatingMenu.setResultMode(false);
            }
            // 将 FloatingMenu 作为最高优先级处理器加入 InputMultiplexer
            refreshInputProcessor();
        }

        // 在PLAY界面，将PlayTouchKeyMapper添加到InputMultiplexer
        if (isAndroid && current instanceof BMSPlayer) {
            try {
                BMSPlayer player = (BMSPlayer) current;
                java.lang.reflect.Field field = BMSPlayer.class.getDeclaredField("touchKeyMapper");
                field.setAccessible(true);
                bms.player.beatoraja.play.PlayTouchKeyMapper touchKeyMapper =
                    (bms.player.beatoraja.play.PlayTouchKeyMapper) field.get(player);
                if (touchKeyMapper != null) {
                    // 将触摸按键映射添加到InputMultiplexer，play界面禁用触摸检测让触摸只流向touchkey
                    if (floatingMenu != null) {
                        Gdx.input.setInputProcessor(new InputMultiplexer(
                            floatingMenu,
                            touchKeyMapper,
                            input.getKeyBoardInputProcesseor()
                        ));
                    } else {
                        Gdx.input.setInputProcessor(new InputMultiplexer(
                            touchKeyMapper,
                            input.getKeyBoardInputProcesseor()
                        ));
                    }
                    Gdx.app.log("MainController", "PlayTouchKeyMapper registered as InputProcessor (stage touch disabled)");
                }
            } catch (Exception e) {
                Gdx.app.log("MainController", "Failed to register PlayTouchKeyMapper: " + e.getMessage());
            }
        }
    }

    /**
     * 重建主 InputMultiplexer（浮动菜单 → 当前状态的 Stage → 键盘）。
     *
     * <p>状态切换时自动调用一次。此外必须手动调用的场景：{@code MusicSelector}
     * 换皮肤 —— 它覆写的 {@code setSkin()} 会重建原生搜索框并 {@code setStage()}，
     * 而 {@code InputMultiplexer} 里持有的是<b>旧</b> {@code Stage} 对象。
     * 不刷新的话新的搜索框收不到任何触摸（表现为「换完皮肤后点搜索框没反应」，
     * 要等下一次状态切换才恢复）。</p>
     *
     * <p>🔴 本方法只覆盖「非 PLAY」的形态。PLAY 界面在 {@link #changeState} 里还会把
     * {@code PlayTouchKeyMapper} 插进 multiplexer，那一层不在这里处理 ——
     * 所以别在 PLAY 状态下拿它当「重建快捷键」用。</p>
     */
    public void refreshInputProcessor() {
        if (current != null && current.getStage() != null) {
            if (floatingMenu != null) {
                Gdx.input.setInputProcessor(new InputMultiplexer(floatingMenu, current.getStage(), input.getKeyBoardInputProcesseor()));
            } else {
                Gdx.input.setInputProcessor(new InputMultiplexer(current.getStage(), input.getKeyBoardInputProcesseor()));
            }
        } else if (input != null) {
            if (floatingMenu != null) {
                Gdx.input.setInputProcessor(new InputMultiplexer(floatingMenu, input.getKeyBoardInputProcesseor()));
            } else {
                Gdx.input.setInputProcessor(input.getKeyBoardInputProcesseor());
            }
        }
    }

    public MainState getCurrentState() { return current; }
    public void setPlayMode(BMSPlayerMode auto) { this.auto = auto; }

    public void create() {
        final long t = System.currentTimeMillis();

        // ── OpenGL ES 性能优化（针对低端Android设备如小米2s）──
        // 禁用2D游戏不需要的GL特性以减少GPU驱动开销
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);      // 2D精灵不需要深度测试
        Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);    // 不需要模板缓冲
        Gdx.gl.glDisable(GL20.GL_DITHER);          // 禁用抖动减少带宽

        // 启用2D游戏需要的优化
        Gdx.gl.glEnable(GL20.GL_BLEND);            // Alpha混合需要
        Gdx.gl.glEnable(GL20.GL_TEXTURE_2D);       // 启用纹理映射

        // 混合模式优化：默认使用预乘Alpha以提高性能
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Mipmap生成优化提示（OpenGL ES兼容）
        Gdx.gl.glHint(GL20.GL_GENERATE_MIPMAP_HINT, GL20.GL_FASTEST);

        // 优化：增大 SpriteBatch 缓冲区到 4096，大幅减少 GL drawArrays 调用次数
        // 高BPM谱面会有数百音符同时渲染，默认1000会频繁flush导致性能问题
        sprite = new SpriteBatch(4096);
        SkinLoader.initPixmapResourcePool(config.getSkinPixmapGen());
        // 清空上次进程残留的 BitmapFont 缓存（Android process restart 时 static 变量可能保留）
        bms.player.beatoraja.skin.BitmapFontCache.invalidate();

        try {
            FileHandle fontFile = resolveFontFileHandle(config.getSystemfontpath());

            // 保留 fontFile 引用，供 Android resume() 时重建字体纹理使用。
            // generator 的创建与释放都在 generateSystemFonts() / dispose() 里，不在此处 dispose。
            systemfontFileHandle = fontFile;
            generateSystemFonts();
        } catch (GdxRuntimeException e) {
            Logger.getGlobal().severe("System Font読み込み失敗: " + e.getMessage());
        }
        messageRenderer = new MessageRenderer(config.getMessagefontpath());

        input = new BMSPlayerInputProcessor(config, player);
        input.setMainController(this);

        // Android uses the audio driver created by AndroidLauncher.createAudio() (OboeAudio)
        // Only create GdxSoundDriver if no audio driver exists (desktop fallback)
        if (Gdx.audio != null && Gdx.audio instanceof AudioDriver) {
            audio = (AudioDriver) Gdx.audio;
        } else {
            audio = new GdxSoundDriver(config);
        }

        resource = new PlayerResource(audio, config, player);
        selector = new MusicSelector(this, songUpdated);
        decide = new MusicDecide(this);
        result = new MusicResult(this);
        gresult = new CourseResult(this);
        keyconfig = new KeyConfiguration(this);
        // 皮肤调整模型：SKINCONFIG 界面与 AUTOPLAY 的皮肤调整窗口共用一个实例
        skinAdjustModel = new SkinAdjustModel(this, player);
        skinconfig = new SkinConfiguration(this, skinAdjustModel);
        musicPlayer = new MusicPlayer(this);

        // 初始化浮动快捷键菜单（必须在 changeState 之前，确保 InputMultiplexer 包含它）
        if (isAndroid) {
            floatingMenu = new FloatingMenu();
            floatingMenu.setKeyboardInput(input.getKeyBoardInputProcesseor());
        }

        if (bmsfile != null) {
            if(resource.setBMSFile(Gdx.files.absolute(bmsfile.toString()), auto)) {
                changeState(MainStateType.PLAY);
            } else {
                // 如果指定文件加载失败，跳转到选曲界面而不是直接退出
                Gdx.app.error("MainController", "Failed to load BMS file: " + bmsfile + ", redirecting to Selector.");
                changeState(MainStateType.MUSICSELECT);
            }
        } else {
            changeState(MainStateType.MUSICSELECT);
        }

        Logger.getGlobal().info("初期化時間(ms) : " + (System.currentTimeMillis() - t));

        // ── JIT预热：提前编译渲染热点代码 ──
        try {
            Logger.getGlobal().info("Starting JIT warmup...");
            // 1. 执行几帧空渲染，触发SpriteBatch热点JIT编译
            for (int i = 0; i < 3; i++) {
                sprite.begin();
                sprite.end();
            }
            Logger.getGlobal().info("JIT warmup completed");
        } catch (Exception e) {
            Logger.getGlobal().warning("JIT warmup failed: " + e.getMessage());
        }

        // 高精度输入轮询线程：硬编码 1000Hz，无条件轮询
        // Android 下 keyDown 事件是异步的，必须每周期无条件调用 poll() 更新 keystate，
        // 否则 keystate[keycode] 会在 keyDown 与 render 之间保持旧状态（如 Enter 被截断问题）。
        // 关键修复：循环条件改用 pollingRunning 标志，dispose() 中可正常退出。
        pollingRunning = true;
        inputPollingThread = new Thread(() -> {
            long nextPollTime = System.nanoTime();
            final long pollIntervalNs = 1_000_000L; // 1000Hz
            while (pollingRunning) {
                // 即使 pollingRunning 在 sleep 期间被翻转,也要先完成这次 poll 释放帧状态,
                // 避免 dispose 后 input.poll() 看不到已 dispose 的 input。
                if (input != null) {
                    input.poll();
                }
                nextPollTime += pollIntervalNs;
                final long sleepNs = nextPollTime - System.nanoTime();
                if (sleepNs > 50000) {
                    LockSupport.parkNanos(sleepNs);
                }
            }
        }, "InputPollingThread");
        inputPollingThread.setDaemon(true); // 守护线程:JVM 退出时强制结束
        // 1000Hz 轮询被渲染/GC 抢占会让 keyDown 上屏延迟、判定时间戳抖动。
        // NORM+1：比普通线程略高，但不与判定线程(NORM+2)抢占。
        try {
            inputPollingThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
        } catch (Throwable ignored) {
            // setPriority 可能受 SecurityManager / 部分 ROM 限制，失败不影响功能
        }
        inputPollingThread.start();

        Array<String> targetlist = new Array<String>(player.getTargetlist());
        for(int i = 0;i < rivals.getRivalCount();i++) {
            targetlist.add("RIVAL_" + (i + 1));
        }
        TargetProperty.setTargets(targetlist.toArray(String.class), this);

        Pixmap plainPixmap = new Pixmap(2,1, Pixmap.Format.RGBA8888);
        plainPixmap.drawPixel(0,0, Color.toIntBits(255,0,0,0));
        plainPixmap.drawPixel(1,0, Color.toIntBits(255,255,255,255));
        Texture plainTexture = new Texture(plainPixmap);
        black = new TextureRegion(plainTexture,0,0,1,1);
        white = new TextureRegion(plainTexture,1,0,1,1);
        plainPixmap.dispose();

        // 创建触摸指针纹理
        if (isAndroid) {
            touchPointerTexture = createTouchPointerTexture();
        }

        Gdx.gl.glClearColor(0, 0, 0, 1);

        if (config.isEnableIpfs()) {
            download = new MusicDownloadProcessor(config.getIpfsUrl(), (md5) -> {
                SongData[] s = getSongDatabase().getSongDatas(md5);
                String[] result = new String[s.length];
                for(int i = 0;i < result.length;i++) { result[i] = s[i].getPath(); }
                return result;
            });
            download.start(null);
        }

        if(ir.length > 0) {
            messageRenderer.addMessage(ir.length + " IR Connection Succeed" ,5000, Color.GREEN, 1);
        }
    }

    private long prevtime;
    private final StringBuilder message = new StringBuilder();
    /**
     * 绝对帧时间基准（纳秒）。用于精确帧率控制：
     * 每帧等待至此时间点，然后将其增加一个帧周期，
     * 从而避免逐帧累积误差导致的帧率漂移。
     */
    private long nextFrameTimeNanos = 0;

    // ─── 帧限制器调参（纳秒）───
    /**
     * 提交前置量上限：在目标时刻【之前】醒来，而不是"准点"醒来。
     *
     * <p>唤醒过冲与 swap 提交开销都由这段余量吸收，不再直接暴露在合成截止时间上：
     * nanosleep / parkNanos 的语义是"不早于"，低端机在限频/深 idle 下实测过冲 0.3~2ms，
     * 且偏差单向（只会晚、不会早）。旧实现在 Android 上跳过忙等 → 每帧都以 T+ε 提交，
     * 余量为 0；一旦越过合成截止时间就是丢一整帧。</p>
     *
     * <p>提前提交同时也是【降低】呈现延迟的方向（缓冲更早入队、更可能赶上本次合成）。</p>
     *
     * <p>实际取值还会被 clamp 到 frameInterval/4，保证高刷下前置量不喧宾夺主。</p>
     */
    private static final long SUBMIT_LEAD_MAX_NANOS = 2_000_000L;

    /**
     * 末尾忙等时长：保证"绝不晚于目标时刻"（唤醒时刻确定化）。
     * 成本：250µs / 16.67ms ≈ 单核 1.5%（60Hz）；120Hz 帧周期 8.33ms ≈ 3%。
     * 若实测 CPU 有压力可置 0 — 此时由 SUBMIT_LEAD 独自兜底（前置量本身已能吸收过冲）。
     */
    private static final long BUSY_WAIT_TAIL_NANOS = 250_000L;

    /** parkNanos 步长下限：越接近目标步长越小，压小最后一次 park 的过冲幅度 */
    private static final long PARK_SLICE_MIN_NANOS = 200_000L;

    /**
     * 相位纠正上限：每帧最多把目标时刻往回拉这么多，且【只往回拉】。
     *
     * <p>修正的是"应用周期(1e9/maxFPS) ≠ 面板真实周期"造成的相位漂移，典型 10~20µs/帧；
     * 不修的话每一帧都往后滑一点，约 20s 漂满一个周期 —— 提交时刻越过合成截止时间，
     * 丢一整帧，表现就是周期性微卡顿。</p>
     *
     * <p>有上限 ⇒ vsync 时间戳抖动/滞后最多造成 250µs 偏差，绝不会把提交推晚。</p>
     */
    private static final long PHASE_FIX_MAX_NANOS = 250_000L;

    /** 记录来自 Android Choreographer 的最新 VSync 时间戳（纳秒） */
    private static volatile long lastVsyncTimeNanos = 0;
    /** 记录上面那个时间戳被写入的时刻（纳秒），用于判断其新鲜度 */
    private static volatile long lastVsyncRecordNanos = 0;
    /** 供 AndroidLauncher 调用，同步 VSync 相位 */
    public static void setLastVsyncTimeNanos(long time) {
        lastVsyncRecordNanos = System.nanoTime(); // 先记写入时刻
        lastVsyncTimeNanos = time;                // 再写时间戳（作为发布动作）
    }

    // ─── 等比视口参数 ───
    // Android 端屏幕宽高比通常不是 16:9（如 2400x1080 = 20:9），
    // 直接全屏渲染会拉伸画面。以下字段存储每帧计算的等比视口位置，
    // 供输入坐标转换使用。
    private int viewportX = 0;
    private int viewportY = 0;
    private int viewportW = 0;
    private int viewportH = 0;
    private int lastGameW = 0;
    private int lastGameH = 0;

    // ─── 性能优化：预分配复用对象，避免每帧 GC ───
    /** 预分配的投影矩阵，每帧复用而非 new Matrix4() */
    private final Matrix4 projMatrix = new Matrix4();
    /** 预分配的坐标文字 StringBuilder，避免每帧 String.format() */
    private final StringBuilder coordTextBuilder = new StringBuilder(16);

    /**
     * 将屏幕坐标 (Gdx.input.getX/getY) 转换为游戏逻辑坐标（皮肤坐标系）。
     * 在等比视口模式下，屏幕坐标需要扣除 pillarbox/letterbox 偏移并缩放。
     */
    public int screenToGameX(int screenX) {
        if (viewportW <= 0 || lastGameW <= 0) return screenX;
        return Math.round((screenX - viewportX) * (float) lastGameW / viewportW);
    }

    public int screenToGameY(int screenY) {
        if (viewportH <= 0 || lastGameH <= 0) return screenY;
        return Math.round((screenY - viewportY) * (float) lastGameH / viewportH);
    }

    public int getViewportX() { return viewportX; }
    public int getViewportY() { return viewportY; }
    public int getViewportW() { return viewportW; }
    public int getViewportH() { return viewportH; }

    /**
     * 设定主渲染的 GL 视口与皮肤投影矩阵。
     *
     * <p>必须在两处调用：{@code current.render()} <b>之前</b>（它内部可能依赖当前投影），
     * 以及渲染之后、画皮肤之前 —— 因为 {@code current.render()} 里的 BGA FBO 渲染、
     * {@code Stage.Viewport.apply()}、LaneRenderer 首次初始化等都会改写 GL 视口或
     * SpriteBatch 的投影矩阵；不复原的话，本帧的皮肤 / 音符 / 判定就会画到错误矩形里。</p>
     */
    private void applyMainViewportAndProjection() {
        Gdx.gl.glViewport(viewportX, viewportY, viewportW, viewportH);
        if (current.getSkin() != null) {
            sprite.setProjectionMatrix(projMatrix.setToOrtho2D(0, 0, current.getSkin().getWidth(), current.getSkin().getHeight()));
        } else {
            sprite.setProjectionMatrix(projMatrix.setToOrtho2D(0, 0, config.getResolution().width, config.getResolution().height));
        }
    }

    /**
     * 生成触摸指针纹理（64x64）：透明底 + 半透明白外圈 + 偏白内圆 + 实心白点。
     *
     * <p>内圆原本是绿色 {@code (0,1,0)}，按需求改成偏白（略偏冷，避免与纯白外圈糊成一片）。</p>
     *
     * <p>纹理会在 GL 上下文丢失后失效，所以 {@link #create()} 与 {@link #resume()} 都要重建；
     * 两处统一走这里，免得配色只改一处、前后台切一次就变回旧色。</p>
     */
    private Texture createTouchPointerTexture() {
        final int pointerSize = 64;
        Pixmap pointerPixmap = new Pixmap(pointerSize, pointerSize, Pixmap.Format.RGBA8888);
        // 透明背景
        pointerPixmap.setColor(0, 0, 0, 0);
        pointerPixmap.fill();
        // 白色外圈
        pointerPixmap.setColor(1, 1, 1, 0.8f);
        pointerPixmap.drawCircle(pointerSize / 2, pointerSize / 2, pointerSize / 2 - 2);
        // 偏白内圈（原为绿色）
        pointerPixmap.setColor(0.88f, 0.94f, 1.0f, 0.6f);
        pointerPixmap.fillCircle(pointerSize / 2, pointerSize / 2, pointerSize / 4);
        // 中心点
        pointerPixmap.setColor(1, 1, 1, 1);
        pointerPixmap.fillCircle(pointerSize / 2, pointerSize / 2, 4);
        Texture texture = new Texture(pointerPixmap);
        // 圆环 / 圆点由 Pixmap 无 AA 画出（本身就是硬边）；纹理与绘制同为 64px，但触摸坐标是
        // 亚像素的 —— Nearest 下圆环边缘会随亚像素抖动出台阶，显式 Linear 平滑一档。
        texture.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        pointerPixmap.dispose();
        return texture;
    }

    public void render() {
        // dispose 过程中跳过渲染，防止访问已释放的资源导致 NPE
        if (disposing) return;

        // 记录帧开始时间（用于帧率限制）
        final long frameStart = System.nanoTime();
        // timer.update() 已移除：时间源改为实时计算，不再每帧缓存

        // ─── 等比视口计算 ───
        // 皮肤坐标决定游戏期望的宽高比；屏幕可能更宽（20:9 手机）或更高。
        // pillarbox/letterbox 黑边保证画面不拉伸。
        int gameW, gameH;
        if (current.getSkin() != null) {
            gameW = (int) current.getSkin().getWidth();
            gameH = (int) current.getSkin().getHeight();
        } else {
            gameW = config.getResolution().width;
            gameH = config.getResolution().height;
        }
        lastGameW = gameW;
        lastGameH = gameH;

        int screenW = Gdx.graphics.getWidth();
        int screenH = Gdx.graphics.getHeight();
        float targetAspect = (float) gameW / gameH;
        float screenAspect = (float) screenW / screenH;

        if (config != null && config.isStretchFullscreen()) {
            // 拉伸至全屏：跳过 pillarbox/letterbox 计算，画面铺满整个 surface
            viewportW = screenW;
            viewportH = screenH;
            viewportX = 0;
            viewportY = 0;
        } else if (screenAspect > targetAspect) {
            // 屏幕比游戏更宽 → pillarbox（左右黑边）
            viewportH = screenH;
            viewportW = Math.round(screenH * targetAspect);
            viewportX = (screenW - viewportW) / 2;
            viewportY = 0;
        } else {
            // 屏幕比游戏更高 → letterbox（上下黑边）
            viewportW = screenW;
            viewportH = Math.round(screenW / targetAspect);
            viewportX = 0;
            viewportY = (screenH - viewportH) / 2;
        }

        // 先清全屏（黑边区域用 glClearColor 填充）
        Gdx.gl.glViewport(0, 0, screenW, screenH);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // 设置视口 + 皮肤投影（抽出为方法：current.render() 之后还要再 apply 一次，见下）
        applyMainViewportAndProjection();

        current.render();

        // current.render() 内部会改写 GL 视口 / SpriteBatch 的投影矩阵：BGA 的 FBO 渲染、
        // LaneRenderer 首次初始化、Stage 的 Viewport.apply() 都会碰这两样，而它们是上面
        // 刚设好的。不复原的话，本帧的皮肤 / 音符 / 判定就会画到错误矩形里 ——
        // 实测症状就是"开启拉伸至全屏后进入练习模式，第一帧仍按 1920x1080 等比显示
        // （带黑边），下一帧才铺满"。与下方触摸指针处的恢复同理。
        applyMainViewportAndProjection();
        // [DEBUG PROBE] 皮肤渲染耗时监控 — 每帧触发，正常运行时禁用
        // long drawStart = System.nanoTime();
        sprite.begin();
        if (current.getSkin() != null) {
            current.getSkin().updateCustomObjects(current);
            current.getSkin().drawAllObjects(sprite, current);
            // 触摸版皮肤的练习参数面板要在皮肤之后叠加（该皮肤轨道背景不透明，画在 BGA 层会被整块盖住）。
            // 其他皮肤不受影响：内部会判定，非触摸皮肤直接返回，仍由 SkinBGA 在 BGA 层绘制。
            if (current instanceof BMSPlayer) {
                ((BMSPlayer) current).drawPracticeOverlay(current.getSkin().getObjectRenderer());
            }
        }
        sprite.end();
        // [DEBUG PROBE] >16ms 慢帧报警 — 正常运行时禁用
        // long drawEnd = System.nanoTime();
        // long drawUs = (drawEnd - drawStart) / 1000;
        // if (drawUs > 16_000) {
        //     bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.log("MainController:skin draw slow=" + drawUs + "us");
        // }

        final Stage stage = current.getStage();
        if (stage != null) {
            stage.getViewport().setScreenBounds(viewportX, viewportY, viewportW, viewportH);
            stage.getViewport().apply();
            stage.act(Math.min(Gdx.graphics.getDeltaTime(), 1 / 30f));
            stage.draw();
        }

        // ── FPS 显示 + 消息渲染合并为一个 begin/end 对（减少 DrawCall）──
        sprite.setProjectionMatrix(projMatrix.setToOrtho2D(0, 0, config.getResolution().width, config.getResolution().height));
        sprite.begin();
        if (showfps && systemfont != null) {
            systemfont.setColor(Color.CYAN);
            message.setLength(0);
            systemfont.draw(sprite, message.append("FPS ").append(Gdx.graphics.getFramesPerSecond()), debugTextXpos, config.getResolution().height - 2);
        }
        messageRenderer.render(current, sprite, 100, config.getResolution().height - 2);
        sprite.end();

        // 绘制触摸指针（仅 Android，PLAY 和 DECIDE 界面不显示，浮动菜单消费触摸时不显示）
        if (Gdx.app.getType() == Application.ApplicationType.Android && touchPointerTexture != null
            && !(current instanceof BMSPlayer)
            && !(current instanceof MusicDecide)
            && (floatingMenu == null || !floatingMenu.isConsumingTouch())) {
            int skinW = current.getSkin() != null ? (int) current.getSkin().getWidth() : config.getResolution().width;
            int skinH = current.getSkin() != null ? (int) current.getSkin().getHeight() : config.getResolution().height;

            // 关键：上方的 stage.draw() 会调用其 Viewport.apply() 改写 GL 视口（如 SearchTextField
            // 的 FitViewport）；若不恢复，触摸指针的 (gameX, gameY) 会画到错误的屏幕区域。
            // 恢复成 MainController 的等比/拉伸视口，投影也用 skin 坐标，保证指针落在指尖位置。
            Gdx.gl.glViewport(viewportX, viewportY, viewportW, viewportH);
            sprite.setProjectionMatrix(projMatrix.setToOrtho2D(0, 0, skinW, skinH));

            // 更新触摸活动时间
            boolean isTouched = Gdx.input.isTouched();
            final long nowMs = System.currentTimeMillis();
            if (isTouched) {
                lastTouchActivity = nowMs;
                wasTouching = true;
            }

            // 获取游戏坐标
            int gameX = input.getMouseX();
            int gameY = input.getMouseY();

            // 仅在触摸时或触摸后500ms内显示
            if (wasTouching && (isTouched || (nowMs - lastTouchActivity < 500))) {
                // 计算淡出透明度
                float alpha = 1.0f;
                if (!isTouched) {
                    alpha = 1.0f - (nowMs - lastTouchActivity) / 500.0f;
                    alpha = Math.max(0, alpha);
                }

                // 触摸指针 + 坐标文字合并为一个 begin/end 对
                sprite.begin();
                sprite.setColor(1, 1, 1, alpha);
                float size = 64;
                sprite.draw(touchPointerTexture, gameX - size/2, gameY - size/2, size, size);

                // 同时显示坐标文字（PLAY 和 DECIDE 界面不显示）
                if (systemfont18 != null && alpha > 0.2f
                    && !(current instanceof BMSPlayer)
                    && !(current instanceof MusicDecide)) {
                    systemfont18.setColor(1, 1, 1, alpha);
                    coordTextBuilder.setLength(0);
                    coordTextBuilder.append('(').append(gameX).append(", ").append(gameY).append(')');
                    systemfont18.draw(sprite, coordTextBuilder, gameX + 40, gameY - 10);
                }
                sprite.end();
            } else if (wasTouching) {
                wasTouching = false;
            }
        }

        // 在PLAY界面，检查PlayTouchKeyMapper是否正在消费触摸
        boolean playTouchKeyConsuming = false;
        if (Gdx.app.getType() == Application.ApplicationType.Android && current instanceof BMSPlayer) {
            bms.player.beatoraja.play.PlayTouchKeyMapper touchKeyMapper = ((BMSPlayer) current).getTouchKeyMapper();
            if (touchKeyMapper != null && touchKeyMapper.isConsumingTouch()) {
                playTouchKeyConsuming = true;
            }
        }

        // 绘制浮动快捷键菜单（在触摸指针之后，始终位于最顶层）
        if (floatingMenu != null) {
            floatingMenu.setViewport(viewportX, viewportY, viewportW, viewportH, lastGameW, lastGameH);
            floatingMenu.render(sprite, systemfont);
        }

        // Touch key rendering
         if (Gdx.app.getType() == Application.ApplicationType.Android && current instanceof BMSPlayer) {
             bms.player.beatoraja.play.PlayTouchKeyMapper touchKeyMapper = ((BMSPlayer) current).getTouchKeyMapper();
             if (touchKeyMapper != null && touchKeyMapper.isEnabled()) {
                 sprite.begin();
                 touchKeyMapper.render(sprite, systemfont);
                 sprite.end();
             }
         }

        if(download != null && download.isDownload()){
            downloadIpfsMessageRenderer(download.getMessage());
        }

        final long time = System.nanoTime() / 1_000_000;
        if(time > prevtime) {
            prevtime = time;

            // 根据当前界面设置手势模式
            if (input != null && input.getKeyBoardInputProcesseor() != null) {
                if (current instanceof MusicSelector) {
                    input.getKeyBoardInputProcesseor().setGestureMode(1); // select界面
                } else if (current instanceof MusicResult || current instanceof CourseResult) {
                    input.getKeyBoardInputProcesseor().setGestureMode(2); // result界面
                } else if (current instanceof MusicDecide) {
                    input.getKeyBoardInputProcesseor().setGestureMode(3); // decide界面
                } else {
                    input.getKeyBoardInputProcesseor().setGestureMode(0); // 默认模式
                }
            }

            current.input();

            // 当浮动菜单或PlayTouchKeyMapper正在消费触摸时，跳过皮肤鼠标事件处理
            boolean menuConsuming = (floatingMenu != null && floatingMenu.isConsumingTouch()) || playTouchKeyConsuming;

            if (input.isMousePressed() && !menuConsuming && current.getSkin() != null) {
                input.setMousePressed();
                current.getSkin().mousePressed(current, input.getMouseButton(), input.getMouseX(), input.getMouseY());
            }
            if (input.isMouseDragged() && !menuConsuming && current.getSkin() != null) {
                input.setMouseDragged();
                current.getSkin().mouseDragged(current, input.getMouseButton(), input.getMouseX(), input.getMouseY());
            }

            if(input.isMouseMoved()) {
                input.setMouseMoved(false);
                mouseMovedTime = time;
            }

            // ================= 这里是补齐的下半部分代码 =================

            // FPS表示切替
            if (input.isActivated(KeyCommand.SHOW_FPS)) {
                showfps = !showfps;
            }

            // screen shot
            if (input.isActivated(KeyCommand.SAVE_SCREENSHOT)) {
                if (screenshot == null || !screenshot.isAlive()) {
                    final byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),Gdx.graphics.getBackBufferHeight(), true);
                    screenshot = new Thread(() -> {
                        // 全ピクセルのアルファ値を255にする(=透明色を無くす)
                        for(int i = 3;i < pixels.length;i+=4) {
                            pixels[i] = (byte) 0xff;
                        }
                        new ScreenShotFileExporter().send(current, pixels);
                    });
                    screenshot.start();
                }
            }

            // 注意：POST_TWITTER 的逻辑已被彻底移除以兼容 Android

            if (download != null && download.getDownloadpath() != null) {
                this.updateSong(download.getDownloadpath());
                download.setDownloadpath(null);
            }
            if (updateSong != null && !updateSong.isAlive()) {
                // 扫描 / 難易度表更新线程已结束：**交接**刷新，不在 GL 线程直接 updateBar()。
                // （原先这里 + SongUpdateThread 自己的 postRunnable 会各跑一次全量重建 ⇒ 重复查库；
                //   交接后两者都只置同一个请求位，最终只重建一次。）
                handOffBarRefresh();
                updateSong = null;
            }
        }

        // -----------------------------------------------------------------------
        // 精确帧率控制（绝对时间对齐方式，消除累积漂移）
        //
        // 传统"每帧结束后等待剩余时间"方式会导致误差累积：
        //   假设每帧目标 8333µs，但实际渲染花了 8340µs，下一帧仍会等待完整 8333µs，
        //   最终导致实际帧率低于目标（或因 sleep 精度问题高于目标）。
        //
        // 绝对时间对齐：维护 nextFrameTimeNanos，每帧结束后等待至该绝对时刻，
        //   然后将其推进一个帧周期。这样误差不会跨帧累积。
        // -----------------------------------------------------------------------
        int maxFPS = config.getMaxFramePerSecond();

        // 判断是否需要执行帧率控制
        boolean doFrameLimit;
        if (isAndroid) {
            // Android：只要 maxFPS 有效且未开启无限帧率模式，就执行帧率控制
            // 注意：maxFPS = 1000 表示"自动检测"模式（Config默认值），实际使用时会被替换
            doFrameLimit = !config.isAndroidUnlimitedFPS() && maxFPS > 0 && maxFPS < 1000;
            // Android 11+：Surface.setFrameRate() + GLSurfaceView vsync 已接管时序，跳过应用层 sleep
            if (doFrameLimit && setFrameRateMethod != null) {
                doFrameLimit = false;
            }
        } else {
            // 非 Android：VSync 关闭且 maxFPS 有效时执行
            doFrameLimit = !config.isVsync() && maxFPS > 0;
        }

        if (doFrameLimit) {
            final long frameIntervalNanos = 1_000_000_000L / maxFPS;
            final long now = System.nanoTime();

            // 初始化：首次运行从下一个帧周期开始
            // 落后重锚：卡顿超过 3 个周期后，直接对齐到【现在】而不是"现在 + 一个周期"。
            //   旧写法会额外白等一个空周期，把一次卡顿放大成"卡顿 + 一帧空白"；
            //   对齐到现在则允许立即补帧（catch-up）。落后不超过 3 个周期时保持落后，
            //   由后面的 remaining <= 0 自然跳过睡眠、逐帧追平。
            if (nextFrameTimeNanos == 0) {
                nextFrameTimeNanos = now + frameIntervalNanos;
            } else {
                nextFrameTimeNanos += frameIntervalNanos;
                if (now - nextFrameTimeNanos > frameIntervalNanos * 3) {
                    nextFrameTimeNanos = now;
                }
            }

            // ─── VSync 相位纠正（只往回拉 + 限量）───
            // 旧实现是"就地取整到 vsync 网格"：一次最多 ±半个周期的硬跳变
            //   （60Hz = ±8.3ms，120Hz = ±4.2ms），比它想消除的漂移大两个数量级；
            //   而且用的 lastVsyncTimeNanos 由 UI 线程 Choreographer 回调写、GL 线程只读，
            //   4×A7 上 UI 线程被抢占时该时间戳会滞后好几帧。
            // 现在：① 时间戳超过 2 个帧周期就不采信；② 即使采信，也只做"往回拉 250µs"的
            //   限速纠正。漂移量级 10~20µs/帧 ⇒ 每帧都被完全抵消，且任何坏输入都无法把
            //   提交时刻推晚。
            if (isAndroid && lastVsyncTimeNanos != 0
                    && now - lastVsyncRecordNanos <= frameIntervalNanos * 2) {
                final long diff = nextFrameTimeNanos - lastVsyncTimeNanos;
                final long snapped = lastVsyncTimeNanos
                        + Math.round((double) diff / frameIntervalNanos) * frameIntervalNanos;
                final long late = nextFrameTimeNanos - snapped;
                if (late > 0) {
                    nextFrameTimeNanos -= Math.min(late, PHASE_FIX_MAX_NANOS);
                }
            }

            // 实际等待目标 = 计划时刻 − 提交前置量（提前醒来，把最后一段交给 eglSwapBuffers）
            final long waitTarget = nextFrameTimeNanos
                    - Math.min(SUBMIT_LEAD_MAX_NANOS, frameIntervalNanos / 4);

            final long remaining = waitTarget - System.nanoTime();

            // 第一阶段：较长等待用 sleep 节省 CPU（给后两阶段留 2ms 缓冲）
            if (remaining > 3_000_000L) {
                try {
                    Thread.sleep((remaining - 2_000_000L) / 1_000_000L);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }

            // 第二阶段：parkNanos 微秒级睡眠，步长随剩余量收缩（1ms → 500µs → 200µs）。
            // 真正让出 CPU 的同时，把最后一次 park 的过冲幅度压到最小。
            long left = waitTarget - System.nanoTime();
            while (left > BUSY_WAIT_TAIL_NANOS) {
                final long slice = left > 4_000_000L ? 1_000_000L
                        : (left > 1_000_000L ? 500_000L : PARK_SLICE_MIN_NANOS);
                LockSupport.parkNanos(slice);
                left = waitTarget - System.nanoTime();
            }

            // 第三阶段：末尾忙等，保证唤醒时刻确定且绝不晚于目标。
            // Android 上也执行（旧实现这里被 !isAndroid 跳过 → 过冲全额转嫁到提交时刻）。
            while (System.nanoTime() < waitTarget) {
                // busy-wait for precision
            }
        }
    }

    public void dispose() {
        // 🔴 幂等守卫（必须）：本方法有两条调用路径，且会先后发生两次 ——
        //   ① 退出对话框 → MainController.exit() → dispose()（UI 线程，同步释放）
        //   ② Activity finish() → SurfaceView 销毁 → libGDX AndroidGraphics.onDrawFrame
        //      的 ldestroy 分支 → BeatorajaGame.dispose() → dispose()（GL 线程）
        // 第二次会在 GL 线程对**已被释放的 FreeType face** 再调一次 FT_Done_Face：
        //   signal 11 (SIGSEGV), fault addr 0x78, libgdx-freetype.so (FT_Done_Face+36)
        // 这正是 /data/tombstones 里从 09-26 起反复出现、且线程总为 GLThread 的崩溃。
        // 字段 disposed 而非 disposing：后者在 exit() 里已置 true，不能复用。
        if (disposed) return;
        disposed = true;

        // 关键修复：先停掉 1000Hz 输入轮询线程,再释放 input,避免 input.poll() 在 input 被释放后
        // 继续调用引发 NPE / 死循环。pollingRunning=false 让循环在下一次 check 时退出;
        // interrupt() 是兜底,若线程卡在 parkNanos 则强制唤醒。
        pollingRunning = false;
        if (inputPollingThread != null) {
            inputPollingThread.interrupt();
            try {
                inputPollingThread.join(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            inputPollingThread = null;
        }

        saveConfig();

        if (bmsplayer != null) {
            bmsplayer.dispose();
        }
        if (selector != null) {
            selector.dispose();
        }
        if (decide != null) {
            decide.dispose();
        }
        if (result != null) {
            result.dispose();
        }
        if (gresult != null) {
            gresult.dispose();
        }
        if (keyconfig != null) {
            keyconfig.dispose();
        }
        if (skinconfig != null) {
            skinconfig.dispose();
        }
        resource.dispose();
        SkinLoader.getResource().dispose();
        ShaderManager.dispose();
        FileCache.clear();
        if (download != null) {
            download.dispose();
        }
        if (systemfont != null) {
            systemfont.dispose();
            systemfont = null;
        }
        if (systemfont18 != null) {
            systemfont18.dispose();
            systemfont18 = null;
        }
        if (systemfontGenerator != null) {
            // FreeTypeFontGenerator.dispose() → FT_Done_Face(face)。
            // 释放后必须置 null：本类是 GameLifecycle 的持有者，残留引用一旦被
            // 第二次 dispose（或 resume()）取用就是野指针（fault addr 0x78）。
            systemfontGenerator.dispose();
            systemfontGenerator = null;
        }
        if (touchPointerTexture != null) {
            touchPointerTexture.dispose();
        }
        if (floatingMenu != null) {
            floatingMenu.dispose();
        }

        Logger.getGlobal().info("全リソース破棄完了");
    }

    public void pause() { current.pause(); }
    public void resize(int width, int height) { current.resize(width, height); }

    public void resume() {
        // ---------------------------------------------------------------------------
        // Android OpenGL 上下文重建后，所有 GPU 纹理均已失效，必须重新上传。
        if (isAndroid) {
            Gdx.app.log("MainController", "resume(): rebuilding font textures after GL context restore");

            // 恢复时重新请求高刷新率
            updateFrameRateAPI(detectedRefreshRate);

            // 步骤1：清除所有 SkinTextFont 的 generator 缓存
            bms.player.beatoraja.skin.SkinTextFont.invalidateGeneratorCache();
            // 步骤1b：清除 BitmapFontCache（.fnt 字体的 GPU 纹理在 GL 上下文重建后也会失效，
            // 否则 .fnt 渲染会引用已销毁的 TextureRegion）
            bms.player.beatoraja.skin.BitmapFontCache.invalidate();

            // 步骤2：重建 systemfont / systemfont18
            if (systemfontFileHandle != null) {
                try {
                    // dispose 旧的失效字体和生成器
                    if (systemfontGenerator != null) {
                        try { systemfontGenerator.dispose(); } catch (Throwable ignore) {}
                        systemfontGenerator = null;
                    }
                    if (systemfont != null) {
                        try { systemfont.dispose(); } catch (Throwable ignore) {}
                        systemfont = null;
                    }
                    if (systemfont18 != null) {
                        try { systemfont18.dispose(); } catch (Throwable ignore) {}
                        systemfont18 = null;
                    }
                    // 重新创建（与 create() 共用同一处，含高 DPI 光栅化与 base scale）
                    generateSystemFonts();
                    Gdx.app.log("MainController", "resume(): systemfont rebuilt successfully");
                } catch (Throwable e) {
                    Gdx.app.error("MainController", "resume(): failed to rebuild systemfont", e);
                }
            }

            // 步骤3：重建触摸指针纹理
            if (touchPointerTexture != null) {
                try { touchPointerTexture.dispose(); } catch (Throwable ignore) {}
            }
            touchPointerTexture = createTouchPointerTexture();
            Gdx.app.log("MainController", "resume(): touch pointer texture rebuilt");

            // 重建浮动菜单纹理
            if (floatingMenu != null) {
                floatingMenu.rebuildTextures();
                Gdx.app.log("MainController", "resume(): floating menu textures rebuilt");
            }
        }

        current.resume();
    }

    public void saveConfig(){
        Config.write(config);
        PlayerConfig.write(config.getPlayerpath(), player);
        Logger.getGlobal().info("設定情報を保存");
    }

    public void exit() {
        disposing = true;
        dispose();
    }

    public BMSPlayerInputProcessor getInputProcessor() { return input; }
    public AudioDriver getAudioProcessor() { return audio; }
    public IRStatus[] getIRStatus() { return ir; }
    public SystemSoundManager getSoundManager() { return sound; }
    public MusicDownloadProcessor getMusicDownloadProcessor(){ return download; }
    public MessageRenderer getMessageRenderer() { return messageRenderer; }
    public FloatingMenu getFloatingMenu() { return floatingMenu; }

    /** 浮動菜单的 In-Game Spectrum 调整页是否打开（用于在其他界面也预览频谱） */
    public boolean isSpectrumAdjustOpen() {
        return floatingMenu != null && floatingMenu.isSpectrumAdjustOpen();
    }

    public void updateMainStateListener(int status) {
        for(MainStateListener listener : stateListener) {
            listener.update(current, status);
        }
    }

    public void addMainStateListener(MainStateListener listener) {
        if (listener != null && !stateListener.contains(listener, true)) {
            stateListener.add(listener);
        }
    }

    public void removeMainStateListener(MainStateListener listener) {
        stateListener.removeValue(listener, true);
    }

    public long getPlayTime() { return System.currentTimeMillis() - boottime; }
    public Calendar getCurrnetTime() {
        cl.setTimeInMillis(System.currentTimeMillis());
        return cl;
    }

    public TimerManager getTimer() { return timer; }
    public long getStartTime() { return timer.getStartTime(); }
    public long getStartMicroTime() { return timer.getStartMicroTime(); }
    public long getNowTime() { return timer.getNowTime(); }
    public long getNowTime(int id) { return timer.getNowTime(id); }
    public long getNowMicroTime() { return timer.getNowMicroTime(); }
    public long getNowMicroTime(int id) { return timer.getNowMicroTime(id); }
    public long getTimer(int id) { return getMicroTimer(id) / 1000; }
    public long getMicroTimer(int id) { return timer.getMicroTimer(id); }
    public boolean isTimerOn(int id) { return getMicroTimer(id) != Long.MIN_VALUE; }
    public void setTimerOn(int id) { timer.setTimerOn(id); }
    public void setTimerOff(int id) { setMicroTimer(id, Long.MIN_VALUE); }
    public void setMicroTimer(int id, long microtime) { timer.setMicroTimer(id, microtime); }
    public void switchTimer(int id, boolean on) { timer.switchTimer(id, on); }

    private UpdateThread updateSong;

    public void updateSong(String path) {
        if (updateSong == null || !updateSong.isAlive()) {
            updateSong = new SongUpdateThread(path);
            updateSong.start();
        } else {
            Logger.getGlobal().warning("楽曲更新中のため、更新要求は取り消されました");
        }
    }

    /**
     * 把「曲库扫描 / 難易度表更新线程已结束」这件事交接给选曲界面。
     *
     * <p>🔴 这里只做两件<b>纯内存</b>的事，<b>不</b>直接调 {@code BarManager.updateBar()}：
     * 后者是同步 DB IO（folder 表 + song 表 + 每个 SongBar 的 score 表），大文件夹下
     * 数百 ms ~ 数秒，放在 GL 线程 = 掉帧 / ANR。真正的重建由
     * {@code MusicSelector.render()} 在下一帧消费 {@code consumeRefreshRequest()} 时执行，
     * 且**只在选曲界面真的在渲染时**才发生（用户不在选曲界面 ⇒ 请求挂着，下次进来再刷）。</p>
     *
     * <p>① {@code invalidateDirectoryCaches()} 是<b>必须</b>的：扫描期间 GL 线程可能按需
     * {@code getChildren()} 撞进 folder 表的重建窗口，把 {@code FolderBar} 钉成
     * {@code LOADED_EMPTY}（永久返回空 ⇒「文件夹消失」，此前只能重启恢复）。
     * 它只改 bar 自己的状态字段、不查库，所以放在 GL 线程安全。</p>
     *
     * <p>② 用字段 {@link #selector} 而不是 {@code current}：即使此刻不在选曲界面
     * （例如扫描是在 PLAY / 设置页期间跑完的），也要把请求挂上，等回到选曲界面再重建。</p>
     */
    private void handOffBarRefresh() {
        final MusicSelector sel = selector;
        if (sel == null || sel.getBarManager() == null) {
            return;
        }
        sel.getBarManager().invalidateDirectoryCaches();
        sel.getBarManager().requestRefresh();
    }

    /**
     * 成绩 / 回放的写入线程完成后，投递「这曲的数据变了」通知。
     *
     * <p>🔴 这是<b>任意线程</b>都能调的（实际调用者就是
     * {@code MusicResult.ScoreWriteThread} 与 {@code ReplayWriteThread}）。
     * 这里只碰线程安全的队列，<b>绝不碰</b> {@code ScoreDataCache} —— 后者内部的
     * {@code ObjectMap} 没有同步，只能在 GL 线程访问。</p>
     *
     * <p>用户可能此刻不在选曲界面（还在看结算画面 / 已退回设置页）：通知会一直挂在队列里，
     * 等下次 {@code MusicSelector.render()} 跑起来才被消费。因为存的是 sha256 而不是
     * 「一次性事件」，重复投递也只会导致一次多余的重读，是幂等的。</p>
     *
     * @param sha256 刚写完成绩 / 回放的楽曲 sha256。null 会被忽略
     */
    public void requestScoreRefresh(String sha256) {
        // 空串也要挡掉：BMSModel.getSHA256() 未设置时返回 ""，进队列只会白刷一次
        if (sha256 != null && !sha256.isEmpty()) {
            pendingScoreRefresh.add(sha256);
        }
    }

    /**
     * 取走并清空待刷新的 sha256 列表（<b>只能在 GL 线程调用</b>）。
     *
     * @return 去重后的 sha256 数组；没有待处理项时返回 {@code null}
     *         （这是每帧都会走的正常路径，所以先 {@code isEmpty()} 挡一下，
     *         避免热路径上白白 new 一堆对象）
     */
    public String[] consumeScoreRefresh() {
        if (pendingScoreRefresh.isEmpty()) {
            return null;
        }
        final Array<String> out = new Array<>();
        String sha;
        while ((sha = pendingScoreRefresh.poll()) != null) {
            // 队列允许重复（同一曲写成绩 + 写回放各投一次），这里按 equals 去重
            if (!out.contains(sha, false)) {
                out.add(sha);
            }
        }
        return out.size == 0 ? null : out.toArray(String.class);
    }

    public void updateTable(TableBar reader) {
        if (updateSong == null || !updateSong.isAlive()) {
            updateSong = new TableUpdateThread(reader);
            updateSong.start();
        } else {
            Logger.getGlobal().warning("楽曲更新中のため、更新要求は取り消されました");
        }
    }

    private UpdateThread downloadIpfs;

    public void downloadIpfsMessageRenderer(String message) {
        if (downloadIpfs == null || !downloadIpfs.isAlive()) {
            downloadIpfs = new DownloadMessageThread(message);
            downloadIpfs.start();
        }
    }

    public static String getVersion() { return VERSION; }

    abstract class UpdateThread extends Thread {
        protected String messageStr;
        public UpdateThread(String message) { this.messageStr = message; }
    }

    class SongUpdateThread extends UpdateThread {
        private final String path;
        public SongUpdateThread(String path) {
            super("updating folder : " + (path == null ? "ALL" : path));
            this.path = path;
        }
        public void run() {
            long threadStartTime = System.currentTimeMillis();
            Logger.getGlobal().info("================================================================================");
            Logger.getGlobal().info("[SongUpdateThread] Starting async scan task");
            Logger.getGlobal().info("[SongUpdateThread] Update path: " + (path == null ? "ALL (bmsroot)" : path));
            Logger.getGlobal().info("[SongUpdateThread] bmsroot: " + java.util.Arrays.toString(config.getBmsroot()));
            Logger.getGlobal().info("================================================================================");

            Message messageObj = messageRenderer.addMessage(this.messageStr, Color.CYAN, 1);

            try {
                // 在扫描前检测并解压 songs 目录下的 zip 文件（Android only）
                try {
                    Class<?> clazz = Class.forName("com.starxh.beatoraja.android.AndroidLauncher");
                    clazz.getMethod("checkAndExtractSongZips").invoke(null);
                } catch (Exception e) {
                    // Non-Android platform or reflection failed, ignore
                }

                // 创建进度回调，通过 postRunnable 更新 Message（确保在 GL 线程更新）
                bms.player.beatoraja.song.SongDatabaseAccessor.SongScanProgress progress =
                    new bms.player.beatoraja.song.SongDatabaseAccessor.SongScanProgress() {
                        @Override
                        public void onFileScanned(int scanned, int total) {
                            Gdx.app.postRunnable(() -> {
                                messageObj.setProgress(scanned, total);
                            });
                        }
                    };

                // 执行扫描 - 这是阻塞调用，会等待扫描完成
                getSongDatabase().updateSongDatas(path, config.getBmsroot(), false, progress);

                long elapsed = System.currentTimeMillis() - threadStartTime;
                Logger.getGlobal().info("================================================================================");
                Logger.getGlobal().info("[SongUpdateThread] Scan task COMPLETED in " + elapsed + "ms");
                Logger.getGlobal().info("[SongUpdateThread] Now triggering UI refresh...");
                Logger.getGlobal().info("================================================================================");

                // 扫描完成后：GL 线程只做「交接」，不在这里同步重建列表。
                //
                // 🔴 这里**不能**直接调 BarManager.updateBar()：
                //   ① 它是同步 DB IO（folder 表 + song 表 + 每个 SongBar 的 score 表），
                //      大文件夹下数百 ms ~ 数秒 —— 放在 GL 线程的 runnable 里一样卡帧/触发 ANR；
                //   ② 扫描期间 GL 线程可能已经按需 getChildren() 撞进 folder 表的重建窗口，
                //      把 FolderBar 钉成 LOADED_EMPTY（永久返回 EMPTY）⇒「文件夹消失」，
                //      而且本会话内没有恢复路径（clearChildrenCache() 此前全仓库零调用）。
                // 所以 postRunnable 里只做两件**纯内存**的事：
                //   ① invalidateDirectoryCaches() —— 解冻被缓存的目录 bar，让被钉死的能自愈；
                //   ② requestRefresh()            —— 交接「下一帧重建」，由 MusicSelector.render() 消费。
                // 顺带的好处：用户若已不在选曲界面（或列表无需刷新），这次重建根本不会发生。
                Gdx.app.postRunnable(() -> {
                    Logger.getGlobal().info("[SongUpdateThread] Handing bar refresh off to MusicSelector (GL thread)");
                    handOffBarRefresh();
                });
            } catch (Exception e) {
                Logger.getGlobal().severe("[SongUpdateThread] FATAL ERROR during scan: " + e.getMessage());
                e.printStackTrace();
            } finally {
                messageObj.stop();
            }
        }
    }

    class TableUpdateThread extends UpdateThread {
        private final TableBar accessor;
        public TableUpdateThread(TableBar bar) {
            super("updating table : " + bar.getAccessor().name);
            accessor = bar;
        }
        public void run() {
            Message messageObj = messageRenderer.addMessage(this.messageStr, Color.CYAN, 1);
            TableData td = accessor.getAccessor().read();
            if (td != null) {
                accessor.getAccessor().write(td);
                accessor.setTableData(td);
            }
            messageObj.stop();
        }
    }

    class DownloadMessageThread extends UpdateThread {
        public DownloadMessageThread(String message) { super(message); }
        public void run() {
            Message messageObj = messageRenderer.addMessage(this.messageStr, Color.LIME, 1);
            while (download != null && download.isDownload() && download.getMessage() != null) {
                messageObj.setText(download.getMessage());
                try { sleep(100); } catch (InterruptedException e) { e.printStackTrace(); }
            }
            messageObj.stop();
        }
    }

    public static class IRStatus {
        public final IRConfig config;
        public final IRConnection connection;
        public final IRPlayerData player;

        public IRStatus(IRConfig config, IRConnection connection, IRPlayerData player) {
            this.config = config;
            this.connection = connection;
            this.player = player;
        }
    }
}
