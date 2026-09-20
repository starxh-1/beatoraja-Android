package bms.player.beatoraja.play;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.IntIntMap;

import bms.model.Mode;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PlayConfig;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.play.SkinNote.SkinLane;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.SkinObject;
import bms.player.beatoraja.skin.SkinObject.SkinOffset;

import java.util.logging.Logger;

/**
 * 皮肤选择界面「实时预览」用的自绘音符层 —— 合成一段"看起来像在游玩"的落音符动画。
 *
 * <h3>为什么不用真音符</h3>
 * <p>真音符由 {@link SkinNote} → {@link LaneRenderer} 绘制，而 {@code SkinNote.prepare()}
 * 第一行就要求 state 是 {@link BMSPlayer}（`(BMSPlayer) state`），{@code LaneRenderer} 运行时
 * 还要活的 {@code timer} / {@code JudgeManager} / {@code RhythmTimerProcessor} /
 * {@code BGAManager}。皮肤预览的 state 是 {@code config.SkinConfiguration}，这些一个都没有 ——
 * 所以放不放谱面都没用，缺的是"一个不带音频/输入的 play 宿主"。</p>
 *
 * <p>折中办法就是本类：<b>只借用皮肤的静态资产</b>（每条轨道的 note 贴图、轨道矩形、
 * 音符厚度、dst 偏移），配上下落几何（与 {@link LaneRenderer#drawLane} 同源）与用户自己的
 * hispeed / lanecover / lift 设置，自己画一圈循环的下落音符。看得见的是"这张皮肤在游玩时
 * 长什么样"：轨道宽度、音符贴图与厚度、判定线位置、下落速度都对得上。</p>
 *
 * <h3>与真实渲染的差别（有意为之）</h3>
 * <ul>
 *   <li>没有真正的谱面：音符按固定的 16 槽（2 小节 8 分音符）图案合成、循环播放；</li>
 *   <li>只有普通音符（单点 + 和弦 + 皿），没有长条 / 地雷 / 判定特效 / 音符扩张动画；</li>
 *   <li>BPM 固定 {@link #DEMO_BPM}，只用来决定下落速度量级。</li>
 * </ul>
 *
 * <p>对象由 {@code SkinConfiguration.loadSelectedSkinPreview()} 插到预览皮肤的
 * {@code SkinNote} 之后（同一绘制层），因此层级与真实音符一致。</p>
 */
public class PreviewNoteLayer extends SkinObject {

	/** 演示用 BPM：只决定下落速度量级（真实 play 里来自谱面）。 */
	private static final double DEMO_BPM = 150;
	/** 一屏穿越时间的钳制范围，避免用户的极端 hispeed 让预览变成瞬移或爬行。 */
	private static final double MIN_TRAVEL_MS = 300;
	private static final double MAX_TRAVEL_MS = 3000;

	/** 皮肤选项里"竖屏布局"的 id（与 LaneRenderer / BMSPlayer 一致）。 */
	private static final int OP_PORTRAIT = 1101;

	/** 8 分音符网格：16 槽 = 2 小节 4/4，循环。 */
	private static final int STEPS = 16;
	/** 主音符的 lane（对键位数取模），-1 = 该槽无主音符。 */
	private static final int[] PRIMARY = { 0, 1, 2, 3, 0, 1, 2, 3, 4, 4, 4, 5, 6, 4, 5, -1 };
	/** 和弦副音符 = PRIMARY[槽] + 此偏移（-1 = 无和弦）。 */
	private static final int[] SECOND = { -1, -1, -1, -1, 4, -1, -1, -1, -1, -1, -1, 2, -1, 1, -1, -1 };
	/** 皿音符所在槽（仅在有皿的 Mode 下生效）。 */
	private static final boolean[] SCRATCH = { true, false, false, false, false, false, false, false,
			true, false, false, false, false, false, false, true };

	private final SkinLane[] lanes;
	/** 沿用 SkinNote 自己的 dst 偏移，保证音符落在和真实渲染相同的位置。 */
	private final SkinOffset[] offsets;
	private final PlaySkin skin;
	private final Mode mode;
	/** 键位数（不含皿）。 */
	private final int keys;
	/** 皿所在的 lane 下标，-1 表示该 Mode 没有皿。 */
	private final int scratchLane;

	/** 每帧解析一次的音符贴图（SkinNote.prepare 跑不到，贴图要自己取）。 */
	private final TextureRegion[] noteImages;

	private MainState state;
	private long time;

	private boolean portraitResolved;
	private boolean portrait;

	/** `draw()` 抛异常时只记一次，避免刷屏。 */
	private boolean errorLogged;

	private boolean configResolved;
	private float hispeed = 1f;
	private boolean enableLanecover;
	private double lanecover;
	private boolean enableLift;
	private float lift;

