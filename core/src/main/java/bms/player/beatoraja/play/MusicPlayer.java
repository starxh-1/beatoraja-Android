package bms.player.beatoraja.play;

import java.io.File;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import bms.model.BMSModel;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PixmapResourcePool;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.audio.PCM;
import bms.player.beatoraja.select.BarManager;
import bms.player.beatoraja.select.bar.Bar;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;
import com.starxh.beatoraja.AudioSpectrumManager;
import com.starxh.beatoraja.AudioSpectrumProvider;
import com.starxh.beatoraja.PlaybackCpuLockManager;

/**
 * 选曲界面中的 Music Player 状态 —— 跑当前选中歌曲的 BMS autoplay,只播放 BG 音轨,
 * 不渲染 BGA / 判定画面 / lane 视觉,改画歌曲列表 + 频谱 + 进度条 + 播放控制按钮。
 *
 * 复用 {@link MainState} 生命周期;BG 音轨调度逻辑仿
 * {@link KeySoundProcessor.AutoplayThread} 在内嵌 BGAutoplayThread 里复刻,隔离。
 */
public class MusicPlayer extends MainState {

	// 自己的歌曲列表（显示所有文件夹的歌曲）
	private SongData[] allSongs;
	// 跨线程读写的字段 —— 后台 worker (切歌) 写, render() / BGAutoplayThread 读,
	// 加 volatile 保证可见性。
	private volatile int selectedIndex = 0;
	private volatile SongData currentSong;
	private volatile BMSModel currentModel;
	private volatile BGAutoplayThread bgThread;
	/**
	 * 播放基准时间({@link System#nanoTime()})。
	 * 这是进度条和切歌判定的唯一时间源。BGAutoplayThread 检测到"时间跳跃"(后台被系统
	 * 限制 CPU 或进入 Doze 后唤醒) 时会前移该基准,避免一次性补播积压的上千个音符
	 * —— 那会把 soundpool 打满、Oboe 回调堆积,表现为爆音 / 声音断掉 / 线程卡死。
	 */
	private volatile long playBaseNanos = 0;
	private volatile long totalDurationMs;
	private volatile Texture stagefile;
	// dispose 之后 transition 不要再起新线程 —— 防止 dispose 和 transition 竞争导致孤儿线程
	private volatile boolean disposed = false;

	/**
	 * Activity 进入后台(锁屏 / 切走)时为 true。
	 *
	 * <p>此时屏幕什么都看不见,但绘制、字形布局、封面解码照样吃 CPU 并制造垃圾,
	 * 而一次 stop-the-world GC 会把 BGAutoplayThread 一起暂停 —— 音符整批迟到。
	 * 锁屏期间 MusicPlayer 只需要干一件事:把音符按时送进 AudioDriver。
	 * 所以这里把所有 GL 相关工作和封面解码全部关掉,只留播放。</p>
	 */
	private volatile boolean backgrounded = false;
	/**
	 * 串行执行所有"重活"的单线程 executor:加载 BMSModel、换 AudioDriver 音频模型、启停播放线程。
	 *
	 * 存在的原因:AudioDriver 只有 setModel() 是 synchronized,而播放线程走的 play0() 完全
	 * 不加锁,wavmap / slicesound 也不是 volatile。后台切歌线程直接调 setModel() 会在播放
	 * 线程读 wavmap 的中途换掉数组并释放旧 PCM(Oboe 是 native 对象),轻则数组越界丢音,
	 * 重则 native use-after-free 直接崩进程。这里把所有结构性操作收敛到单线程,
	 * 并在切换前后与播放线程做交接(先停播放线程,再换模型),彻底消除该竞态。
	 */
	/** worker 由 loader 线程(startAdvanceWatcher)创建、GL 线程(scheduleStagefileDecode)读取 */
	private volatile ExecutorService worker;

	/**
	 * 专职加载线程 —— 和 worker 分开,因为两者会互相拖累。
	 *
	 * <p>worker 上跑的是"曲尾自动切歌"的监视循环,轻量但必须及时;加载是重活
	 * (解析 BMS + 解码几百个音源 + 扫 tail 时读几千个音频头 + 写 SQLite),
	 * 一次可能几秒。挤在同一条线程上,监视循环会被加载堵住导致曲尾接不上,
	 * 加载反过来也会被 200ms 一次的检查搅碎。</p>
	 */
	private volatile ExecutorService loader;
	/** 加载请求序号。只有序号等于当前值的 job 才真正执行,被后来者取代的直接空转返回。 */
	private final AtomicLong loadSeq = new AtomicLong();
	/** 与 loadSeq 配套的互斥锁:保证同一时刻只有一个加载 / 切歌在进行。 */
	private final Object loadLock = new Object();
	/** 是否正在加载。 */
	private volatile boolean loading = false;
	/** 自动切歌监视任务是否已在 worker 上跑起来 —— 每首歌 submit 一次会累加出多个循环。 */
	private volatile boolean advanceWatcherStarted = false;

	/**
	 * 1x1 纯白纹理 —— 所有纯色矩形都靠它 + batch.setColor() 染出来。
	 *
	 * 不能做成 static:Android 切后台会销毁并重建 GL 上下文,static 字段里的 Texture
	 * 句柄会变成失效引用,而 static 又不会随实例重来 —— 回到前台再画它就是野句柄
	 * (GL error / 渲染错乱 / 上下文丢失时直接崩)。改成实例字段后由 resume()/dispose()
	 * 统一释放重建。
	 */
	private Texture whiteTexture;
	private BitmapFont font;
	/**
	 * font 是否是本类自己 new 出来的。来自 main.getSystemFont18() 的字体由 MainController
	 * 统一回收,本类 dispose() 绝不能碰 —— resume() 之后 MainController 换了新的实例,
	 * 旧的已经被 dispose 了,再 dispose 一次就是 double free。
	 */
	private boolean fontOwned = false;
	private int skinW;
	private int skinH;

	// 控制按钮布局(屏幕底部)
	private static final float BTN_SIZE = 96f;
	private static final float BTN_MARGIN_BOTTOM = 48f;
	private static final float BTN_GAP = 32f;

	// 频谱显示区域 (基于原始比例换算到当前分辨率)
	private static final float SPEC_W_RATIO = 0.28125f; // 540/1920 - 保持原始比例
	private static final float SPEC_H_RATIO = 0.185f;   // 200/1080 - 高度比例
	private static final float SPEC_Y_OFFSET = 0.80f;   // Y 放到上方，向下 5%
	private static final int SPEC_BANDS = 32;
	// 运行时计算的频谱坐标
	private float specX, specY, specW, specH;

	// 歌曲列表区域
	private static final int LIST_VISIBLE = 10; // 上下各 4 条,中间 1 条
	private static final float LIST_LINE_H = 56f;
	private static final float LIST_TOP_Y = 0f; // 从屏幕顶部 0 起(屏幕坐标)
	private static final float LIST_LEFT_X = 36f;
	private static final float LIST_RIGHT_X = 336f; // 频谱从 X=360 开始,留 24px 间距
	private static final int LIST_TAP_THRESHOLD = 20; // 像素:低于此值视为 tap,否则视为 drag

	// 列表触摸状态
	private boolean listDragging = false;
	private int listDragStartY = 0;
	private float listDragOffset = 0f;
	private int listTouchedBarIndex = -1;

	// 后台切换歌曲时的并发守卫,防止 AutoAdvanceThread 重复进入
	private volatile boolean isTransitioning = false;

	/**
	 * 等待 GL 线程释放的旧纹理。
	 *
	 * <p>Texture 只能在 GL 线程 dispose,而"该换封面了"这件事发生在 loader/worker 线程。
	 * 之前这里是一个 {@code volatile Texture stagefileToDispose} 单槽位,有两个问题:
	 * 一是只能挂一张,连着切两首时前一张会被覆盖掉,直接泄漏;二是<b>只有自动切歌
	 * 那条路径会往里放</b>,手动 PREV/NEXT 走的 loadSingle() 反而把旧封面原样留在
	 * {@code stagefile} 上 —— 于是新歌的封面解出来之前,屏幕上一直挂着上一首的图,
	 * 新歌要是压根没有封面,那张图就永远留在那儿了。</p>
	 *
	 * <p>改成无锁队列 + 统一的 {@link #retireStagefile()} 之后,任何切歌路径都只是
	 * 把当前封面摘下来投递,GL 线程每帧 drain 一次,既不丢也不滞留。</p>
	 */
	private final java.util.concurrent.ConcurrentLinkedQueue<Texture> glTextureGarbage =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

	/**
	 * 保护 {@link #stagefile} 的"摘除"与"替换"这两个动作。
	 *
	 * <p>这两件事来自不同线程,而且会真正并发:切歌时 loader 线程摘封面,
	 * GL 线程同时可能正在把刚解码完的 pixmap 换成 Texture。不加保护的话会出现
	 * "同一个 Texture 被两边各释放一次"——第二次删的是一个已经无效、
	 * 甚至可能已被 GL 复用给别的纹理的 ID,轻则画面错乱重则崩。
	 * 加锁后两个动作各自是原子的,谁先谁后都不会重放同一张。</p>
	 */
	private final Object stagefileLock = new Object();

	/**
	 * 把当前封面从 {@link #stagefile} 摘下来、投递给 GL 线程回收。任意线程可调。
	 *
	 * <p>摘除是同步的:调用方一返回,{@code stagefile} 就一定是 null 了,
	 * 不会出现"切歌了但屏幕还在画旧封面"的中间态。</p>
	 */
	private void retireStagefile() {
		Texture old;
		synchronized (stagefileLock) {
			old = stagefile;
			stagefile = null;
		}
		if (old != null) {
			glTextureGarbage.add(old);
		}
	}

	/** GL 线程上释放所有已投递的旧纹理。每帧调一次。 */
	private void drainRetiredTextures() {
		Texture t;
		while ((t = glTextureGarbage.poll()) != null) {
			t.dispose();
		}
	}

	/**
	 * 后台线程已解码、等 GL 线程上传成纹理的舞台图。
	 *
	 * <p>为什么要有这一层:解码一张封面(读文件 + 解压)是纯 CPU 活,但以前它被放在
	 * {@code render()} 里同步做 —— 切歌后 stagefile 被置 null,下一帧就在主线上解码。
	 * 一张几百 KB 的 PNG 展开成 Pixmap 常常要上百毫秒,期间主线程卡住(Choreographer
	 * "Skipped N frames"),更糟的是随之而来的大 GC 是 stop-the-world,会把
	 * BGAutoplayThread 一起暂停 —— 音符整批迟到,日志上就是
	 * "BGAutoplay starved for Nms"。主线程掉帧和音符 starve 两个数字几乎相等,
	 * 就是这个原因。</p>
	 *
	 * <p>现在解码挪到 worker 线程,render() 只做{@code new Texture()}上传。</p>
	 */
	private volatile Pixmap pendingStagefilePixmap = null;

