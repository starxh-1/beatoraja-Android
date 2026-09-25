package bms.player.beatoraja.skin;

/**
 * 皮肤图像的纹理过滤策略（image id 白名单 / 黑名单）。
 *
 * <p>🔴 <b>背景</b>：2026-09-25 曾试过「非 1:1 一律 BILINEAR」（在
 * {@code SkinObject.draw()} 公共路径按缩放比例自动升级 filter），实机证实副作用过大
 * （folder 的 graph-lamp 颜色流淌、PLAY 判定线 / lane 边缘淡出、BPM 区域紫边），
 * 已整体回退到上游行为。本类是替代方案：<b>默认全部 Nearest（与上游一致），
 * 只有白名单 id 才升级 Linear，黑名单 id 强制 Nearest（连皮肤的 filter 声明都覆盖）</b>。</p>
 *
 * <p>🔴 <b>为什么落在 {@link JsonSkinObjectLoader} 层（经
 * {@code JSONSkinLoader.setDestination} 之后调用 {@link #apply})</b>：</p>
 * <ul>
 *   <li>此时皮肤 json 的 {@code filter} 声明已经写进 {@code dstfilter}，
 *       黑名单才能真正「覆盖」而不是被覆盖。</li>
 *   <li>上游的 {@code dstfilter} 机制本身是半成品：它给的是 {@code TYPE_BILINEAR}，
 *       而 {@code Skin.SkinObjectRenderer.setFilter()} 的白名单<b>不含 BILINEAR</b>
 *       （{@code bilinear.frag} 是纯透传 shader，真正的插值由纹理 min/mag filter 决定）。
 *       所以白名单直接给 {@code TYPE_LINEAR}（setFilter 认它），绕开这个坑。</li>
 *   <li>只对 {@link SkinImage} 及其子类生效：text（{@code SkinText}）/ 数字等对象
 *       自带 filter 路径（如 {@code SkinTextFont.draw} 用 {@code getFilter()}），
 *       不归本策略管。这同时避免「黑名单关键词误伤同名 text id」
 *       （例如 {@code bartext} 是 text 对象，{@code bar-} 前缀不应影响它）。</li>
 * </ul>
 *
 * <p>🔴 <b>副作用防线（folder lamp 渗色的教训）</b>：{@code SkinDistributionGraph}
 * 等<b>不经过 {@code SkinObject.draw()}</b> 的对象会继承 renderer 当前的 type。
 * 黑名单把 {@code bar-*}（graph 前一刻绘制的行背景）钉死在 NORMAL，
 * 白名单又只有 {@code bg} 这类在场景最底层绘制的对象 →
 * graph 读到的 type 恒为 NORMAL，渗色在结构上不可能再发生。
 * <b>因此：不要把 graph/lamp 前一刻绘制的 id 加进白名单。</b></p>
 *
 * <p>名单按<b>小写前缀</b>匹配。调整名单后无需改其它代码，重载皮肤即生效。</p>
 */
public final class SkinTextureFilterPolicy {

	/**
	 * 🔴 强制 Nearest（黑名单）：即使皮肤 json 声明了 {@code filter} 也覆盖掉。
	 *
	 * <p>收录标准：<b>「拉伸 + 边界有语义」</b>的素材 —— 被插值会毁掉的东西。
	 * 具体即：1px 帧分布图、判定/音符/键梁等音游戏性素材、位图字体条、
	 * 以及 graph 前一刻绘制的行背景（防线见类注释）。</p>
	 */
	private static final String[] FORCED_NEAREST_PREFIXES = {
			"lamp",        // lamp / lamp-* / graph-lamp / playerlamp（1px 帧、小竖条）
			"graph",       // graph-lamp / graph-rank / graph-best / graph-now / graph-target / notes-graph
			"bar",         // 选曲行背景：imageset "bar"（成员 bar-song 等）+ bar-*。
			               // graph 的直接前驱，必须 NORMAL（防线）。bartext 是 SkinText，
			               // 被 instanceof 守卫挡住，不受影响。
			"judge",       // judge-* / judgef-* / judgen-* / judgems-*（判定图像，像素级锐利）
			"section-line",// 判定线
			"note-",       // note-* 音符
			"bomb",        // bomb* 爆炸
			"keybeam",     // keybeam* 键梁
			"lane",        // lane-* 轨道背景
			"hold-",       // hold-* 长条
			"mine",        // mine / mine-* 地雷
			"hcn",         // hcn*（HCN 蛇）
			"ln",          // lna- / lnb- / lne- / lns- / lnbomb（LN 系全部）
			"songs_font",  // 选曲列表位图字体条（要保锐利）
			"gauge",       // gauge-* 血条
			"rank",        // rank / rank_a...（等级字母图像）
			"trophy",      // trophy-* 小图标
			"keys",        // keys 键区图像
			"option-selector", // 选项勾选/数字条
			"playlevel_bar",
			"hidden-cover",
			"load-progress",
			"close",
	};

	/**
	 * ✅ 允许 Linear（白名单）：默认 Nearest 的世界里<b>只有</b>这些 id 例外。
	 *
	 * <p>收录标准：<b>照片 / 柔和渐变大图，且绘制位置远离 graph / lamp</b>。
	 * 初版只放全屏背景（1280×720 源图在本机必然被放大，是锯齿重灾区，
	 * 且是最先绘制的底层，不构成 type 泄漏风险）。文字不要加进来 ——
	 * text 对象不经过本策略（见类注释），它们的平滑由皮肤 json 的
	 * {@code filter} 声明（default 皮肤已在 title/artist 上写了）负责。</p>
	 */
	private static final String[] LINEAR_ALLOWED_PREFIXES = {
			"bg",          // select 的 bg（全屏照片，必被放大）
			"background",  // play 的 background（同上。注意 startsWith("bg") 对它不成立，必须单列）
	};

	private SkinTextureFilterPolicy() {
	}

	/**
	 * 按 id 应用策略。在 {@code setDestination}（dstfilter 已赋值）之后、
	 * {@code skin.add(obj)} 之前调用。
	 *
	 * @param obj 刚构建完的皮肤对象（可为 null）
	 * @param id  皮肤 json 的 dst id（可为 null / 数字字符串）
	 */
	public static void apply(SkinObject obj, String id) {
		if (obj == null || id == null) {
			return;
		}
		// 只管图像对象。text / 数字等有自己的 filter 路径（见类注释）。
		if (!(obj instanceof SkinImage)) {
			return;
		}
		final String lid = id.toLowerCase();

		if (matches(lid, FORCED_NEAREST_PREFIXES)) {
			// 覆盖皮肤声明的 filter，钉死 Nearest。
			obj.setFilter(0);
			obj.setImageType(Skin.SkinObjectRenderer.TYPE_NORMAL);
			return;
		}

		if (matches(lid, LINEAR_ALLOWED_PREFIXES)) {
			// 仅升级「默认图」（imageType == NORMAL）。
			// 動画 (SkinSourceMovie) 在构造时已是 TYPE_LINEAR，由其自身 render type 管理，不动。
			if (obj.getImageType() == Skin.SkinObjectRenderer.TYPE_NORMAL) {
				obj.setImageType(Skin.SkinObjectRenderer.TYPE_LINEAR);
			}
		}
	}

	private static boolean matches(String lowercaseId, String[] prefixes) {
		for (String prefix : prefixes) {
			if (lowercaseId.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}
}