	public PreviewNoteLayer(SkinNote source, PlaySkin skin, Mode mode) {
		this.lanes = source.getLanes();
		this.offsets = source.getOffsets();
		this.skin = skin;
		this.mode = mode;
		this.noteImages = new TextureRegion[lanes.length];

		final boolean hasScratch = mode == Mode.BEAT_5K || mode == Mode.BEAT_7K
				|| mode == Mode.BEAT_10K || mode == Mode.BEAT_14K;
		this.keys = Math.max(1, lanes.length - (hasScratch ? 1 : 0));
		this.scratchLane = hasScratch ? lanes.length - 1 : -1;

		// 必须至少有一个 destination，否则会在 Skin.validate() 阶段被删掉（同 SkinNote）。
		// 它只用于通过校验：真正的绘制坐标全部由轨道矩形算出来。
		this.setDestination(0, 0, 0, 1, 1, 0, 255, 255, 255, 255, 0, 0, 0, 0, 0, 0, new int[0]);
	}

	/**
	 * 竖屏布局判定：与 {@link LaneRenderer} 里那套完全一致（先看皮肤自定义选项 Layout，
	 * 再看皮肤 option 里有没有 1101）。延迟到 prepare 时才判定，是为了和游玩时同一条路径
	 * —— {@code Skin.prepare()} 会把 option 表清空，所以游玩时真正生效的是 header 里那条。
	 * 没有抽取共用方法是为了不碰游玩主路径的渲染代码。
	 */
	private static boolean detectPortrait(PlaySkin skin) {
		boolean isPortrait = false;
		try {
			if (skin.header != null) {
				for (SkinHeader.CustomOption co : skin.header.getCustomOptions()) {
					if (co.name.equals("Layout") && co.getSelectedOption() == OP_PORTRAIT) {
						isPortrait = true;
						break;
					}
				}
			}
			if (!isPortrait && skin.getOption() != null) {
				for (IntIntMap.Entry e : skin.getOption()) {
					if (e.value == OP_PORTRAIT) {
						isPortrait = true;
						break;
					}
				}
			}
		} catch (Throwable e) {
			// 取不到就按横屏处理
		}
		return isPortrait;
	}

	@Override
	public void prepare(long time, MainState state) {
		this.state = state;
		this.time = time;
		if (!portraitResolved) {
			portraitResolved = true;
			portrait = detectPortrait(skin);
		}
		resolveConfig(state);

		for (int i = 0; i < lanes.length; i++) {
			final SkinLane lane = lanes[i];
			try {
				// 【必须】region 只在 SkinObject.prepareRegion() 里算出来（setDestination 只写
				// dst[]/fixr，从不写 region）。而 SkinNote.prepare() 第一行 `(BMSPlayer) state`
				// 就抛异常 → 它后面那句 `lane.prepare()` 永不执行 → 每条轨道的 region 恒为
				// (0,0,0,0)。不补这一步，下面的下落几何会算出 travel = 0，一个音符都画不出来。
				lane.prepareRegion(time, state);
				// 贴图同理要自己取：SkinLane.prepare 里的 noteImage 也不会被算出来。
				noteImages[i] = lane.note != null ? lane.note.getImage(time, state) : null;
			} catch (Throwable e) {
				// 单条轨道失败不影响其它轨道，更不该让整个对象被永久停用
			}
		}

		try {
			super.prepare(time, state);
		} catch (Throwable e) {
			// 预览环境没有 play 数据，destination 求值失败不影响自绘
		}
		// 本对象画什么完全由自绘逻辑决定，不受 dstdraw / mouseRect 影响
		draw = true;
	}

	/** 读用户自己的 play 设置（hispeed / lanecover / lift），让预览的速度与遮挡贴近实际。 */
	private void resolveConfig(MainState state) {
		if (configResolved || state == null) {
			return;
		}
		configResolved = true;
		try {
			final PlayerConfig player = state.resource.getPlayerConfig();
			final PlayConfig play = player.getPlayConfig(mode).getPlayconfig();
			hispeed = Math.max(0.1f, play.getHispeed());
			enableLanecover = play.isEnablelanecover();
			lanecover = clamp01(play.getLanecover());
			enableLift = play.isEnablelift();
			lift = (float) clamp01(play.getLift());
		} catch (Throwable e) {
			// 拿不到就用默认值：hispeed 1.0、无 lanecover / lift
		}
	}

	private static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	@Override
	public void dispose() {
		// 贴图都归预览皮肤所有（SkinSource / SkinLane），这里只放掉引用，不释放任何纹理
		java.util.Arrays.fill(noteImages, null);
	}

	@Override
	public void draw(SkinObjectRenderer renderer) {
		if (!draw || state == null || lanes == null || lanes.length == 0) {
			return;
		}
		try {
			drawNotes(renderer);
		} catch (Throwable e) {
			// 【别删】drawAllObjectsSafely 吞掉异常后只把 draw 置 false，不打印任何东西。
			// 没有这条日志，"几何算对了却一个音符都没有"这类问题完全无法定位
			// （off[] 全 null 导致的 NPE 就是靠它才抓到的，见 docs/skinselect-live-preview.md 六.2）。
			if (!errorLogged) {
				errorLogged = true;
				Logger.getGlobal().warning("PreviewNoteLayer.draw 抛异常 : " + stackTraceOf(e));
			}
		}
	}