	/**
	 * {@link #pendingStagefilePixmap} 里那张封面属于哪首曲子(记 {@code currentSong.getPath()})。
	 *
	 * <p>为什么必须记:解码是异步的,而"解码"和"上传"发生在两个不同的时刻。快速连续切歌时,
	 * 歌曲 A 的封面可能在这时刚解完,而 {@code currentSong} 已经变成 B 了。上传端如果只看
	 * "有没有 pixmap",就会把 A 的封面当成 B 的画上去 —— 封面张冠李戴,而且要等下一次
	 * 切歌才纠正。更坏的情况是新歌根本没有封面,{@code decodeStagefile()} 直接 return,
	 * 那张 {@code pendingStagefilePixmap} 会永远留着,于是一路错下去。</p>
	 *
	 * <p>上传前后各校验一次:解码端解完发现已切歌就丢弃结果,上传端发现 key 与
	 * {@code currentSong} 不符就丢弃并清掉 {@link #stagefileDecodeKey},让下一帧重新请求。</p>
	 */
	private volatile String pendingStagefileKey = null;

	/** 舞台图上传前的最大边长。显示区只有 480x320,没必要把 2000px 的原图传进显存。 */
	private static final int MAX_STAGEFILE_PX = 640;

	/**
	 * 已经为哪首曲子请求过封面解码。
	 *
	 * 没有封面的曲子,decodeStagefile() 会直接返回,stagefile 就永远是 null。
	 * 如果 render() 用 "stagefile == null 就重试" 来兜底,那首曲子会变成
	 * 每帧一次 findImagePath()(要对目录做列举 + 大小写不敏感匹配),
	 * 60fps 下就是每秒 60 次文件系统操作 —— 白烧 CPU 还制造垃圾。
	 * 所以每首只请求一次,失败就认了。
	 */
	private volatile String stagefileDecodeKey = null;

	// 播放模式 (顺序 / 随机 / 单曲循环)
	private enum PlayMode { SEQUENCE, RANDOM, LOOP_ONE }
	private volatile PlayMode playMode = PlayMode.SEQUENCE;

	// 频谱渲染
	private ShapeRenderer shapeRenderer;
	private final float[] specBands = new float[SPEC_BANDS];
	private final float[] specTopValues = new float[SPEC_BANDS];
	private static final float SPEC_FALL_SPEED = 0.02f;

	// 舞台图(放在屏幕正中央,4:3 横向)
	private static final float STAGEFILE_W = 480f;
	private static final float STAGEFILE_H = 320f;

	public MusicPlayer(MainController main) {
		super(main);
	}

	@Override
	public void create() {
		// 使用 Config 的分辨率（4:3 模式下会返回实际屏幕分辨率）
		this.skinW = resource.getConfig().getResolution().width;
		this.skinH = resource.getConfig().getResolution().height;

		// 确保 audio 状态干净:闪退后 dispose() 可能未执行,
		// 残留 note 会导致新 setModel 与旧音频状态竞争。
		AudioDriver audioDriver = main.getAudioProcessor();
		if (audioDriver != null) {
			audioDriver.stop((Note) null);
		}

		// 复位上一次离开 MusicPlayer 时 shutdown()/dispose() 设过的退出标志。
		// 不复位的话,本次进入后 worker 监视任务会立刻退出、自动切歌也永远被
		// disposed 守卫拦下 —— 表现为"能播当前曲但永远不自动切歌"。
		this.disposed = false;
		this.isTransitioning = false;
		// 状态实例可能被复用;不复位的话上次锁屏留下的标志会让进来就黑屏。
		this.backgrounded = false;

	// !!! 从这里往下的重活全部搬到 loader 线程,千万别搬回来 !!!
	//
	// 原因:下面这一段单首可能耗时几百毫秒~数秒 —— 读全曲库、解析 BMS、
	// 对几百个音源文件做解码(setModel)、扫 tail 时读几千个音频头、写 SQLite。
	// 这些放在 GL 线程上会直接卡住 Choreographer(logcat 里就是
	// "Skipped 211 frames!")。此前它们藏在 create() 和 loadAndPlaySelected() 里,
	// 而这两个方法都在 GL 线程上被调用,所以每次进 MusicPlayer、每次手动
	// PREV/NEXT 都会卡这么一下。
	//
	// 写下来的原因:之前两轮修复都只处理了"解码要挪走"这一件事,没处理
	// "谁来付这笔钱" —— 结果 BMS 解析和 setModel 仍留在主线程,卡顿一点没少。

		// 字体
		this.font = main.getSystemFont18();
		if (this.font == null) {
			this.font = new BitmapFont();
			this.fontOwned = true;
		}
		this.font.setColor(Color.WHITE);

		// 启动 ShapeRenderer
		if (this.shapeRenderer == null) {
			this.shapeRenderer = new ShapeRenderer();
		}

		// 计算频谱显示区域坐标
		specW = skinW * SPEC_W_RATIO;
		specH = skinH * SPEC_H_RATIO;
		specX = (skinW - specW) / 2;  // X 居中
		specY = (int)(skinH * SPEC_Y_OFFSET);  // Y 放到上方

		// 启动后开启持续渲染(选曲界面默认是关的)
		Gdx.graphics.setContinuousRendering(true);

		// 锁屏后保持 CPU 运行。没有它,屏幕一灭系统就把 CPU 拉进低功耗并把普通线程的
		// 定时器 slack 放大,BGAutoplayThread 的唤醒精度会崩 —— 音符晚点(不同步)或
		// 被 Starve 保护整批丢弃(停顿)。libGDX 自带的 useWakelock 是 FULL_WAKE_LOCK
		// 且在 Activity.onPause() 就被 release,帮不上忙。
		PlaybackCpuLockManager.acquire(this, "MusicPlayer");

		// 真正把这首曲子跑起来:在 loader 线程上完成全部重活,期间 render() 画载入画面。
		requestLoad(selectedIndex, true);
	}

	// ------------------------------------------------------------------
	// 加载流水线 —— 所有重活都在这个方法里,且只跑在 loader 单线程上
	// ------------------------------------------------------------------

