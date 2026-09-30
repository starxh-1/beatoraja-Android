package bms.player.beatoraja;

import bms.player.beatoraja.select.BarRenderer;

/**
 * 「选曲条渲染器」的来源。
 *
 * <h3>为什么要有这个接口</h3>
 * <p>{@code select.SkinBar}（选曲列表这个对象）原本是这样拿渲染器的：</p>
 *
 * <pre>render = ((MusicSelector) state).getBarRender();</pre>
 *
 * <p>也就是硬性要求 state 必须是真的 {@code MusicSelector}。于是「皮肤选择」界面的
 * 实时预览（那里 state 是 {@link bms.player.beatoraja.config.SkinConfiguration}）里
 * 这一步直接 {@code ClassCastException} → 异常被 {@code Skin.drawAllObjectsSafely} 吞掉
 * → 对象被永久置 {@code draw=false}。<b>整条选曲列表（含曲名、等级、灯、奖杯、标签、
 * 分布图）就此消失</b> —— 这是 musicselect 预览里最大的一个空洞。</p>
 *
 * <p>把它抽成最小契约之后：</p>
 * <ul>
 *   <li><b>真选曲界面</b>：{@code MusicSelector} 实现本接口（它本来就有
 *       {@code getBarRender()}，零改动），取值与改动前完全一致；</li>
 *   <li><b>皮肤预览</b>：{@code SkinConfiguration} 借用 {@code MainController} 上长驻的
 *       那个 {@code MusicSelector} 的渲染器（它持有真实的 {@code BarManager}，
 *       因此预览里看到的就是<b>真的选曲列表</b>，不是假的）。</li>
 * </ul>
 *
 * <p>注意 {@code BarRenderer} 内部绘制 bar 的文本 / 灯 / 等级全靠它自己的
 * {@code BarManager} 数据，唯一额外需要的是「按哪张 {@code MusicSelectSkin} 排版」
 * —— 预览时那张皮肤的 {@code getSkin()} 已被清空（离开选曲界面时
 * {@code MainController} 会 {@code setSkin(null)}），所以预览走的宿主会顺手
 * 把预览皮肤设成 {@code BarRenderer} 的排版覆盖，见 {@code SkinConfiguration#getBarRender()}。</p>
 */
public interface BarRenderSource {

	/**
	 * 选曲条渲染器。{@code null} = 这个界面没有选曲条可画（调用方必须判空）。
	 */
	BarRenderer getBarRender();
}