	private static String stackTraceOf(Throwable e) {
		final java.io.StringWriter sw = new java.io.StringWriter();
		e.printStackTrace(new java.io.PrintWriter(sw));
		return sw.toString();
	}

	private void drawNotes(SkinObjectRenderer renderer) {
		// ── 下落几何：与 LaneRenderer.drawLane 同源 ──
		// hu = 音符出生端（离判定线最远），hl = 判定线位置，两者之差就是一个"屏幕高"
		final SkinLane lane0 = lanes[0];
		final double hu;
		final double hl;
		if (portrait) {
			// 竖屏：音符沿 X 轴从右往左下落，判定线在左
			final float trackWidth = lane0.region.width - 40;
			hl = (lane0.region.x + 40) + trackWidth * lift;
			hu = lane0.region.x + lane0.region.width;
		} else {
			hu = lane0.region.y + lane0.region.height;
			hl = enableLift ? lane0.region.y + lane0.region.height * lift : lane0.region.y;
		}
		final double travel = hu - hl;
		if (travel <= 1) {
			return;
		}

		// 一屏穿越时间：真实渲染里 region = 240000 / bpm / hispeed（bpm 取谱面）
		final double travelMs = Math.max(MIN_TRAVEL_MS,
				Math.min(MAX_TRAVEL_MS, 240000.0 / DEMO_BPM / hispeed));
		final double pxPerMs = travel / travelMs;
		// lane cover 遮挡区：直接不画，效果等同于音符被罩子盖住（本层画在罩子之后，
		// 若照画反而会浮在罩子上面）
		final double covered = enableLanecover ? travel * lanecover : 0;

		float offsetX = 0;
		float offsetY = 0;
		float offsetW = 0;
		float offsetH = 0;
		if (offsets != null) {
			for (SkinOffset offset : offsets) {
				if (offset == null) {
					continue;
				}
				offsetX += offset.x;
				offsetY += offset.y;
				offsetW += offset.w;
				offsetH += offset.h;
			}
		}

		// 8 分音符网格，按经过时间循环播放（now 用当前时间而不是 prepare 传来的 time，
		// 保证下落和帧率同步、不受 prepare 节流影响）
		final double stepMs = 30000.0 / DEMO_BPM;
		final double cycleMs = stepMs * STEPS;
		final long now = state.timer.getNowTime();
		final double phase = ((now % (long) cycleMs) + cycleMs) % cycleMs;

		renderer.setColor(1f, 1f, 1f, 1f);
		renderer.setBlend(0);
		renderer.setType(SkinObjectRenderer.TYPE_NORMAL);

		for (int k = 0; ; k++) {
			final double dt = k * stepMs - phase;
			if (dt > travelMs) {
				break;
			}
			if (dt < 0) {
				continue;
			}
			final double pos = hl + dt * pxPerMs;
			if (covered > 0 && pos < hl + covered) {
				continue;
			}

			final int slot = k % STEPS;
			final int primary = PRIMARY[slot];
			if (primary >= 0) {
				drawNote(renderer, primary, pos, offsetX, offsetY, offsetW, offsetH);
				final int second = SECOND[slot];
				if (second >= 0) {
					drawNote(renderer, primary + second, pos, offsetX, offsetY, offsetW, offsetH);
				}
			}
			if (scratchLane >= 0 && SCRATCH[slot]) {
				drawNote(renderer, scratchLane, pos, offsetX, offsetY, offsetW, offsetH);
			}
		}
	}

	/**
	 * 画一个音符。坐标推导与 {@link LaneRenderer} 的"未来音符"分支一致：
	 * 横屏音符底边落在 pos 上、宽 = 轨道宽、厚 = 皮肤定义的 scale；
	 * 竖屏整体旋转 270°（贴图宽轴对应轨道宽、高轴对应音符厚度），pos 是音符中心。
	 */
	private void drawNote(SkinObjectRenderer renderer, int laneIndex, double pos,
			float offsetX, float offsetY, float offsetW, float offsetH) {
		final int index = Math.floorMod(laneIndex, keys);
		final SkinLane lane = lanes[index];
		final TextureRegion image = noteImages[index];
		if (image == null || lane.region == null) {
			return;
		}
		final float scale = lane.scale;
		if (portrait) {
			final float width = lane.region.height + offsetH;
			final float height = scale + offsetW;
			final float x = (float) pos - width / 2f;
			final float y = lane.region.y + offsetY + (width - height) / 2f;
			renderer.draw(image, x, y, width, height, 0.5f, 0.5f, 270f);
		} else {
			renderer.draw(image, lane.region.x + offsetX, (float) pos + offsetY - offsetH / 2f,
					lane.region.width + offsetW, scale + offsetH);
		}
	}
}
