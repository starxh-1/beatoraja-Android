package bms.player.beatoraja;

import bms.player.beatoraja.play.GrooveGauge;

/**
 * play 皮肤 HUD 需要的「游玩态数值」：当前判定、连击、量表。
 *
 * <h3>为什么要有这个接口</h3>
 * <p>这三项原本只能从 {@code bms.player.beatoraja.play.BMSPlayer} 上取 ——
 * {@code SkinJudge} / {@code SkinGauge} 里直接写的是 {@code (BMSPlayer) state} 强转。
 * 于是「皮肤选择」界面的实时预览（那里 state 是 {@link bms.player.beatoraja.config.SkinConfiguration}）
 * 里判定与量表整体不显示：强转失败 → 异常被 {@code drawAllObjectsSafely} 吞掉 → 对象被
 * 永久置 {@code draw=false}。</p>
 *
 * <p>把这三个值抽成最小契约之后：</p>
 * <ul>
 *   <li><b>真游玩</b>：{@code BMSPlayer} 实现本接口，委托给 {@code JudgeManager} /
 *       {@code GrooveGauge}，取值与改动前完全一致；</li>
 *   <li><b>皮肤预览</b>：{@link bms.player.beatoraja.play.PreviewPlayValues} 给一份合成实现，
 *       让判定与量表也能用皮肤自己的美术渲染出来。</li>
 * </ul>
 *
 * <p>这么做是为了**避免**在预览里跑一个真的 play 会话 —— 那需要
 * {@code BMSPlayer.create()}，而它会动输入处理器 / {@code FileCache} / 皮肤缓存等
 * 全局单例，与真实游玩无法共存。详见 {@code docs/skinselect-live-preview.md} 第五节。</p>
 *
 * <p>默认实现返回 {@code null}（见 {@link MainState#getPlayStateValues()}），
 * <b>调用方必须判空</b>：拿不到就当作「这个界面没有游玩态」，该对象不画。</p>
 */
public interface PlayStateValues {

	/**
	 * 当前判定。取值与 {@code JudgeManager.getNowJudge(player)} 同义：**1 起算**
	 * （1 = PERFECT，2 = GREAT，3 = GOOD…），0 或负数 = 还没有判定过。
	 */
	int getNowJudge(int player);

	/**
	 * 当前连击数。取值与 {@code JudgeManager.getNowCombo(player)} 同义。
	 */
	int getNowCombo(int player);

	/**
	 * 量表。{@code null} = 这个界面没有量表可画。
	 */
	GrooveGauge getGauge();
}
