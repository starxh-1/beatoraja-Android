package bms.player.beatoraja;

import java.io.File;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

import com.badlogic.gdx.*;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.*;
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

    private static final String VERSION = "beatoraja 0.8.8 (Android)";

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

    private AudioDriver audio;
    private PlayerResource resource;
    private BitmapFont systemfont;
    private BitmapFont systemfont18;
    /** systemfont 用的 FreeTypeFontGenerator，保留引用用于 Android resume() 时重建字体纹理 */
    private FreeTypeFontGenerator systemfontGenerator;
    /** systemfont 对应的字体文件 FileHandle，resume 时重新创建 generator 用 */
    private FileHandle systemfontFileHandle;
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
    private MusicDownloadProcessor download;
    /** MusicSelector是否已初始化过（避免每次切回都重新create导致歌曲扫描） */
    private boolean selectorInitialized = false;

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

        // Android RESULT界面触摸转ESCAPE - 覆盖其他处理器
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
            Gdx.input.setInputProcessor(escapeMapper);
        } else {
            // 浮动菜单：在 PLAY / DECIDE / MUSICPLAYER 状态隐藏（除非 config 允许在 PLAY 显示）
            if (floatingMenu != null) {
                boolean menuVisible = state != MainStateType.PLAY && state != MainStateType.DECIDE && state != MainStateType.MUSICPLAYER;
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
                floatingMenu.setPlayMode(state == MainStateType.PLAY);
            }
            // 将 FloatingMenu 作为最高优先级处理器加入 InputMultiplexer
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

            // 保留 generator 和 fontFile 引用，供 Android resume() 时重建字体纹理使用。
            // 不在此处调用 generator.dispose()，等 MainController.dispose() 时统一释放。
            systemfontFileHandle = fontFile;
            systemfontGenerator = new FreeTypeFontGenerator(fontFile);
            FreeTypeFontParameter parameter = new FreeTypeFontParameter();
            parameter.size = 24;
            parameter.incremental = true; // 启用增量渲染，支持全量 CJK 字符
            parameter.characters = buildJapaneseCharacters();
            systemfont = systemfontGenerator.generateFont(parameter);
            // Pre-generate 18pt version for LaneRenderer and PracticeConfiguration
            parameter.size = 18;
            systemfont18 = systemfontGenerator.generateFont(parameter);
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
        skinconfig = new SkinConfiguration(this, player);
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
            int pointerSize = 64;
            Pixmap pointerPixmap = new Pixmap(pointerSize, pointerSize, Pixmap.Format.RGBA8888);
            // 透明背景
            pointerPixmap.setColor(0, 0, 0, 0);
            pointerPixmap.fill();
            // 白色外圈
            pointerPixmap.setColor(1, 1, 1, 0.8f);
            pointerPixmap.drawCircle(pointerSize / 2, pointerSize / 2, pointerSize / 2 - 2);
            // 绿色内圈
            pointerPixmap.setColor(0, 1, 0, 0.6f);
            pointerPixmap.fillCircle(pointerSize / 2, pointerSize / 2, pointerSize / 4);
            // 中心点
            pointerPixmap.setColor(1, 1, 1, 1);
            pointerPixmap.fillCircle(pointerSize / 2, pointerSize / 2, 4);
            touchPointerTexture = new Texture(pointerPixmap);
            pointerPixmap.dispose();
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

    /** 记录来自 Android Choreographer 的最新 VSync 时间戳（纳秒） */
    private static volatile long lastVsyncTimeNanos = 0;
    /** 供 AndroidLauncher 调用，同步 VSync 相位 */
    public static void setLastVsyncTimeNanos(long time) { lastVsyncTimeNanos = time; }

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

        // 设置等比视口
        Gdx.gl.glViewport(viewportX, viewportY, viewportW, viewportH);

        if (current.getSkin() != null) {
            sprite.setProjectionMatrix(projMatrix.setToOrtho2D(0, 0, current.getSkin().getWidth(), current.getSkin().getHeight()));
        } else {
            sprite.setProjectionMatrix(projMatrix.setToOrtho2D(0, 0, config.getResolution().width, config.getResolution().height));
        }

        current.render();
        // [DEBUG PROBE] 皮肤渲染耗时监控 — 每帧触发，正常运行时禁用
        // long drawStart = System.nanoTime();
        sprite.begin();
        if (current.getSkin() != null) {
            current.getSkin().updateCustomObjects(current);
            current.getSkin().drawAllObjects(sprite, current);
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
                selector.getBarManager().updateBar();
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

            // 初始化或重置：若 nextFrameTimeNanos 距现在已超过 3 个帧周期，
            // 说明是首次运行或长时间卡顿后恢复，重新对齐到当前时间
            if (nextFrameTimeNanos == 0 || now - nextFrameTimeNanos > frameIntervalNanos * 3) {
                nextFrameTimeNanos = now + frameIntervalNanos;
            } else {
                nextFrameTimeNanos += frameIntervalNanos;
            }

            // ─── Android VSync 相位对齐 ───
            // 将 nextFrameTimeNanos 对齐到来自 Choreographer 的 VSync 信号相位。
            // 这样应用提交帧的节奏与 SurfaceFlinger 合成节奏保持锁定，消除周期性微卡顿。
            if (isAndroid && lastVsyncTimeNanos != 0) {
                long diff = nextFrameTimeNanos - lastVsyncTimeNanos;
                long intervals = Math.round((double) diff / frameIntervalNanos);
                nextFrameTimeNanos = lastVsyncTimeNanos + intervals * frameIntervalNanos;
            }

            long remaining = nextFrameTimeNanos - System.nanoTime();

            // 第一阶段：较长等待用 sleep 节省 CPU（保留 1ms 缓冲给后两阶段）
            if (remaining > 2_000_000) {
                try {
                    Thread.sleep((remaining - 1_000_000) / 1_000_000);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }

            // 第二阶段：剩余 1ms 内用 parkNanos 微秒级睡眠，真正让出 CPU
            while (nextFrameTimeNanos - System.nanoTime() > 200_000) {
                LockSupport.parkNanos(200_000);
            }

            // 第三阶段：最后 200µs 用忙等保证精度（Android 跳过）
            if (!isAndroid) {
                while (System.nanoTime() < nextFrameTimeNanos) {
                    // busy-wait for precision
                }
            }
        }
    }

    public void dispose() {
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
        }
        if (systemfont18 != null) {
            systemfont18.dispose();
        }
        if (systemfontGenerator != null) {
            systemfontGenerator.dispose();
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
                    // 重新创建
                    systemfontGenerator = new FreeTypeFontGenerator(systemfontFileHandle);
                    FreeTypeFontParameter parameter = new FreeTypeFontParameter();
                    parameter.size = 24;
                    parameter.incremental = true; // 启用增量渲染
                    parameter.characters = buildJapaneseCharacters();
                    systemfont = systemfontGenerator.generateFont(parameter);
                    parameter.size = 18;
                    systemfont18 = systemfontGenerator.generateFont(parameter);
                    Gdx.app.log("MainController", "resume(): systemfont rebuilt successfully");
                } catch (Throwable e) {
                    Gdx.app.error("MainController", "resume(): failed to rebuild systemfont", e);
                }
            }

            // 步骤3：重建触摸指针纹理
            if (touchPointerTexture != null) {
                try { touchPointerTexture.dispose(); } catch (Throwable ignore) {}
            }
            int pointerSize = 64;
            com.badlogic.gdx.graphics.Pixmap pointerPixmap = new com.badlogic.gdx.graphics.Pixmap(pointerSize, pointerSize, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pointerPixmap.setColor(0, 0, 0, 0);
            pointerPixmap.fill();
            pointerPixmap.setColor(1, 1, 1, 0.8f);
            pointerPixmap.drawCircle(pointerSize / 2, pointerSize / 2, pointerSize / 2 - 2);
            pointerPixmap.setColor(0, 1, 0, 0.6f);
            pointerPixmap.fillCircle(pointerSize / 2, pointerSize / 2, pointerSize / 4);
            pointerPixmap.setColor(1, 1, 1, 1);
            pointerPixmap.fillCircle(pointerSize / 2, pointerSize / 2, 4);
            touchPointerTexture = new com.badlogic.gdx.graphics.Texture(pointerPixmap);
            pointerPixmap.dispose();
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

                // 扫描完成后，在 GL 线程中刷新 UI
                Gdx.app.postRunnable(() -> {
                    Logger.getGlobal().info("[SongUpdateThread] Executing UI refresh on GL thread");
                    if (getSongDatabase() != null) {
                        // 通知选曲界面更新 - 使用 current 而不是 main
                        if (current instanceof MusicSelector) {
                            MusicSelector selector = (MusicSelector) current;
                            if (selector.getBarManager() != null) {
                                selector.getBarManager().updateBar();
                                Logger.getGlobal().info("[SongUpdateThread] BarManager.updateBar() called");
                            }
                        }
                    }
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
