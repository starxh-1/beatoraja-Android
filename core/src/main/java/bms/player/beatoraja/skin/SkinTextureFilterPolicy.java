package bms.player.beatoraja.skin;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * 皮肤纹理设置 —— 全仓库唯一决定「皮肤纹理怎么取样」的地方。
 *
 * <h2>现在只有两件事</h2>
 * <pre>
 *   一、默认 = Linear            所有皮肤对象（JSON / Lua / LR2）一律升 Linear
 *   二、INSET_HALF_TEXEL = true  从共享图集切图时把 UV 内缩半个 texel —— 治「图集渗色」
 * </pre>
 *
 * <h2>为什么这两件就够了（2026-09-26 实机结论）</h2>
 * <p>皮肤图集普遍是「手写紧排 + 零 padding」且「整张 PNG 一张 Texture」，而非整数缩放让
 * Linear 的 2×2 取样核几乎必然跨过区域边界 → 边界附近吃到邻居像素（就是「穿透」）。
 * 工业标准解法是给图集每个区域留外扩边（edge extrude）；改不了第三方素材，就在取样端
 * 把 UV 往里收半个 texel —— 取样点落到本区域第一个 texel 的中心，渗色随之消失，
 * 而平滑度不变。实机确认「全 Linear + 半 texel 内缩」效果足够，于是把此前那套
 * 「按 id / 源图 / 引擎语义把个别图钉回 Nearest」的机制整套撤除。</p>
 *
 * <h2>历史：2026-09-25 ~ 09-26 曾存在、现已撤除的「钉 Nearest」机制</h2>
 * <pre>
 *   ①  NEAREST_PREFIXES      非文字对象 id 前缀（section-line / keys）
 *   ①b NEAREST_ID_CONTAINS   非文字对象 id 子串（scroll）+ 渲染期整张锁
 *   ②  NEAREST_TEXT_PREFIXES 文字 id 前缀（当时为空）
 *   ③  NEAREST_SOURCES       源图整张（default/system.png、/lamp.png）
 *   ④  PIN_SELECT_SONGLIST   选曲条整组（引擎 songlist 块）
 * </pre>
 * <p>配套的 {@code LOCKED_OBJECTS / PINNED_TEXTURES / pinNearest / pinSonglist /
 * onTextureLoaded / isPinned / setNearestLock} 等一并删除
 * （涉及 {@code Skin} / {@code SkinObject} / {@code JSONSkinLoader} /
 * {@code JsonSelectSkinObjectLoader}）。</p>
 * <p>⚠️ <b>旧实现全文留档在 {@code docs/archive/SkinTextureFilterPolicy_v417.java.txt}</b>
 * （v4.17，605 行）；来龙去脉见 {@code docs/skin-texture-filtering.md} §18–§24。
 * 日后某个皮肤又出渗色时，从存档里捞回对应名单加回来即可 —— 本类仍是它该待的地方。</p>
 *
 * <h2>⚠️ 不在本类管辖内的两处（改这里没用）</h2>
 * <ul>
 *   <li>{@code SkinTextImage}（LR2 位图字体，逐字符切帧）：类内 {@code draw} <b>按设计恒
 *       Nearest</b> —— Linear 必然字符间渗色。见 {@link #applyLinear}。</li>
 *   <li><b>自绘 UI 纹理</b>：{@code new Texture(Pixmap)} 出来的（FloatingMenu 图标、
 *       触摸指针…）不经过 {@code SkinObjectRenderer}，要 Linear 得各自显式设。</li>
 * </ul>
 */
public final class SkinTextureFilterPolicy {

	/**
	 * 🔴 <b>构建指纹</b> —— 确认设备上跑的到底是哪一版策略。
	 * 2026-09-25 曾因设备装着几小时前的旧构建而误判三四轮，所以<b>每次改动本类请一并改版本号</b>。
	 * 查法：{@code adb logcat | grep SkinFilter}（加载皮肤时打一行）；或拉 APK 扫 dex
	 * （见 {@code docs/dev-workflow.md} §1.3）。纯注释改动不必升版本。
	 */
	public static final String BUILD_TAG = "SkinFilterPolicy v5.0 (2026-09-26) all=LINEAR; inset-half-texel=ON; lr2=LINEAR; nearest-machine=REMOVED";

	private static boolean buildReported = false;

	/**
	 * 已被本类处理过的对象（{@link WeakHashMap} 键集合，随皮肤一起 GC，反复切皮肤不泄漏）。
	 * 作用：让 {@link #applyDefault} 不重复处理 ⇒ {@link #applyAll} 对 JSON / Lua 皮肤
	 * 重复调用也是幂等的。
	 */
	private static final Set<SkinObject> POLICY_APPLIED =
			Collections.newSetFromMap(new WeakHashMap<SkinObject, Boolean>());

	// ─────────── 一、默认 = Linear ───────────
	// 见类注释。入口只有两个：apply()（JSON / Lua 的 destination 循环）与 applyAll()（LR2）。

	// ─────────── 二、半 texel UV 内缩（治图集渗色）───────────
	/**
	 * true：把每个「从共享图集里切出来」的区域 UV 向内缩<b>半个 texel</b>，
	 * 让 Linear 的 2×2 取样核不再跨过区域边界 → 消除图集渗色。
	 *
	 * <p>🔴 <b>2026-09-26 LIAO 开启</b>（原默认 false —— 按项目规矩，公共路径上的
	 * <b>全局渲染</b>改动必须默认失效、实测通过再打开）。回退 = 改回 {@code false}，
	 * 切图路径立刻恢复成 {@code new TextureRegion(...)} 原样，零残留。</p>
	 *
	 * <p><b>原理</b>：{@code new TextureRegion(tex, x, y, w, h)} 的 UV 正好贴在区域边上
	 * （{@code u = x/texW}、{@code u2 = (x+w)/texW}）。Linear 在 u = 区域左边界取样时，
	 * 双线性核会覆盖到<b>左邻区域</b>的最后一个 texel ⇒ 边界附近混进邻居的颜色。
	 * 内缩半个 texel 后，区域左边界落到<b>本区域第一个 texel 的中心</b>，双线性在该点恰好
	 * 等于该 texel 本身 ⇒ 渗色彻底消失（但平滑度不变：它不让 Linear 更锐或更柔，只是把
	 * 「取到错误像素」变成「取到正确像素」）。</p>
	 *
	 * <p><b>代价（已实测接受）</b>：区域在源码空间少了 1 个 texel 的宽/高，而
	 * {@code regionWidth/Height} 不变 ⇒ 内容被拉伸 {@code w/(w-1)} 倍。16px 轨道 ≈ 6%、
	 * 24px 音符帧 ≈ 4%，肉眼可接受；但 <b>1~2px 的细元素（灯条分段、1px 线）会被压缩</b>；
	 * {@code w == 1} 时 {@code u == u2} → 恒定取样同一个 texel（输出纯色，不会消失，
	 * 但失去「1px 宽」的语义）。⚠️ 动画帧表（divx/divy 紧排）每帧各缩半个 texel，
	 * 等于裁掉外圈 1px 内容 —— 若某皮肤动画帧边缘缺一条，就是这个原因。</p>
	 */
	public static final boolean INSET_HALF_TEXEL = true;

	// ─────────── 三、让 LR2 皮肤也吃「默认 = Linear」───────────
	/**
	 * true：LR2（{@code .lr2csv} / {@code .lr2skin}）皮肤也套用「默认 = Linear」。
	 *
	 * <h2>🔴 为什么需要单独一个开关</h2>
	 * <p>本类的钩子只有一个：{@code JSONSkinLoader} 的 destination 循环里调 {@link #apply}。
	 * 而 {@code SkinLoader.load(...)} 是按皮肤文件后缀分三支的：</p>
	 * <pre>
	 *   .json     → JSONSkinLoader   ← 有 apply 钩子 ✅
	 *   .luaskin  → LuaSkinLoader    ← extends JSONSkinLoader，同样有 ✅
	 *   else      → LR2SkinHeaderLoader + LR2SkinCSVLoader  ← 完全没钩子 ❌
	 * </pre>
	 * <p>所以 LR2 皮肤的每个 {@code SkinObject} 都停在 libGDX 的纹理默认值
	 * （{@code Texture} 默认 = <b>Nearest</b>），一个都没被升过 Linear ——
	 * 而 LR2 恰好是 640×480 老素材、非整数放大最厉害的一类，锯齿最明显。</p>
	 *
	 * <h2>补做点</h2>
	 * <p>{@code SkinLoader.load()} 的 LR2 分支末尾（{@link #applyAll}）—— 那儿是 LR2 皮肤
	 * 对象的<b>唯一</b>构建出口（{@code SkinAdjustModel} 只读 header，不建对象）。
	 * 放在 {@code loadSkin()} 返回<b>之后</b>，确保所有 {@code setDestination} 都执行完了，
	 * 本类写下的 {@code imageType} 才是最终生效的那个。</p>
	 *
	 * <p>撤销：改回 {@code false}（LR2 立刻恢复成 libGDX 默认的纯 Nearest）。</p>
	 */
	public static final boolean APPLY_POLICY_TO_LR2 = true;

	private SkinTextureFilterPolicy() {
	}

	/**
	 * 从图集里切一块区域 —— <b>皮肤切图的统一入口</b>，取代直接写 {@code new TextureRegion(...)}。
	 *
	 * <p>{@link #INSET_HALF_TEXEL} 关闭时<b>与 {@code new TextureRegion(tex, x, y, w, h)} 完全等价</b>
	 * （连 UV 都不碰，w/h 为 0 或负数时也与原行为一致）；打开时才做半 texel 内缩。
	 * 现共 10 处调用：{@code JsonSkinObjectLoader} 1 处、{@code LR2SkinCSVLoader} 6 处、
	 * {@code LR2PlaySkinLoader} 3 处。</p>
	 *
	 * @param tex 源纹理
	 * @param x   区域左上角 x（像素，允许为负 / 越界，行为同 libGDX）
	 * @param y   区域左上角 y（像素，同上）
	 * @param w   区域宽（像素）
	 * @param h   区域高（像素）
	 * @return 该区域的 {@link TextureRegion}（UV 可能已按开关内缩）
	 */
	public static TextureRegion slice(Texture tex, int x, int y, int w, int h) {
		TextureRegion region = new TextureRegion(tex, x, y, w, h);
		if (INSET_HALF_TEXEL && w > 0 && h > 0) {
			insetHalfTexel(region);
		}
		return region;
	}

	/**
	 * 对已有 region 做半 texel 内缩（见 {@link #INSET_HALF_TEXEL}）。
	 * 幂等性<b>不保证</b> —— 每调用一次就再缩半个 texel，别重复调。
	 * 只改 UV，不动 {@code regionWidth/Height}，所以不影响布局与 {@code TYPE_BILINEAR}
	 * 的判定（那边按 {@code regionWidth} 与实际绘制尺寸比）。
	 */
	public static void insetHalfTexel(TextureRegion region) {
		if (region == null || region.getTexture() == null) {
			return;
		}
		final float dx = 0.5f / region.getTexture().getWidth();
		final float dy = 0.5f / region.getTexture().getHeight();
		// 🔴 libGDX 1.14 起 TextureRegion 的 u/v/u2/v2 字段是【包私有】，不能直接改写
		//    （javap 确认：float u,v,u2,v2 均无 public 修饰符），只能走 getU/setU 等。
		//    u<u2、v<v2 恒成立（本类的构造路径 w/h>0），所以两边都是「往里收」，与翻转无关。
		region.setU(region.getU() + dx);
		region.setU2(region.getU2() - dx);
		region.setV(region.getV() + dy);
		region.setV2(region.getV2() - dy);
	}

	/**
	 * 按「默认 = Linear」处理一个皮肤对象。
	 *
	 * <p>调用点：{@code JSONSkinLoader} 的 destination 循环（{@code setDestination} 之后、
	 * {@code skin.add} 之前）⇒ 覆盖 JSON 皮肤与 Lua 皮肤（{@code LuaSkinLoader extends
	 * JSONSkinLoader}）。</p>
	 *
	 * @param obj 刚构建完的皮肤对象（{@code null} 忽略）
	 */
	public static void apply(SkinObject obj) {
		reportBuild();
		if (obj == null) {
			return;
		}
		POLICY_APPLIED.add(obj);
		applyLinear(obj);
	}

	/**
	 * 给<b>没经过 {@link #apply}</b> 的对象补做「默认 = Linear」。
	 * 唯一使用者 = {@link #applyAll}（LR2 皮肤，见 {@link #APPLY_POLICY_TO_LR2}）。
	 *
	 * <p>幂等且不重复：对象已在 {@link #POLICY_APPLIED} 里就直接跳过，
	 * 所以对 JSON 皮肤调用是安全的。</p>
	 *
	 * @param obj 皮肤对象（{@code null} 忽略）
	 */
	public static void applyDefault(SkinObject obj) {
		if (obj == null || !POLICY_APPLIED.add(obj)) {
			return;
		}
		applyLinear(obj);
	}

	/**
	 * 把「默认 = Linear」套到一张已加载完的皮肤的<b>全部对象</b>上
	 * （{@link Skin#getAllSkinObjects()}）。调用点只有一个：{@code SkinLoader.load(...)}
	 * 的 <b>LR2 分支末尾</b>。
	 *
	 * <p>JSON / Lua 两支的每个对象早已在 destination 循环里被 {@link #apply} 处理过，
	 * 由 {@link #POLICY_APPLIED} 去重，所以本方法对它们不产生任何影响。</p>
	 *
	 * <p>受 {@link #APPLY_POLICY_TO_LR2} 总开关控制。</p>
	 *
	 * @param skin 刚加载完的皮肤（{@code null} 忽略）
	 * @return 本次实际处理的对象数（供调用方打一行日志核对）
	 */
	public static int applyAll(Skin skin) {
		if (!APPLY_POLICY_TO_LR2 || skin == null) {
			return 0;
		}
		int count = 0;
		for (SkinObject obj : skin.getAllSkinObjects()) {
			if (obj != null && !POLICY_APPLIED.contains(obj)) {
				applyDefault(obj);
				count++;
			}
		}
		return count;
	}

	/** 皮肤加载时打一行构建指纹（整个进程只打一次）。 */
	private static void reportBuild() {
		if (!buildReported) {
			buildReported = true;
			System.out.println("[SkinFilter] " + BUILD_TAG);
		}
	}

	/**
	 * 「默认 = Linear」的真正落点。
	 *
	 * <p>对象分两类：</p>
	 * <ul>
	 *   <li><b>文字</b>（{@code SkinTextFont} = TTF、{@code SkinTextBitmap} = .fnt 位图字体）：
	 *       它们的 {@code draw} 每帧按 {@code getFilter()} 自选 LINEAR / NORMAL，与
	 *       {@code imageType} 无关 ⇒ 这里只设 {@code filter}。
	 *       {@code SkinTextImage}（LR2 逐字符切帧的位图字体）<b>按设计恒 Nearest，不碰</b>
	 *       —— Linear 会让相邻字符互相渗色。</li>
	 *   <li><b>非文字对象</b>：把 {@code imageType} 从「默认」（{@code TYPE_NORMAL}）升到
	 *       {@code TYPE_LINEAR}，{@code SkinObjectRenderer.setFilter} 才会去升纹理。
	 *       皮肤已声明的其它 type（{@code TYPE_BILINEAR} / {@code TYPE_DISTANCE_FIELD}…）
	 *       不动。</li>
	 * </ul>
	 *
	 * <p>⚠️ 与皮肤 json 声明的 {@code filter} 冲突时<b>本策略赢</b>：{@code SkinObject.draw}
	 * 里那条件是 {@code dstfilter != 0 && imageType == TYPE_NORMAL}，我们已把 imageType
	 * 设成 LINEAR，条件不成立。</p>
	 */
	private static void applyLinear(SkinObject obj) {
		if (obj instanceof SkinText) {
			if (obj instanceof SkinTextFont || obj instanceof SkinTextBitmap) {
				obj.setFilter(1);
			}
			return;
		}
		if (obj.getImageType() == Skin.SkinObjectRenderer.TYPE_NORMAL) {
			obj.setImageType(Skin.SkinObjectRenderer.TYPE_LINEAR);
		}
	}
}