	/** 取得(必要时创建)loader。所有结构化操作串行在这一个线程上。 */
	private ExecutorService ensureLoader() {
		ExecutorService ld = loader;
		if (ld == null || ld.isShutdown()) {
			ld = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "MusicPlayer-Loader");
				t.setDaemon(true);
				try {
					t.setPriority(Thread.NORM_PRIORITY + 2);
				} catch (Throwable ignored) {
				}
				return t;
			});
			loader = ld;
		}
		return ld;
	}

	/**
	 * 提交一次加载请求。同一时刻只允许最后一次请求生效 —— 连续点 PREV/NEXT 时,
	 * 中间那些已经被取代的 job 会在 performLoad() 开头直接返回,不做无谓解码。
	 */
	private void requestLoad(int index, boolean initial) {
		final long mySeq = loadSeq.incrementAndGet();
		loading = true;
		// 加载期间挡住自动切歌监视任务:此刻 playBaseNanos / totalDurationMs 还是
		// 上一首的,不挡的话它会每 200ms 重复触发一次切歌。
		isTransitioning = true;
		// 立刻退出"正在播放"的显示态。进度条的时间源就是 playBaseNanos,不清零的话
		// 加载期间它会继续拿上一首的基准往前走 —— 而列表高亮和歌名在 loadSingle()
		// 一开头就换成新歌了,于是画面变成"新歌 + 上一首还在涨的进度条"。
		// 清零之后进度条归零、时间显示 0:00 / 0:00,再由 render() 补一个 Loading 提示。
		playBaseNanos = 0;
		totalDurationMs = 0;
		final ExecutorService ld = ensureLoader();
		try {
			ld.submit(() -> performLoad(index, initial, mySeq));
		} catch (Throwable t) {
			loading = false;
			isTransitioning = false;
		}
	}

	/**
	 * 完整的单曲加载:停旧的 → 解析 BMS → 换音频模型 → 算时长 → 解封面 → 起播放线程。
	 *
	 * <p>只跑在 loader 单线程上,并用 {@link #loadLock} 与自动切歌互斥。
	 * 两者都会调用 {@code setModel()},并发执行的话,一个线程刚把 wavmap 换成新模型的、
	 * 另一个可能正拿着旧引用在播 —— 而 setModel() 会 disposeOld() 释放旧 PCM
	 * (Oboe 是 native 对象),那就是 use-after-free。必须串行。</p>
	 */
	private void performLoad(int index, boolean initial, long mySeq) {
		try {
			if (disposed || mySeq != loadSeq.get()) return;
			synchronized (loadLock) {
				if (disposed || mySeq != loadSeq.get()) return;
				loadSingle(index, initial);
			}
		} catch (Throwable t) {
			if (Gdx.app != null) {
				Gdx.app.error("MusicPlayer", "load failed", t);
			}
		} finally {
			loading = false;
			// 被后来者取代的 job 不要清标志 —— 赢的那个会清。
			if (mySeq == loadSeq.get()) {
				isTransitioning = false;
			}
		}
	}

	/**
	 * 让自动切歌监视任务闭嘴。
	 *
	 * 监视任务的判据是 {@code total > 0},所以置 0 就够 —— 不必用 Long.MAX_VALUE:
	 * 那样进度条的时间文字会拿 formatTime(Long.MAX_VALUE) 去格式化,显示成一串天文数字。
	 * 加载失败、时长还没定出来时都走这里。
	 */
	private void silenceAdvanceWatcher() {
		totalDurationMs = 0;
	}

	private void loadSingle(int index, boolean initial) {
		final long startNanos = System.nanoTime();

		if (initial) {
			// 获取所有歌曲（不再依赖 MusicSelector 的 BarManager）
			this.allSongs = main.getSongDatabase().getSongDatas();
			if (allSongs == null || allSongs.length == 0) {
				silenceAdvanceWatcher();
				Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
				return;
			}
			// 如果 MusicSelector 当前选中的是 SongBar，保持同步
			Bar selectorBar = main.getMusicSelector().getBarManager().getSelected();
			if (selectorBar instanceof SongBar) {
				SongData selectorSong = ((SongBar) selectorBar).getSongData();
				for (int i = 0; i < allSongs.length; i++) {
					if (allSongs[i].getSha256().equals(selectorSong.getSha256())) {
						index = i;
						break;
					}
				}
			}
			selectedIndex = index;
		}

		if (allSongs == null || index < 0 || index >= allSongs.length) return;
		SongData next = allSongs[index];
		if (next == null) return;
		selectedIndex = index;
		this.currentSong = next;
		// 换歌了,旧封面必须立刻下屏:光把 currentSong 换掉是不够的,
		// stagefile 还指着上一首那张图,而 Texture 只能交给 GL 线程回收。
		retireStagefile();

		// 先解析谱面,再停旧歌。loadBMSModel() 只读文件、完全不碰音频驱动,
		// 所以旧歌可以一直播到这一步 —— 放到 stopBgThread() 后面的话,这几百毫秒
		// 就是纯静音,听感上就是"切歌时莫名其妙断一下"。
		this.currentModel = resource.loadBMSModel(
				Gdx.files.absolute(next.getPath()),
				resource.getPlayerConfig().getLnmode());
		if (this.currentModel == null) {
			silenceAdvanceWatcher();
			Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
			return;
		}
		if (disposed) return;

		// 到这里必须停:setModel() 会整体替换 wavmap 并 disposeOld() 释放旧 PCM
		// (Oboe 是 native 对象),播放线程还活着的话就是 use-after-free。
		stopBgThread();
		stopAllNotes();

		// 把模型的 WAV 列表灌进 AudioDriver,否则 audio.play(note) 会数组越界
		main.getAudioProcessor().setModel(currentModel);
		resource.setPlayMode(BMSPlayerMode.AUTOPLAY);

		// 上面 stopBgThread() 掐掉了音符调度,下面 startBgThread() 才重新开始 ——
		// 这中间就是用户听到的"切歌空白"。所以窗口里每多一件活,空白就长一截。
		// tail 计算(要读几千个音频头 + 写一次 SQLite)和封面解码都不影响发声,
		// 全部挪到 startBgThread() 之后去做。
		final int lastEventTime = currentModel.getLastTime();
		final int lastNoteTime = currentModel.getLastNoteTime();
		int tail = currentSong.getTail();

		// 起播放线程。要在 setModel() 之后 —— 播放线程读的 wavmap 必须是新模型灌好的那份。
		this.playBaseNanos = System.nanoTime();
		startBgThread(currentModel);

		// 立刻给一个时长,让进度条从 0 开始正常走。tail 的精确值要读几千个音频头才算得出来,
		// 不能为了等它让进度条停在 0 —— 那看起来像卡住了。这个值偏小无害:它只表示
		// "至少有这么久",精确值算完会在方法末尾覆盖它。
		// 注意:这里把 totalDurationMs 从 0 变成非 0,理论上就给了监视任务触发条件,
		// 但 requestLoad() 设的 isTransitioning 要到 performLoad() 的 finally 才清,
		// 覆盖了整个 loadSingle(),所以监视任务碰不到这里。
		this.totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + Math.max(tail, 0));

		// ---- 以下都是不发声的收尾,不再占用静音窗口 ----

		// 封面:趁还在 loader 线程上解码,别留给 render() 在 GL 线程解
		stagefileDecodeKey = currentSong.getPath();
		decodeStagefile();

		if (tail <= 0) {
			try {
				tail = calculateMaxTailMs(currentModel, lastNoteTime);
				currentSong.setTail(tail);
				main.getSongDatabase().updateSongTail(currentModel.getSHA256(), tail);
			} catch (Throwable e) {
				// 算不出来就退化成 tail = 0,下面的公式会给出 lastEventTime + 1000。
				// 重点是这条路径也必须走到下面那次赋值 —— 不能让 totalDurationMs 停在
				// 上面那个估算值上,否则曲尾判定会偏早,整首歌没播完就被切走。
				if (Gdx.app != null) Gdx.app.error("MusicPlayer", "calculateMaxTailMs failed", e);
				tail = 0;
			}
		}
		this.totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + Math.max(tail, 0));

		if (Gdx.app != null) {
			Gdx.app.log("MusicPlayer", "loaded \"" + currentSong.getTitle() + "\" in "
					+ ((System.nanoTime() - startNanos) / 1000000L) + "ms, totalDurationMs=" + totalDurationMs
					+ ", tail=" + tail + ", lastNoteTime=" + lastNoteTime + ", lastEventTime=" + lastEventTime);
		}

		startAdvanceWatcher();
	}

	/**
	 * 解码舞台图 —— 读文件 + 解压,纯 CPU,不碰 GL,可在任意线程调用。
	 * 结果放进 {@link #pendingStagefilePixmap},由 GL 线程的
	 * {@link #applyPendingStagefile()} 上传。
	 */
	private void decodeStagefile() {
		// 后台不解码:屏幕看不见,解码出来的垃圾还会招来 stop-the-world GC。
		// 回到前台后由 resume() 重新调度。
		if (backgrounded) return;
		// 整段用同一个快照。currentSong 是 volatile,切歌线程随时会换掉它;
		// 逐次去读可能拼出"用 A 的目录 + B 的封面名"这种半新半旧的组合。
		final SongData song = currentSong;
		if (song == null) return;
		final String ownerPath = song.getPath();
		if (ownerPath == null) return;
		String path = song.getStagefile();
		if (path == null || path.isEmpty()) {
			path = song.getBanner();
		}
		if (path == null || path.isEmpty()) return;
		File bmsFile = new File(ownerPath);
		File coverFile = new File(bmsFile.getParentFile(), path);
		String resolved = PixmapResourcePool.findImagePath(coverFile.getAbsolutePath());
		if (resolved == null) return;
		Pixmap pm = PixmapResourcePool.loadPicture(resolved);
		if (pm == null) return;
		Pixmap scaled = downscaleIfNeeded(pm);

		// 解码是几百毫秒的活,期间很可能又切歌了(连续按 NEXT 尤其明显)。
		// 这份结果已经属于上一首,再上传上去就是封面张冠李戴 —— 就地丢弃。
		// 不做这个检查的话,丢掉的还只是这一张;真正致命的是下面的
		// "新歌没封面 → 这张永久残留"路径,见 pendingStagefileKey 的注释。
		if (disposed || !isStillCurrent(ownerPath)) {
			scaled.dispose();
			return;
		}
		// 上一张还没来得及上传就被下一首顶掉了 —— 直接丢,别让它占着 native 内存
		Pixmap stale = pendingStagefilePixmap;
		pendingStagefilePixmap = scaled;
		pendingStagefileKey = ownerPath;
		if (stale != null && stale != scaled) {
			stale.dispose();
		}
	}

	/** 这份封面记录是否仍对应 {@link #currentSong}。跨线程读 volatile,只做一次快照比较。 */
	private boolean isStillCurrent(String songPath) {
		SongData song = currentSong;
		if (song == null) return false;
		String now = song.getPath();
		return now != null && now.equals(songPath);
	}

	/** 边长超过 {@link #MAX_STAGEFILE_PX} 就等比缩小;不需要缩放时原样返回。 */
	private static Pixmap downscaleIfNeeded(Pixmap src) {
		int w = src.getWidth();
		int h = src.getHeight();
		if (w <= MAX_STAGEFILE_PX && h <= MAX_STAGEFILE_PX) return src;
		float scale = Math.min((float) MAX_STAGEFILE_PX / w, (float) MAX_STAGEFILE_PX / h);
		int nw = Math.max(1, (int) (w * scale));
		int nh = Math.max(1, (int) (h * scale));
		Pixmap dst = null;
		try {
			dst = new Pixmap(nw, nh, src.getFormat());
			dst.setFilter(Pixmap.Filter.BiLinear);
			dst.drawPixmap(src, 0, 0, w, h, 0, 0, nw, nh);
		} catch (Throwable t) {
			if (dst != null) dst.dispose();
			return src; // 缩放失败就用原图,总比什么都不显示强
		}
		src.dispose();
		return dst;
	}

	/**
	 * 把 {@link #pendingStagefilePixmap} 上传成纹理。只能在 GL 线程调用。
	 * 这是 render() 里唯一剩下的舞台图工作,耗时从"解码整张图"降到"一次纹理上传"。
	 */
	private void applyPendingStagefile() {
		Pixmap pm = pendingStagefilePixmap;
		if (pm == null) return;
		final String key = pendingStagefileKey;
		pendingStagefilePixmap = null;
		pendingStagefileKey = null;

		// 归属校验:解码完成和这里上传之间隔了至少一帧,期间完全可能又切过歌。
		// 不匹配就地丢弃。同时必须把 stagefileDecodeKey 一起清掉 —— 不然
		// ensureStagefileRequested() 会认为"当前这首已经请求过了",永远不再请求,
		// 那一首从此再也没有封面(正是"显示不正确"里最难复原的那种)。
		if (key == null || !isStillCurrent(key)) {
			pm.dispose();
			stagefileDecodeKey = null;
			return;
		}

		Texture fresh = null;
		try {
			// 上传本身放在锁外:它可能耗时(几十毫秒),没必要把 retireStagefile() 挡在门外。
			fresh = new Texture(pm);
		} catch (Throwable t) {
			if (Gdx.app != null) Gdx.app.error("MusicPlayer", "Failed to upload stagefile", t);
		} finally {
			// 像素已经进显存,Pixmap 的 native 内存可以放了。
			// 老实现从未 dispose 它,切几次歌就攒下好几张原图的 native 内存。
			pm.dispose();
		}
		if (fresh == null) return;

		Texture old;
		synchronized (stagefileLock) {
			old = stagefile;
			stagefile = fresh;
		}
		// 旧的一律走同一个回收队列 —— 保证一张纹理只有一个释放点。
		// 直接在这里 dispose 的话,和 retireStagefile() 并发时会重复释放同一张
		// (第二次删的可能是已被 GL 复用给别的纹理的 ID)。队列延迟一帧释放,无所谓。
		if (old != null && old != fresh) {
			glTextureGarbage.add(old);
		}
	}

	/**
	 * 当前曲子还没请求过封面解码的话就请求一次。
	 *
	 * 绝不能在 GL 线程上同步解码 —— 见 {@link #stagefileDecodeKey} 的注释说明
	 * 为什么要按曲目去重。
	 */
	private void ensureStagefileRequested() {
		if (currentSong == null) return;
		String key = currentSong.getPath();
		if (key == null) return;
		if (key.equals(stagefileDecodeKey)) return;
		// 已经有结果(或已在解码队列里)了,别重复调度
		if (stagefile != null || pendingStagefilePixmap != null) {
			stagefileDecodeKey = key;
			return;
		}
		stagefileDecodeKey = key;
		scheduleStagefileDecode();
	}

	/** 把封面解码丢给 worker,GL 线程不碰解码这件事。 */
	private void scheduleStagefileDecode() {
		ExecutorService w = ensureWorker();
		if (w == null || w.isShutdown()) return;
		try {
			w.submit(() -> {
				if (disposed) return;
				decodeStagefile();
			});
		} catch (Throwable ignored) {
			// executor 已关(正在退出),不解码就是了
		}
	}

	// ------------------------------------------------------------------
	// 线程 / GL 资源管理
	// ------------------------------------------------------------------

	/**
	 * 启动 BG 自动播放线程。必须在 {@code setModel()} 之后调用 —— 播放线程读的
	 * wavmap 必须是新模型灌好的那份。
	 */
	private void startBgThread(BMSModel model) {
		BGAutoplayThread t = new BGAutoplayThread(model, main, playBaseNanos);
		this.bgThread = t;
		t.start();
	}

	/**
	 * 停掉 BG 自动播放线程并等它真正退出。
	 *
	 * 这一步是修复的核心之一:AudioDriver.setModel() 会整体换掉 wavmap 并 disposeOld()
	 * 释放旧 PCM。Oboe 的 PCM 是 native 对象,如果播放线程正好拿着旧引用在播,
	 * 释放后就是 use-after-free —— 随机 SIGSEGV,切回前台时最容易撞上。
	 * 所以换模型前必须先把播放线程 join 掉,再 audio.stop(null) 停掉混音器里还在
	 * 排队的 sample,最后才 setModel()。
	 */
	private void stopBgThread() {
		BGAutoplayThread t = bgThread;
		bgThread = null;
		if (t == null) return;
		t.stop = true;
		t.interrupt();
		try {
			t.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 停掉所有在飞的音符。必须在 setModel() 之前调用,理由同 {@link #stopBgThread()}。
	 */
	private void stopAllNotes() {
		if (main == null) return;
		AudioDriver audio = main.getAudioProcessor();
		if (audio != null) {
			audio.stop((Note) null);
		}
	}

	/**
	 * 在 worker 单线程上启动常驻的"自动切歌"监视任务。
	 *
	 * 为什么是常驻任务而不是每首歌 new 一个 Thread:
	 *  - 每首歌 new Thread 时,线程创建/销毁的窗口里会和 GL 线程的手动切歌抢同一把锁,
	 *    旧实现还经常漏掉 stop 旧线程;
	 *  - 常驻任务只有一个线程,和手动切歌共用 {@code synchronized(this)},天然串行。
	 */
	/** 取得(必要时创建)worker。自动切歌监视和封面解码都跑在这一个线程上。 */
	private ExecutorService ensureWorker() {
		ExecutorService w = worker;
		if (w == null || w.isShutdown()) {
			w = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "MusicPlayer-Worker");
				t.setDaemon(true);
				// 略高于默认:它负责切歌时的 BMSModel 加载和 setModel(CPU 密集),
				// 锁屏后如果被饿到,曲尾会明显空一拍才接上下一首。
				// 不给 MAX —— 解码是重活,抢太狠反而拖慢音符调度线程。
				try {
					t.setPriority(Thread.NORM_PRIORITY + 2);
				} catch (Throwable ignored) {
				}
				return t;
			});
			worker = w;
		}
		return w;
	}

	private void startAdvanceWatcher() {
		if (disposed) return;
		// 每首歌都 submit 会累加出多个监视循环 —— 它们会各自触发切歌,
		// 表现就是曲尾连续跳好几首。整个实例只要起一次。
		if (advanceWatcherStarted) return;
		ExecutorService w = ensureWorker();
		if (w.isShutdown()) return;
		advanceWatcherStarted = true;
		w.submit(() -> {
			while (!disposed && !Thread.currentThread().isInterrupted()) {
				try {
					long total = totalDurationMs;
					if (total > 0 && !isTransitioning && getCurrentPlaybackMs() >= total) {
						transitionToNextInBackground();
					}
					Thread.sleep(200);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (Throwable e) {
					// 兜底:任何异常都不能让监视线程静默死掉 ——
					// 那样自动切歌会永久失效,表现就是"声音播完就断了"
					if (Gdx.app != null) {
						Gdx.app.error("MusicPlayer", "advance watcher error", e);
					}
				}
			}
		});
	}

	/** 见 {@link #whiteTexture} 字段注释:必须是实例级,GL 上下文重建后要重来 */
	private Texture getWhiteTexture() {
		if (whiteTexture == null) {
			try {
				Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
				pm.setColor(1f, 1f, 1f, 1f);
				pm.fill();
				whiteTexture = new Texture(pm);
				pm.dispose();
			} catch (Throwable e) {
				if (Gdx.app != null) {
					Gdx.app.error("MusicPlayer", "failed to create white texture", e);
				}
				return null;
			}
		}
		return whiteTexture;
	}

	/**
	 * 释放本类持有的 GL 资源。在 resume() 里也要调一次 —— 切后台时 GL 上下文会被销毁
	 * 重建,旧 Texture 句柄全部失效,必须丢掉重来。
	 */
	/**
	 * 释放本类持有的 GL 资源。
	 *
	 * @param contextAlive true = GL 上下文仍然有效,可以真正 dispose(暂停、退出时);
	 *                     false = 上下文已经重建过,旧句柄全是野指针 —— 只能丢弃引用,
	 *                     拿去 dispose 反而可能误伤新上下文里被复用的纹理 ID
	 */
	private void disposeGlResources(boolean contextAlive) {
		if (whiteTexture != null) {
			if (contextAlive) whiteTexture.dispose();
			whiteTexture = null;
		}
		if (stagefile != null) {
			if (contextAlive) stagefile.dispose();
			stagefile = null;
		}
		// 队列里排着的旧纹理。上下文还在就真释放;已经重建过的话句柄是野指针,
		// 只丢引用(此时那批纹理早随旧上下文一起没了)。
		Texture retired;
		while ((retired = glTextureGarbage.poll()) != null) {
			if (contextAlive) retired.dispose();
		}
		// Pixmap 是纯 CPU 内存,与 GL 上下文无关,什么时候都能放
		if (pendingStagefilePixmap != null) {
			pendingStagefilePixmap.dispose();
			pendingStagefilePixmap = null;
		}
		pendingStagefileKey = null;
	}

	private void disposeGlResources() {
		disposeGlResources(true);
	}

	@Override
	public void render() {
		// 后台(锁屏 / 切走)什么都不画:屏幕看不见,但列表字形布局、6 次 batch
		// begin/end、频谱解析照样吃 CPU 并制造垃圾。省下的 CPU 和避免的 GC 都直接
		// 让给音符调度线程 —— 播放才是锁屏时唯一要做的事。
		if (backgrounded) return;
		SpriteBatch batch = main.getSpriteBatch();
		if (batch == null) return;

		// 切歌时后台线程把旧封面投递进队列了,GL 线程上统一释放。
		drainRetiredTextures();
		// 后台已经解码好的,这里只做上传(很快)。没有待上传的才自己解码 ——
		// 正常路径下切歌时 worker 已经解好了,走到这里说明是刚进状态/从后台恢复。
		// applyPendingStagefile() 内部会校验这张封面是否还属于当前歌曲:属于上一首的
		// 会被丢掉,并清掉去重 key 让下一帧重新请求。
		applyPendingStagefile();
		ensureStagefileRequested();

		// 1. 背景:深色
		drawBackground(batch);
		// 2. 歌曲列表(顶部)
		drawSongList(batch);
		// 3. 舞台图(中央)
		drawStagefile(batch);
		// 3.5 加载提示。切歌时音频要重新解码(几百毫秒到几秒),这期间封面已经摘掉、
		//     进度条也归零了 —— 不说明一下会像是卡住。
		drawLoadingIndicator(batch);
		// 4. 频谱(中部)
		drawSpectrum(batch);
		// 5. 进度条 + 时间文字
		drawProgressBar(batch);
		// 6. 播放控制按钮(底部)
		drawControlButtons(batch);
	}

	private void drawBackground(SpriteBatch batch) {
		Texture tex = getWhiteTexture();
		if (tex == null) return;
		batch.begin();
		batch.setColor(0f, 0f, 0f, 1f); // 纯黑背景
		batch.draw(tex, 0, 0, skinW, skinH);
		batch.end();
	}

	private void drawSongList(SpriteBatch batch) {
		if (font == null) return;
		// 本帧取一次快照,后面所有下标都以它为准 —— allSongs 是 loader 线程赋的字段,
		// 循环里反复读字段等于让长度和下标来自两个时刻。
		final SongData[] songs = allSongs;
		if (songs == null || songs.length == 0) return;
		final int count = songs.length;
		// 曲库不足一屏时只画 count 行,并把选中行摆在这一段的正中 —— 选曲列表是个
		// 首尾相接的环,照 LIST_VISIBLE 硬画 10 行会让同一首歌在屏幕上重复出现两三次,
		// 既难辨认也点不准。
		final int visible = Math.min(LIST_VISIBLE, count);
		final int half = visible / 2;
		Texture blank = getWhiteTexture();

		// 第一行的基线 y(libGDX 坐标,自下而上)
		float baseX = LIST_LEFT_X;
		float topRowBaselineY = skinH - LIST_TOP_Y - LIST_LINE_H * 0.5f;

		batch.begin();
		for (int row = 0; row < visible; row++) {
			// 🔴 必须 floorMod,不能写 (selectedIndex + row - half + count) % count:
			// "+count" 只兜得住 -count < x < 0,而 half 是写死的 5,曲库不足 10 首时
			// x 会小到 -5;count=4 时 -5+4 = -1,Java 的 % 对负数返回负数
			// ⇒ allSongs[-1] ⇒ ArrayIndexOutOfBoundsException(length=4; index=-1)。
			// floorMod 对任意 int 都返回 [0,count),顺带兜住并发下 selectedIndex 越界。
			int idx = Math.floorMod(selectedIndex + row - half, count);
			SongData song = songs[idx];
			String title = song == null ? "" : (song.getFullTitle() == null ? song.getTitle() : song.getFullTitle());

			// 随拖拽偏移整体平移:手指下滑(gdx_y 减小)→ listDragOffset > 0 → 行向下移
			float y = topRowBaselineY - row * LIST_LINE_H - listDragOffset;

			if (row == half) {
				// 当前曲目:高亮背景
				if (blank != null) {
					batch.setColor(0.20f, 0.30f, 0.55f, 0.9f);
					batch.draw(blank,
							LIST_LEFT_X - 12f,
							y - LIST_LINE_H * 0.5f + 4f,
							LIST_RIGHT_X - LIST_LEFT_X + 24f,
							LIST_LINE_H - 8f);
				}
				font.setColor(1f, 0.95f, 0.55f, 1f);
			} else {
				float fade = 1f - Math.abs(row - half) * 0.12f;
				if (fade < 0.3f) fade = 0.3f;
				font.setColor(fade, fade, fade, 1f);
			}
			font.draw(batch, title, baseX, y);
		}
		batch.end();
	}

	private void drawStagefile(SpriteBatch batch) {
		// 一次快照,后面只用这个局部引用 —— 判空之后再读一次字段是崩溃源。
		//
		// stagefile 会被 loader 线程(loadSingle)和 worker 线程(自动切歌)通过
		// retireStagefile() 置空,与本方法并发。而 batch.begin() 恰好夹在"判空"和
		// "传给 draw"这两次字段读之间(几十微秒的 JNI/GL 调用),那个 null 完全可能
		// 落进 batch.draw() —— 而 SpriteBatch.draw(null, ...) 不会当场抛异常:
		// 5 参数重载只做 "if (texture != lastTexture) switchTexture(texture)",
		// 而 end() 会把 lastTexture 置成 null,于是 null != null 为假,连 switchTexture
		// 都不走,顶点照样写进缓冲(UV 是写死的 0/1)。要到 batch.end() → flush() →
		// lastTexture.bind() 才 NPE。这就是崩溃栈指向 end()、看起来和封面毫无关系的原因
		// (libGDX 1.14 SpriteBatch.java:206 置 null / :531 判等 / :975 bind)。
		//
		// 被 retire 掉的那张图不会在本帧被释放:drainRetiredTextures() 在 render() 开头跑,
		// 本帧 drain 之后才 retire 的,要到下一帧才 dispose。所以拿本地引用画一帧是安全的。
		Texture tex = stagefile;
		if (tex == null) return;
		float x = (skinW - STAGEFILE_W) / 2f;
		float y = (skinH - STAGEFILE_H) / 2f;
		batch.begin();
		batch.setColor(1, 1, 1, 1);
		batch.draw(tex, x, y, STAGEFILE_W, STAGEFILE_H);
		batch.end();
	}

	/**
	 * 加载中的提示。
	 *
	 * 切歌时音频要重新解码(setModel() 那一步,几百毫秒到几秒),这期间列表高亮已经
	 * 移到新歌、封面被摘掉、进度条归零 —— 不给个说明的话,用户看到的就是
	 * "歌名变了但什么都没有",像是卡住了。
	 *
	 * 这也是"不要傻快"的另一半:切换本身没错,错的是切换过程中界面不说自己在干什么。
	 * 只在 {@link #loading} 期间绘制,而且此时封面区本来就是空的,不会挡住任何东西。
	 */
	private void drawLoadingIndicator(SpriteBatch batch) {
		if (!loading || font == null) return;
		String text = "Loading...";
		com.badlogic.gdx.graphics.g2d.GlyphLayout layout =
				new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, text);
		float x = (skinW - STAGEFILE_W) / 2f;
		float y = (skinH - STAGEFILE_H) / 2f;
		batch.begin();
		font.setColor(0.85f, 0.85f, 0.90f, 1f);
		font.draw(batch, text,
				x + (STAGEFILE_W - layout.width) / 2f,
				y + STAGEFILE_H / 2f);
		batch.end();
	}

	private void drawSpectrum(SpriteBatch batch) {
		// MusicPlayer 强制把全局设置里的"线条"(MODE_WAVEFORM)和"频谱"(MODE_SPECTRUM)
		// 都按频谱条渲染 —— 本类没有 waveform 绘制路径,只画 bars。
		// 若用户在 Settings 里选了"关闭"(MODE_OFF),这里直接 return,空出中部频谱区。
		// 这样既覆盖了"线条模式下进入 MusicPlayer 闪退"的历史问题,又允许用户主动关掉频谱。
		if (resource.getConfig().getAudioVisualizationMode() == bms.player.beatoraja.Config.MODE_OFF) {
			return;
		}
		// 1. 取频谱(64 段 = 32 左 + 32 右,合并成 32 段单声道)
		AudioSpectrumProvider provider = AudioSpectrumManager.getGlobalProvider();
		float[] raw = provider == null ? null : provider.getSpectrumMagnitudes();
		if (raw == null || raw.length < 64) {
			// 退化:全 0
			for (int i = 0; i < SPEC_BANDS; i++) {
				specBands[i] = 0f;
			}
		} else {
			for (int i = 0; i < SPEC_BANDS; i++) {
				float left = raw[i];
				float right = raw[32 + i];
				float v = (left + right) * 0.5f;
				if (v < 0f) v = 0f;
				if (v > 1f) v = 1f;
				specBands[i] = v;
			}
		}

		// 2. 顶部 peak 衰减
		for (int i = 0; i < SPEC_BANDS; i++) {
			if (specBands[i] > specTopValues[i]) {
				specTopValues[i] = specBands[i];
			} else {
				specTopValues[i] -= SPEC_FALL_SPEED;
				if (specTopValues[i] < 0) specTopValues[i] = 0;
			}
		}

		// 3. 边框
		if (shapeRenderer == null) {
			shapeRenderer = new ShapeRenderer();
		}
		Gdx.gl.glEnable(Gdx.gl.GL_BLEND);
		Gdx.gl.glBlendFunc(Gdx.gl.GL_SRC_ALPHA, Gdx.gl.GL_ONE_MINUS_SRC_ALPHA);
		shapeRenderer.setProjectionMatrix(batch.getProjectionMatrix());
		shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

		// 背景框
		shapeRenderer.setColor(0f, 0f, 0f, 0.6f);
		shapeRenderer.rect(specX, specY, specW, specH);

		// 频谱条
		float bandW = specW / SPEC_BANDS;
		float barThickness = bandW * 0.7f;
		for (int i = 0; i < SPEC_BANDS; i++) {
			float x = specX + i * bandW;
			float v = specBands[i];
			float top = specTopValues[i];
			float barHeight = v * (specH - 4f);
			float topY = top * (specH - 4f);
			barHeight = Math.min(barHeight, specH - 4f);

			// 主条:蓝绿渐变(按频率从低到高,颜色从青到紫)
			float hue = (float) i / SPEC_BANDS;
			shapeRenderer.setColor(0.3f + hue * 0.4f, 0.7f, 1f - hue * 0.5f, 0.85f);
			shapeRenderer.rect(x + (bandW - barThickness) / 2f, specY + 2f,
					barThickness, barHeight);

			// 顶部峰值
			if (topY > 2f) {
				shapeRenderer.setColor(1f, 1f, 1f, 0.9f);
				shapeRenderer.rect(x + (bandW - barThickness) / 2f, specY + 2f + topY - 2f,
						barThickness, 2f);
			}
		}
		shapeRenderer.end();
	}

	private void drawProgressBar(SpriteBatch batch) {
		long currentMs = getCurrentPlaybackMs();
		float progress = totalDurationMs > 0
				? Math.min(1f, (float) currentMs / totalDurationMs)
				: 0f;
		float barX = 48f;
		float barY = BTN_MARGIN_BOTTOM + BTN_SIZE + 32f;
		float barW = skinW - 96f;
		float barH = 8f;

		Texture blank = getWhiteTexture();
		batch.begin();
		if (blank != null) {
			// 底色
			batch.setColor(0.20f, 0.20f, 0.25f, 1f);
			batch.draw(blank, barX, barY, barW, barH);
			// 进度
			batch.setColor(0.95f, 0.90f, 0.40f, 1f);
			batch.draw(blank, barX, barY, barW * progress, barH);
		}
		batch.end();

		// 时间文字
		if (font != null) {
			batch.begin();
			font.setColor(0.8f, 0.8f, 0.8f, 1f);
			String timeText = formatTime(currentMs) + " / " + formatTime(totalDurationMs);
			font.draw(batch, timeText, barX, barY - 8f);
			batch.end();
		}
	}

	private void drawControlButtons(SpriteBatch batch) {
		// 4 个按钮:上一首 / 下一首 / 模式 / 退出
		float totalW = BTN_SIZE * 4 + BTN_GAP * 3;
		float startX = (skinW - totalW) / 2f;
		float y = BTN_MARGIN_BOTTOM;

		Texture tex = getWhiteTexture();
		batch.begin();
		if (tex != null) {
			batch.setColor(0f, 0f, 0f, 1f); // 纯黑背景
			for (int i = 0; i < 4; i++) {
				float x = startX + i * (BTN_SIZE + BTN_GAP);
				batch.draw(tex, x, y, BTN_SIZE, BTN_SIZE);
			}
		}
		batch.end();

		// 按钮文字
		if (font != null) {
			batch.begin();
			font.setColor(1, 1, 1, 1);
			String[] labels = {"PREV", "NEXT", modeLabel(playMode), "EXIT"};
			for (int i = 0; i < 4; i++) {
				float x = startX + i * (BTN_SIZE + BTN_GAP);
				font.draw(batch, labels[i], x + 12f, y + BTN_SIZE / 2f);
			}
			batch.end();
		}
	}

	private static String modeLabel(PlayMode m) {
		switch (m) {
			case SEQUENCE: return "SEQ";
			case RANDOM:   return "RND";
			case LOOP_ONE: return "LOOP";
			default: return "?";
		}
	}

	@Override
	public void input() {
		// 物理键盘 / Android BACK 键
		if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT)) {
			playPrev();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) {
			playNext();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.M)) {
			cyclePlayMode();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
			// libGDX Android 后端会把 BACK 重映射成 ESCAPE 派发 (见 logcat "BACK detected, remapping to ESCAPE"),
			// 所以这里 catch ESC 就同时 catch 了 Android BACK。
			// 行为:等价于点击 EXIT 按钮(切回选曲界面)
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}

		// 列表触屏:滑动 / 点击
		handleListTouch();

		// 触屏:4 个按钮区域(列表已开始拖拽时不响应按钮,避免和列表冲突)
		if (!listDragging && Gdx.input.justTouched()) {
			int gx = main.getInputProcessor().getMouseX();
			int gy = main.getInputProcessor().getMouseY();
			int idx = hitTestControlButton(gx, gy);
			if (idx >= 0) {
				switch (idx) {
					case 0: playPrev(); break;
					case 1: playNext(); break;
					case 2: cyclePlayMode(); break;
					case 3: main.changeState(MainStateType.MUSICSELECT); break;
				}
			}
		}
	}

	private void handleListTouch() {
		// 坐标必须走 InputProcessor 的 mouse 坐标:它经过 screenToGame 变换
		// (等比缩放 + pillarbox/letterbox 居中偏移),和 render() 画出来的位置是同一套坐标。
		//
		// 原来这里图省事用了 Gdx.input.getX()/getY(),那是**屏幕物理像素**,缺两步换算:
		//   1. 缺缩放。皮肤 1280x720 在 2400x1080 的屏幕上会被放大 1.5 倍,而判定还按
		//      720 的坐标算 —— 偏差随 y 增大而累积,点第 4 首会落到第 6 首
		//      (行高 56,正好差两行)。上方按钮没这个问题,因为它们本来就用 getMouseX/Y。
		//   2. 缺居中偏移。20:9 屏幕左右各有黑边,列表右半边换算回来落在判定范围之外,
		//      表现就是"列表右边点不动、也滑不了"。
		int gx = main.getInputProcessor().getMouseX();
		int gy = main.getInputProcessor().getMouseY();
		boolean touched = Gdx.input.isTouched() || Gdx.input.justTouched();

		if (touched) {
			if (!listDragging) {
				if (isInListArea(gx, gy)) {
					listDragging = true;
					listDragStartY = gy;
					listTouchedBarIndex = computeBarIndexAtTouch(gy);
				}
			} else {
				// 手指向下滑(gdx_y 减小) -> listDragOffset > 0
				listDragOffset = listDragStartY - gy;
			}
		} else {
			if (listDragging) {
				if (Math.abs(listDragOffset) < LIST_TAP_THRESHOLD) {
					// 视为 tap:选中并开始播放
					if (listTouchedBarIndex >= 0) {
						selectedIndex = listTouchedBarIndex;
						loadAndPlaySelected();
					}
				} else {
					// 视为 drag:按行换 selectedindex
					// 渲染方向(手指下滑 → 行下移)走的是 convention 1(content follows finger),
					// 手指下滑时进入视野的是上方(LOWER 索引)的曲子,snap 必须与之一致:
					// listDragOffset > 0 → newSel 减小。
					if (allSongs != null && allSongs.length > 0) {
						int deltaSel = -Math.round(listDragOffset / LIST_LINE_H);
						int n = allSongs.length;
						selectedIndex = ((selectedIndex + deltaSel) % n + n) % n;
					}
				}
				listDragging = false;
				listDragOffset = 0f;
			}
		}
	}

	private boolean isInListArea(int gx, int gy) {
		if (gx < LIST_LEFT_X - 24f || gx > LIST_RIGHT_X + 24f) return false;
		float listTopY = skinH - LIST_TOP_Y;
		float listBottomY = listTopY - LIST_VISIBLE * LIST_LINE_H;
		return gy >= listBottomY && gy <= listTopY;
	}

	private int computeBarIndexAtTouch(int gy) {
		final SongData[] songs = allSongs;
		if (songs == null || songs.length == 0) return -1;
		final int count = songs.length;
		// 窗口尺寸必须和 drawSongList() 逐字一致,否则"看到的"和"点到的"会错位。
		final int visible = Math.min(LIST_VISIBLE, count);
		final int half = visible / 2;
		// 绘制公式(drawSongList):row r 中心 y = baseRow0Y - r * LIST_LINE_H - listDragOffset
		// 反推:row = (baseRow0Y - gy - listDragOffset) / LIST_LINE_H
		// 注意是减 —— 内容被整体下移(listDragOffset > 0)时,同一个屏幕位置上放的是
		// 更靠上(更小 row)的行。这里原来写成了加,只是靠"按下时 listDragOffset 必然
		// 已被上一轮归零"侥幸不出错,别依赖这个巧合。
		float baseRow0Y = skinH - LIST_TOP_Y - LIST_LINE_H * 0.5f;
		int row = Math.round((baseRow0Y - gy - listDragOffset) / LIST_LINE_H);
		if (row < 0 || row >= visible) return -1;
		// floorMod —— 同 drawSongList():(x + count) % count 在 count < half 时是负数。
		return Math.floorMod(selectedIndex + row - half, count);
	}

	// synchronized(this) —— 跟 transitionToNextInBackground() 同一把锁,
	// 防止"手动 NEXT 按钮"和"曲尾自动 transition"竞争导致重复起线程 / 音频重叠。
	private synchronized void loadAndPlaySelected() {
		if (allSongs == null || selectedIndex < 0 || selectedIndex >= allSongs.length) return;
		SongData next = allSongs[selectedIndex];
		if (next == null) return;
		// 重活全交给 loader 线程:解析 BMS + setModel(解码几百个音源)+ 算 tail,
		// 单首可能几百毫秒到几秒。留在这里会直接卡住 Choreographer
		// (logcat 里的 "Skipped N frames!"),而随之而来的 stop-the-world GC
		// 会把 BGAutoplayThread 一起暂停 —— 音符迟到,就是日志里的
		// "BGAutoplay starved for Nms"。停旧线程 / 停音符 / 起新线程都由
		// loadSingle() 在 loader 上完成,顺序不变。
		requestLoad(selectedIndex, false);
	}

	private int hitTestControlButton(int gx, int gy) {
		float totalW = BTN_SIZE * 4 + BTN_GAP * 3;
		float startX = (skinW - totalW) / 2f;
		float y = BTN_MARGIN_BOTTOM;
		for (int i = 0; i < 4; i++) {
			float x = startX + i * (BTN_SIZE + BTN_GAP);
			if (gx >= x && gx <= x + BTN_SIZE && gy >= y && gy <= y + BTN_SIZE) {
				return i;
			}
		}
		return -1;
	}

	private void playNext() {
		advanceByMode(true);
	}

	private void playPrev() {
		advanceByMode(false);
	}

	/**
	 * 按当前 playMode 推进到下一首/上一首(用于手动 PREV/NEXT 按钮和歌曲结束的自动切歌)。
	 *  - LOOP_ONE:不切歌,直接重新播放当前曲目
	 *  - RANDOM:随机选一首跟当前不同的;若曲目数 <= 1 则保持当前
	 *  - SEQUENCE:在所有歌曲列表中顺序/逆序移动
	 */
	private void advanceByMode(boolean forward) {
		if (allSongs == null || allSongs.length == 0) return;

		switch (playMode) {
			case LOOP_ONE:
				loadAndPlaySelected();
				return;
			case RANDOM: {
				if (allSongs.length <= 1) {
					loadAndPlaySelected();
					return;
				}
				java.util.Random rng = new java.util.Random();
				int newIdx = selectedIndex;
				int safety = 16;
				while (newIdx == selectedIndex && safety-- > 0) {
					newIdx = rng.nextInt(allSongs.length);
				}
				selectedIndex = newIdx;
				loadAndPlaySelected();
				return;
			}
			case SEQUENCE:
			default:
				int n = allSongs.length;
				selectedIndex = forward ? (selectedIndex + 1) % n : (selectedIndex - 1 + n) % n;
				loadAndPlaySelected();
				return;
		}
	}

	private void cyclePlayMode() {
		switch (playMode) {
			case SEQUENCE: playMode = PlayMode.RANDOM; break;
			case RANDOM:   playMode = PlayMode.LOOP_ONE; break;
			case LOOP_ONE: playMode = PlayMode.SEQUENCE; break;
		}
	}

	/**
	 * 当前播放进度(ms)。
	 *
	 * 用 {@link System#nanoTime()}(单调时钟)而不是 {@link System#currentTimeMillis()}:
	 * wall clock 会被 NTP 校时 / 用户改表往前往后跳,一跳就是几百毫秒甚至几分钟,
	 * 进度条会瞬移,自动切歌也会在错误的时刻触发。
	 */
	private long getCurrentPlaybackMs() {
		if (playBaseNanos == 0) return 0;
		return (System.nanoTime() - playBaseNanos) / 1000000L;
	}

	private static String formatTime(long ms) {
		if (ms < 0) ms = 0;
		long sec = ms / 1000;
		long min = sec / 60;
		sec = sec % 60;
		return String.format("%d:%02d", min, sec);
	}

	@Override
	public void shutdown() {
		// 状态切换(EXIT → MUSICSELECT)走这里。必须和 dispose() 一样彻底停掉音频与线程,
		// 否则 worker 监视任务仍存活,曲尾会触发自动切歌重新起播
		// —— 表现为"退出 MusicPlayer 后音乐还在播放"。
		// 注意:不 dispose font/shapeRenderer(留待下次进入复用),也不调 super.dispose()。
		terminatePlayback();
	}

	/**
	 * Android 屏幕关掉 (Activity.onPause/onStop) 时 libGDX 会调到这里。
	 * 父类 MainState.pause()/resume() 是空实现,默认会让 render() 停止被调用。
	 * 这里保持 BG 自动播放线程不受影响 —— 它跑在自己线程上,基于单调时钟
	 * (System.nanoTime() - baseNanos) 推进,Activity 生命周期无关。
	 * Oboe 音频流也由 AAudio 单独驱动,只要进程不被打死就会继续播。
	 * 这样锁屏 / 屏幕关 / 应用切到其他 activity 短暂遮挡时音乐不会中断。
	 *
	 * 注意:真正的"应用彻底后台" (Android 进程被杀) 需要 ForegroundService + mediaPlayback
	 * 才能继续播 —— 那需要改 AndroidManifest 启动前台服务和 Oboe Usage::Game → Media。
	 * 单纯 override pause/resume 只能保证 standby / 短时切换应用 不断音。
	 */
	@Override
	public void pause() {
		// 锁屏 / 切走:立刻标记后台,render() 和封面解码都随之停手。
		// 播放线程和 worker 上的切歌不受影响 —— 那才是锁屏时唯一该继续的事。
		backgrounded = true;
		// 主动关掉持续渲染。libGDX 通常会在 onPause 自己停掉 GL 线程,但不同设备
		// 上 Surface 销毁有早有晚,这一段里的每帧绘制都是白烧 CPU。
		if (Gdx.graphics != null) {
			Gdx.graphics.setContinuousRendering(false);
		}
		// 现在释放舞台图。pause() 走 GL 线程且上下文此刻仍然有效,是安全的。
		// 必须在这里放掉:后台时 render() 不再跑,而切歌仍会把旧纹理塞进
		// stagefileToDispose 等 render 清理 —— 那就永远没人清,锁屏播一晚上
		// 能攒下几十张纹理。
		disposeGlResources(true);
	}

	@Override
	public void resume() {
		// 回到前台,恢复绘制(放在最前:下面的 disposeGlResources/schedule 都依赖它)
		backgrounded = false;
		// 从 standby / 切回前台 时,libGDX 会关掉持续渲染,这里重新打开
		if (Gdx.graphics != null) {
			Gdx.graphics.setContinuousRendering(true);
		}
		// GL 上下文在后台被销毁重建过,本类持有的所有 Texture 句柄都已失效 ——
		// 注意:失效的 Texture 对象不是 null,所以"== null 才重载"的老判断是错的,
		// 必须无条件丢弃重来,否则回到前台第一帧就拿着野句柄去 draw。
		// 注意这里传 false:GL 上下文已经在后台被销毁重建过了,手上这些句柄全是
		// 野指针,只能丢弃引用,不能拿去 dispose。
		disposeGlResources(false);
		if (currentSong != null) {
			// 不要在这里同步解码封面。resume() 跑在 GL 线程上,解码一张封面动辄上百毫秒,
			// 会把 Choreographer 卡住几百帧并触发 stop-the-world GC —— 而 GC 会顺带
			// 暂停 BGAutoplayThread,表现正是"切回前台时音频断一下"。
			// 丢给 worker,render() 只负责把结果上传成纹理(那很快)。
			// 后台期间 decodeStagefile() 被跳过了,所以这里重新请求一次;同时把去重 key
			// 一并写上,免得 render() 的 ensureStagefileRequested() 再多解一次同一张图。
			String key = currentSong.getPath();
			if (key != null) stagefileDecodeKey = key;
			scheduleStagefileDecode();
		}
		// MainController.resume() 重新生成了 systemfont18,旧引用指向已 dispose 的对象;
		// 重新拿一次,否则 font.draw() 引用失效纹理会导致渲染缺失。
		// 注意:旧引用绝不能在这里 dispose —— 它可能已经被 MainController 释放过了。
		if (main != null) {
			BitmapFont fresh = main.getSystemFont18();
			if (fresh != null) {
				this.font = fresh;
				this.fontOwned = false;
			}
		}
		if (this.font != null) {
			this.font.setColor(Color.WHITE);
		}
	}

	// synchronized(this) —— 跟 transitionToNextInBackground() 同一把锁,
	// 防止"用户关闭播放器"和"后台自动切歌"竞争导致孤儿线程泄漏。
	// 注意:本方法也会被 loadAndPlaySelected()(手动切歌)调用,不能在这里设 disposed=true,
	// 否则手动 NEXT 一次之后自动 transition 会永远看到 disposed=true 而不再起新线程。
	private synchronized void shutdownResources() {
		// 先彻底停掉播放线程(join),再停掉所有在飞的 note —— 顺序不能反,
		// 否则播放线程还会在 stop 之后继续往已经要被换掉的 wavmap 里塞 note。
		stopBgThread();
		// 强制停止上一首所有 K/BG 音轨 —— 已经在 Oboe 缓冲里排队的 note 不停的话,
		// 切歌后会跟新歌重叠,而且 setModel() 释放旧 PCM 后它们就是悬空引用。
		// 必须在 setModel(newModel) 之前调用,否则 wavmap 已替换。
		stopAllNotes();
		if (stagefile != null) {
			stagefile.dispose();
			stagefile = null;
		}
		// 已解码但还没上传的那张也要放掉,否则退出时漏一份 native 内存
		if (pendingStagefilePixmap != null) {
			pendingStagefilePixmap.dispose();
			pendingStagefilePixmap = null;
		}
		// 复位频谱
		for (int i = 0; i < SPEC_BANDS; i++) {
			specBands[i] = 0f;
			specTopValues[i] = 0f;
		}
		// 退出时恢复无限速(0 表示不限制),由下一状态自行决定
		Gdx.graphics.setForegroundFPS(0);
	}

	/**
	 * 彻底停止播放相关的一切:后台切换监视任务、BG 自动播放线程、在飞音符、GL 资源。
	 *
	 * <p>必须在第一件事就设 {@code disposed = true}:这样正在 worker 线程上跑的
	 * {@link #transitionToNextInBackground()} 会在它的多处 disposed 守卫点尽快 return,
	 * 不会在 transition 中途 startBgThread 起出一个没人管的孤儿播放线程。</p>
	 *
	 * shutdown()(状态切到 MUSICSELECT)和 dispose()(整体退出)共用本方法;
	 * 区别只是 dispose 额外释放 font/shapeRenderer 并调用 super.dispose()。
	 */
	private void terminatePlayback() {
		// 第一件事:置退出标志。worker 监视任务和 transition 都靠它早退。
		disposed = true;
		// 放开 CPU 锁:不放在最后是因为 shutdownResources() 里的 join 可能耗时,
		// 没必要让 CPU 在这段时间一直被强行唤醒。
		PlaybackCpuLockManager.release(this);
		// 打断 worker 上的常驻监视任务(它在 sleep(200),shutdownNow 负责 interrupt)。
		// 用 shutdownNow 而不是 shutdown:监视任务是死循环,shutdown 不会主动踢它。
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
		advanceWatcherStarted = false;
		// 加载线程也要关。shutdownNow 而不是 shutdown:正在跑的 job 可能正卡在
		// setModel() 的解码里,不打断的话它会在 dispose 之后继续往 AudioDriver 里
		// 灌一个已经退出状态的模型。任务内部有 disposed 检查,能尽快退出。
		if (loader != null) {
			loader.shutdownNow();
			loader = null;
		}
		shutdownResources();
		disposeGlResources();
	}

	@Override
	public void dispose() {
		terminatePlayback();
		// 只 dispose 自己 new 的字体。systemfont18 归 MainController 管,
		// 而且 resume() 之后 MainController 已经换过实例、旧的已释放,再动就是 double free。
		if (fontOwned && font != null) {
			font.dispose();
		}
		font = null;
		fontOwned = false;
		if (shapeRenderer != null) {
			shapeRenderer.dispose();
			shapeRenderer = null;
		}
		super.dispose();
	}

	/**
	 * 扫描所有音频文件以计算真正的音频尾部时长(tail)。
	 * 逻辑移植自 BMSPlayer.java 以确保 MusicPlayer 在数据库记录缺失时也能获得正确时长。
	 */
	private int calculateMaxTailMs(BMSModel model, int lastNoteTime) {
		int maxTailMs = 0;
		final String[] wavlist = model.getWavList();
		final File bmsDir = new File(model.getPath()).getParentFile();
		final int[] lastOccurrenceArray = new int[wavlist.length];
		java.util.Arrays.fill(lastOccurrenceArray, -1);

		for (TimeLine tl : model.getAllTimeLines()) {
			final int time = tl.getTime();
			for (int lane = 0; lane < model.getMode().key; lane++) {
				Note n = tl.getNote(lane);
				if (n == null) n = tl.getHiddenNote(lane);
				if (n != null && n.getWav() >= 0 && n.getWav() < lastOccurrenceArray.length) {
					lastOccurrenceArray[n.getWav()] = time;
				}
			}
			for (Note n : tl.getBackGroundNotes()) {
				if (n != null && n.getWav() >= 0 && n.getWav() < lastOccurrenceArray.length) {
					lastOccurrenceArray[n.getWav()] = time;
				}
			}
		}

		// 一次性建立目录索引，避免数千次 File.exists() 系统调用导致 I/O 阻塞
		// 音频位于子目录（如 audio/bgm.ogg）时才惰性补一次有界深度递归扫描
		final PCM.AudioFileIndex audioFileIndex = new PCM.AudioFileIndex(bmsDir);

		final List<int[]> sortedWavs = new ArrayList<>();
		for (int wavid = 0; wavid < lastOccurrenceArray.length; wavid++) {
			if (lastOccurrenceArray[wavid] != -1 && wavlist[wavid] != null) {
				sortedWavs.add(new int[]{wavid, lastOccurrenceArray[wavid]});
			}
		}
		sortedWavs.sort((a, b) -> Integer.compare(b[1], a[1]));

		for (int[] entry : sortedWavs) {
			final int wavid = entry[0];
			final int lastTime = entry[1];

			File audioFile = audioFileIndex.resolve(wavlist[wavid]);

			if (audioFile != null) {
				long fileSize = audioFile.length();
				if (lastTime + (fileSize / 8) <= lastNoteTime + maxTailMs) {
					continue;
				}
				int dur = bms.player.beatoraja.audio.PCM.getWavDurationMs(audioFile.getPath());
				if (dur > 0) {
					final int tailEnd = lastTime + dur;
					if (tailEnd > lastNoteTime) {
						maxTailMs = Math.max(maxTailMs, tailEnd - lastNoteTime);
					}
				}
			}
		}
		return maxTailMs;
	}

	/**
	 * BG 音轨自动播放线程 —— 仿 {@link KeySoundProcessor.AutoplayThread},但持有自己的时钟。
	 * 播完所有 timeline 后自然退出,不触发任何回调 —— 切歌由 worker 上常驻的监视任务
	 * 在 totalDurationMs 触发,中间 tail 静音期留给最后一条 note 自然播完。
	 *
	 * 与旧实现的两点关键差异:
	 * <ol>
	 *   <li><b>时间源换成 {@link System#nanoTime()}</b>。wall clock 会被 NTP 校时 / 用户改表
	 *       往前往后跳,一跳就是几百毫秒,整条 timeline 的调度会整体错位。</li>
	 *   <li><b>加了"追赶保护"</b>。应用切后台后系统可能长时间不给这个线程 CPU
	 *       (Doze / 后台 CPU 配额 / 大核下线),唤醒时 elapsed 会一下前进好几秒。
	 *       旧实现会把这几秒内积压的上千个 note 一次性全灌进 AudioDriver ——
	 *       soundpool 瞬间打满、Oboe 回调线程堆积,表现就是爆音、声音断掉、
	 *       严重时整个音频层卡死。这里检测到"时间跳跃"就把跳过的 note 丢弃(不补播),
	 *       直接从当前时间点的 timeline 继续。</li>
	 * </ol>
	 */
	private static class BGAutoplayThread extends Thread {
		private final BMSModel model;
		private final MainController main;
		volatile boolean stop = false;
		private final long baseNanos;
		/** 醒来时间比计划晚超过这个毫秒数就认为被系统饿过一次,丢弃积压的 note */
		private static final long STARVE_THRESHOLD_MS = 500L;
		/**
		 * 单次 sleep 上限。只是为了防御 interrupt 丢失之类的意外,正常情况下
		 * 靠 interrupt 打断,不需要靠短 sleep 来保持响应。
		 */
		private static final long MAX_SLEEP_MS = 2000L;

		BGAutoplayThread(BMSModel model, MainController main, long baseNanos) {
			this.model = model;
			this.main = main;
			this.baseNanos = baseNanos;
		setName("MusicPlayer-BGAutoplay");
		setDaemon(true);
		// 这个线程的唤醒精度就是音符的时序精度。默认优先级下它要和 GL 线程、GC、
		// 扫描任务一起抢 CPU,锁屏后更是被 background cgroup 压到小核慢慢排队。
		// 提到最高(Android 上约映射为 nice -8),让它尽量不被别的普通线程挤掉。
		try {
			setPriority(Thread.MAX_PRIORITY);
		} catch (Throwable ignored) {
			// 某些运行时不允许改优先级,忽略即可
		}
	}

		@Override
		public void run() {
			try {
				AudioDriver audio = main.getAudioProcessor();
				if (audio == null) return;
				float vol = main.getPlayerResource().getConfig().getAudioConfig().getBgvolume();

				Array<TimeLine> tls = new Array<>();
				for (TimeLine tl : model.getAllTimeLines()) {
					if (tl.getBackGroundNotes().length > 0 || hasKeyNote(tl)) {
						tls.add(tl);
					}
				}
				TimeLine[] timelines = tls.toArray(TimeLine.class);

				int p = 0;
				// 上次"计划醒来"的时间点(相对 elapsedMs)。追赶保护必须拿它做基准,
				// 不能用两次唤醒的间隔:去掉轮询上限后,曲子里的长静音段本来就会睡几秒,
				// 按间隔判会误判成"被系统饿过",把静音段后的第一个音符丢掉 —— 漏音。
				long scheduledWakeMs = 0;
				while (!stop) {
					long elapsedMs = (System.nanoTime() - baseNanos) / 1000000L;
					long timeMicros = elapsedMs * 1000L;

					// 实际醒来比计划晚太多 = 这段时间系统没给 CPU(锁屏/降频/被 throttle)。
					// 积压的 note 不补播:一次性灌进 AudioDriver 会打满 soundpool、
					// 让 Oboe 回调堆积,反而整段卡住。只推进游标,从当前时刻接着播。
					long overshootMs = elapsedMs - scheduledWakeMs;
					final boolean starved = overshootMs > STARVE_THRESHOLD_MS;
					if (starved && Gdx.app != null) {
						Gdx.app.log("MusicPlayer", "BGAutoplay starved for "
								+ overshootMs + "ms, skipping backlog");
					}

					while (p < timelines.length && timelines[p].getMicroTime() <= timeMicros) {
						if (!starved) {
							TimeLine tl = timelines[p];
							for (Note n : tl.getBackGroundNotes()) {
								audio.play(n, vol, 0);
							}
							for (int lane = 0; lane < tl.getLaneCount(); lane++) {
								Note n = tl.getNote(lane);
								if (n != null) {
									audio.play(n, vol, 0);
								}
							}
						}
						p++;
					}
					if (p >= timelines.length) {
						// 所有 note 已播完 → 线程自然退出,切歌等 worker 监视任务触发
						break;
					}

					// 用"播放循环结束之后"的时间重算调度基准。循环本身可能耗时很久:
					// 密集段落一次要发几百个 note,每个 audio.play() 都要过 JNI 并抢
					// AudioDriver 的 monitor,几百毫秒很正常。不重算的话,这段耗时会被
					// 下一轮原样算进 overshoot —— 一旦超过阈值就判定 starved 并丢掉
					// 接下来的音符。表现出来就是"鼓点密的地方突然断一截",而且丢完
					// 立刻又跑下一轮,自我强化、越丢越多。
					elapsedMs = (System.nanoTime() - baseNanos) / 1000000L;
					timeMicros = elapsedMs * 1000L;

					// 直接睡到下一个音符,不再固定间隔轮询。
					// 原来 10ms 上限意味着每秒约 100 次唤醒,绝大多数是空转(稀疏段落里
					// 相邻音符常隔几百毫秒)。锁屏后 CPU 被压到小核并受 cgroup 限制,
					// 这么高的唤醒频率正是最容易被 throttle 的目标 —— 一被限就是整批
					// 音符迟到。停止走 interrupt(),长 sleep 照样能立刻打断。
					long sleepMs = (timelines[p].getMicroTime() - timeMicros) / 1000L;
					if (sleepMs < 1) sleepMs = 1;
					if (sleepMs > MAX_SLEEP_MS) sleepMs = MAX_SLEEP_MS;
					scheduledWakeMs = elapsedMs + sleepMs;
					try {
						sleep(sleepMs);
					} catch (InterruptedException e) {
						if (stop) break;
					}
				}
			} catch (Throwable e) {
				// 兜底:任何异常都不能让播放线程"静默消失" —— 那正是"音频断开"的现象之一
				if (Gdx.app != null) {
					Gdx.app.error("MusicPlayer", "BGAutoplayThread aborted", e);
				}
			}
		}

		private static boolean hasKeyNote(TimeLine tl) {
			for (int i = 0; i < tl.getLaneCount(); i++) {
				if (tl.getNote(i) != null) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * 曲终自动切歌入口 —— 由 worker 上常驻的监视任务在 totalDurationMs 触发。
	 *
	 * 与 {@link #loadAndPlaySelected()} 的区别:loadAndPlaySelected 在 GL 线程上调用,
	 * 包含完整的 shutdownResources + create(含 stagefile Texture 上传等);
	 * 而本方法跑在 worker 线程,跳过所有 GL 依赖,只做:
	 * 停播放线程 → stop 所有 note → 推进曲目 → 加载 BMSModel → 设新音频模型 → 启新线程。
	 * GL 相关的 stagefile 清理/加载推迟到下一个 render() 统一处理。
	 *
	 * <b>注意:这里必须 stop 播放线程 + stop 所有 note,再 setModel()。</b>
	 * 旧实现直接 setModel(),而 setModel() 会换掉 wavmap 并 disposeOld() 释放旧 PCM ——
	 * Oboe 的 PCM 是 native 对象,播放线程/混音器还拿着旧引用就是 use-after-free,
	 * 直接 SIGSEGV。这正是"后台播着播着音频断开、切回前台闪退"的主因。
	 *
	 * @return true 表示成功切换;false 表示被并发守卫拦截(重复调用 / 已 dispose)
	 */
	private synchronized boolean transitionToNextInBackground() {
		if (isTransitioning) return false;
		// 早期 disposed 检查 —— dispose() 已设 disposed=true 并在等锁,
		// 本方法一拿到锁就该立刻放弃,不要白白加载 BMSModel / 算 tail / 设音频模型。
		if (disposed) return false;
		// 与 loader 流水线共用同一把锁。
		//
		// 手动 PREV/NEXT 走 loadLock,曲尾自动切歌原本只靠 synchronized(this) ——
		// 这是两把不同的锁,互不排斥。在曲尾附近点 NEXT 时两条路径会并发
		// setModel():一个刚把 wavmap 换成新模型的,另一个还拿着旧引用在播,
		// 而 setModel() 会 disposeOld() 释放 native PCM(Oboe),就是 use-after-free。
		// 所以自动切歌必须进同一把 loadLock,和手动切歌真正串行起来。
		synchronized (loadLock) {
			isTransitioning = true;
			try {
				// 1. 先停播放线程 + 停掉所有在飞的音符,然后才能安全地换 wavmap。
				//    (旧实现漏了这一步,是崩溃的根源)
				stopBgThread();
				stopAllNotes();
				// 同步退出"正在播放"的显示态。后面 setModel() 要花几百毫秒到几秒,
				// 不清的话进度条会拿上一首的基准继续往前跑(还会被 totalDurationMs 截到 100%)。
				// 清零后进度条归零,render() 那边会画上 Loading 提示。
				playBaseNanos = 0;
				totalDurationMs = 0;

				// 2. 按 playMode 推进到下一首(与 advanceByMode 同逻辑)
				// 注意:先推进歌曲再清理 stagefile,确保 render() 如果在此期间运行
				// 看到的是已更新的 currentSong,从而 loadStagefile() 加载正确的封面。
				if (allSongs == null || allSongs.length == 0) {
					// 置 0 让监视任务闭嘴(判据是 total > 0),否则它会每 200ms 重新触发
					// 一次切歌,变成死循环(加载失败 → 立刻再触发 → 再失败 …)
					silenceAdvanceWatcher();
					Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
					return true;
				}

				switch (playMode) {
					case LOOP_ONE:
						// 保持当前曲目,直接重启
						break;
					case RANDOM: {
						if (allSongs.length > 1) {
							java.util.Random rng = new java.util.Random();
							int cur = selectedIndex;
							int newIdx = cur;
							for (int safety = 16; safety > 0 && newIdx == cur; safety--) {
								newIdx = rng.nextInt(allSongs.length);
							}
							selectedIndex = newIdx;
						}
						break;
					}
					case SEQUENCE:
					default:
						// floorMod:selectedIndex 是跨线程 volatile,一旦它越界(或为负),
						// (x + 1) % length 照样会给出负下标,下一行 allSongs[selectedIndex]
						// 就直接崩在 GL 线程上。这里顺手兜住。
						selectedIndex = Math.floorMod(selectedIndex + 1, allSongs.length);
						break;
				}

				// 5. currentSong 已更新,在此之后清理 stagefile,保证 render() 若同时运行
				// 看到的是新 currentSong,loadStagefile() 会加载正确封面。
				this.currentSong = allSongs[selectedIndex];
				if (currentSong == null) {
					// 置 0 让监视任务闭嘴(判据是 total > 0),否则它会每 200ms 重新触发
					// 一次切歌,变成死循环(加载失败 → 立刻再触发 → 再失败 …)
					silenceAdvanceWatcher();
					Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
					return true;
				}

				// 换歌了,旧封面必须立刻摘下来。以前这行和下面的解码一起被套在
				// "if (stagefile != null)" 里,后果是:上一首解完还没上传(null)、
				// 或者上一首没封面时,既不清也不解 —— 屏幕上一直挂着旧图,
				// 新歌因此永远等不到自己的封面。摘除和解码是两件独立的事。
				retireStagefile();

				// 6. 加载新 BMSModel(纯文件 I/O + 解析,不需要 GL)
				this.currentModel = resource.loadBMSModel(
						Gdx.files.absolute(currentSong.getPath()),
						resource.getPlayerConfig().getLnmode());
				if (currentModel == null) {
					// 置 0 让监视任务闭嘴(判据是 total > 0),否则它会每 200ms 重新触发
					// 一次切歌,变成死循环(加载失败 → 立刻再触发 → 再失败 …)
					silenceAdvanceWatcher();
					Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
					return true;
				}

				// 7. 设置新音频模型(在飞的 note 已在第 1 步停掉,可以安全换 wavmap)
				//    再查一次 disposed:setModel() 会占住 AudioDriver 的锁做完整解码(可能几百毫秒
				//    ~数秒),期间 GL 线程再碰 audio 就会被挡住,没必要在退出时还锁一把。
				if (disposed) return false;
				main.getAudioProcessor().setModel(currentModel);
				resource.setPlayMode(BMSPlayerMode.AUTOPLAY);

				// 8. 记下时长参数,准备把播放线程拉起来。
				//    顺序很关键:tail 计算要读几千个音频头再写一次 SQLite,可能好几秒,
				//    而它和封面解码都不发声。让它们挡在 startBgThread() 前面的话,
				//    上一首早已播完、下一首迟迟不开始 —— 中间那几秒就是纯空白。
				final int lastEventTime = currentModel.getLastTime();
				final int lastNoteTime = currentModel.getLastNoteTime();
				int tail = currentSong.getTail();

				// 9. 启动新线程。dispose() 可能在第 6~8 步期间被调用过,这里再确认一次,
				//    否则会起出一个没人管的孤儿播放线程。
				if (disposed) return false;
				this.playBaseNanos = System.nanoTime();
				startBgThread(currentModel);
				// 立刻给一个时长,让进度条从 0 开始正常走;精确值在方法末尾覆盖它。
				// isTransitioning 要到 finally 才复位,监视任务碰不到这次赋值。
				this.totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + Math.max(tail, 0));

				// ---- 以下都不发声,放在播放线程起来之后 ----

				// 趁还在 worker 线程上把新封面解好。别留给 render() 在主线程解码:
				// 解码 + 随之而来的 GC 会卡住 Choreographer,而 stop-the-world GC
				// 会把 BGAutoplayThread 一起暂停,音符整批迟到。
				// 结果带归属标记;若上传前又切了歌,applyPendingStagefile() 会把它丢掉。
				stagefileDecodeKey = currentSong.getPath();
				decodeStagefile();

				if (tail <= 0) {
					// 这一段会读几千个音频文件的头 + 写 SQLite,包一层容错:
					// 后台数据库被别的线程占用时抛异常不能把整个切歌流程带崩
					try {
						tail = calculateMaxTailMs(currentModel, lastNoteTime);
						currentSong.setTail(tail);
						main.getSongDatabase().updateSongTail(currentModel.getSHA256(), tail);
					} catch (Throwable e) {
						if (Gdx.app != null) {
							Gdx.app.error("MusicPlayer", "tail calculation failed", e);
						}
						if (tail <= 0) tail = 1000;
					}
				}
				this.totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + tail);
				Gdx.app.log("MusicPlayer", "transitionToNext totalDurationMs: " + totalDurationMs + " ms, tail: " + tail + ", lastNoteTime: " + lastNoteTime + ", lastEventTime: " + lastEventTime);

				// 10. 复位频谱(纯内存,无 GL)
				for (int i = 0; i < SPEC_BANDS; i++) {
					specBands[i] = 0f;
					specTopValues[i] = 0f;
				}
				return true;
			} finally {
				isTransitioning = false;
			}
		}
	}
}
