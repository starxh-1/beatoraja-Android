package bms.player.beatoraja.play;

import bms.model.BMSModel;
import bms.model.Mode;
import bms.model.NormalNote;
import bms.model.TimeLine;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PlayStateValues;

/**
 * 皮肤预览用的合成「游玩态数值」：让判定 / 连击 / 量表在预览里也显示出来，
 * 而且**用的是皮肤自己的美术** —— 判定图、连击数字、量表条全都是皮肤里定义的对象，
 * 这里只负责给它们喂值。
 *
 * <h3>为什么不用真的判定 / 量表</h3>
 * <p>真值挂在 {@link JudgeManager} / {@link GrooveGauge} 上，而它们由 {@link BMSPlayer} 持有。
 * 在预览里造一个真 {@code BMSPlayer} 就得跑它的 {@code create()}，而那会动输入处理器、
 * {@code FileCache}、皮肤缓存等全局单例，与真实游玩无法共存 —— 详见
 * {@code docs/skinselect-live-preview.md} 第五节。所以这里只把这三个值**合成**出来，
 * 不碰任何游玩侧对象。</p>
 *
 * <p>合成方式：判定在 PERFECT / GREAT / GOOD 之间轮换（每 {@link #JUDGE_INTERVAL} 换一次），
 * 连击随时间递增，量表在一个周期内从 {@link #GAUGE_MIN_RATE} 平滑涨到满。目的只是
 * 「看着像在游玩」，不模拟真实得分。</p>
 */
public final class PreviewPlayValues implements PlayStateValues {

	/** 判定 / 连击的推进间隔（ms）。 */
	private static final long JUDGE_INTERVAL = 400;
	/** 判定序列：1 = PERFECT、2 = GREAT、3 = GOOD（与 JudgeManager 同义，1 起算）。 */
	private static final int[] JUDGE_SEQUENCE = { 1, 1, 1, 2, 1, 1, 3, 1 };
	/** 连击绕圈的跨度，避免数字无限增长。 */
	private static final int COMBO_WRAP = 300;
	/** 量表涨满一轮的周期（ms）。 */
	private static final long GAUGE_PERIOD = 20000;
	/** 量表周期的最低点（占最大值的比例）：别掉到 0，否则再也涨不回来（见下）。 */
	private static final float GAUGE_MIN_RATE = 0.2f;

	private final MainState state;
	private final GrooveGauge gauge;
	private final float gaugeMax;

	/**
	 * @param state     预览所在的界面状态，只用来取时钟
	 * @param mode      当前预览皮肤的 Mode，决定量表规则
	 * @param gaugeType 用户在设置里选的量表类型（{@code PlayerConfig.getGauge()}，0~5）
	 */
	public PreviewPlayValues(MainState state, Mode mode, int gaugeType) {
		this.state = state;
		GrooveGauge created = null;
		float max = 100f;
		try {
			created = GrooveGauge.create(createDemoModel(mode), gaugeType, 0, null);
			if (created != null) {
				max = created.getGauge().getProperty().max;
			}
		} catch (Throwable e) {
			// 量表造不出来就不画量表，判定 / 连击照常
			created = null;
		}
		this.gauge = created;
		this.gaugeMax = max;
	}

	/**
	 * 造一个能喂 {@link GrooveGauge} 的极小演示谱面。**不能是空模型**：
	 * 量表增减补正里的 {@code GaugeModifier.TOTAL} 是
	 * {@code f * model.getTotal() / model.getTotalNotes()}，
	 * 总音符数为 0 会算出 Infinity / NaN 留在量表内部数组里。所以给一个音符，
	 * 并把 TOTAL 设成 1.0 让补正量级落在正常范围。
	 */
	private static BMSModel createDemoModel(Mode mode) {
		final BMSModel model = new BMSModel();
		model.setMode(mode);
		model.setBpm(150);
		model.setTotal(1.0);
		final TimeLine timeline = new TimeLine(0, 0, mode.key);
		timeline.setNote(0, new NormalNote(0));
		model.setAllTimeLine(new TimeLine[] { timeline });
		return model;
	}

	@Override
	public int getNowJudge(int player) {
		final int index = (int) ((now() / JUDGE_INTERVAL) % JUDGE_SEQUENCE.length);
		return JUDGE_SEQUENCE[index];
	}

	@Override
	public int getNowCombo(int player) {
		// 从 1 起算：0 会让皮肤里的连击数字看起来像"还没开始打"
		return 1 + (int) ((now() / JUDGE_INTERVAL) % COMBO_WRAP);
	}

	/**
	 * 量表**在这里顺带推进**（不用额外的每帧钩子）：按当前时间把值设到周期内对应的位置，
	 * 上限 / 死亡线由 {@code Gauge.setValue} 自己钳。
	 */
	@Override
	public GrooveGauge getGauge() {
		if (gauge != null) {
			final double phase = (now() % GAUGE_PERIOD) / (double) GAUGE_PERIOD;
			gauge.setValue(gaugeMax * (GAUGE_MIN_RATE + (1f - GAUGE_MIN_RATE) * (float) phase));
		}
		return gauge;
	}

	/** 用预览界面自己的时钟，与音符下落同一个时间源。 */
	private long now() {
		return state != null && state.timer != null ? state.timer.getNowTime() : System.currentTimeMillis();
	}
}
