package bms.player.beatoraja.skin;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * 皮肤纹理过滤策略 —— 全仓库唯一决定「哪张图平滑（Linear）、哪张图硬边（Nearest）」的地方。
 *
 * <h2>五个开关</h2>
 * <pre>
 *   默认 = Linear            不在名单里的一切，全部升 Linear
 *   ① NEAREST_PREFIXES       留在 Nearest 的【非文字对象】id 前缀
 *   ② NEAREST_TEXT_PREFIXES  留在 Nearest 的【文字】id 前缀（当前空 → 文字全 Linear）
 *   ③ NEAREST_SOURCES        整张钉 Nearest 的【源图】文件（纹理级，与 id 无关）
 *   ④ PIN_SELECT_SONGLIST    选曲条整组钉 Nearest（引擎 songlist 块，唯一真通用锚点）
 *   ⑤ DEBUG_LOG              加载期把每个 id 的判定打进 logcat
 * </pre>
 * 名单里每个候选都写好理由、注释态备着：取消注释即钉 Nearest，注释掉即回 Linear；
 * 改完<b>重载皮肤即生效</b>，不用改皮肤 json。前缀匹配：{@code "lamp"} 命中 {@code lamp-noplay}。
 *
 * <h2>🔴 两条必须先懂的机制（否则白改）</h2>
 * <ol>
 *   <li>libGDX 的 {@code Texture} <b>默认就是 Nearest</b>；升 Linear 的唯一入口
 *       {@code SkinObjectRenderer.setFilter(Texture)}（仅当 type 为 LINEAR / DISTANCE_FIELD）
 *       <b>只升不降</b>。所以「想回 Nearest」光设 type 不够，必须直接改纹理
 *       —— {@link #forceNearestTexture} / {@link #pinNearest} 干的就是这件事。</li>
 *   <li>纹理 filter 是<b>纹理级</b>唯一值，不是对象级 ⇒ 同一张图被多个对象共享时，
 *       只要有一个 LINEAR 兄弟，它每帧就会把整张图升回去 → <b>「按 id 钉」在共享纹理上
 *       逻辑上不可能生效</b>；要钉就得钉源图整张（③）或按引擎语义分组（④）。</li>
 * </ol>
 *
 * <h2>覆盖范围与钩子</h2>
 * <ul>
 *   <li>非文字对象 → {@link #applyObject}（SkinImage / SkinNumber / SkinGraph / SkinSlider /
 *       分布图 / visualizer … 全部）；文字 → {@link #applyText}。两条分支互斥。</li>
 *   <li>钩子在 {@code JSONSkinLoader} 的 destination 循环里（{@code setDestination} 之后、
 *       {@code skin.add} 之前 —— 皮肤 json 声明的 {@code filter} 此时才写进 {@code dstfilter}，
 *       名单里的判定才有覆盖权）⇒ 覆盖 JSON 皮肤与 Lua 皮肤
 *       （{@code LuaSkinLoader extends JSONSkinLoader}）。
 *       <b>LR2 的 .lr2csv 皮肤走另一套 loader，完全不经过这里</b>。</li>
 *   <li>⚠️ <b>自绘 UI 纹理不在本类管辖内</b>：{@code new Texture(Pixmap)} 出来的
 *       （FloatingMenu 图标、触摸指针…）不经过 SkinObjectRenderer，要 Linear 得各自显式设。</li>
 * </ul>
 */
public final class SkinTextureFilterPolicy {

	/**
	 * 🔴 <b>构建指纹</b> —— 确认设备上跑的到底是哪一版策略。
	 * 2026-09-25 曾因设备装着几小时前的旧构建而误判三四轮，所以<b>每次改动策略请一并改版本号</b>。
	 * 查法：{@code adb logcat | grep SkinFilter}（加载皮肤时打一行）；或拉 APK 扫 dex（见
	 * {@code docs/dev-workflow.md} §1.3）。纯注释改动不必升版本。
	 */
	public static final String BUILD_TAG = "SkinFilterPolicy v4.12 (2026-09-26) default=LINEAR; nearest=section-line,keys; songlist-bar=NEAREST; pinned-src=default/system.png,/lamp.png";

	private static boolean buildReported = false;

	// ─────────── ① 留在 Nearest 的【非文字对象】───────────
	/** 命中 id → {@link #setNearest}；未命中 → {@link #setLinear}。取消注释即启用，重载皮肤生效。 */
	private static final String[] NEAREST_PREFIXES = {
			// ── ✅ 启用中 ──
			"section-line", // PLAY 判定线：半透明边缘被插值后整条像淡出（v4.3 实机验证 = 淡出的主因）
			"keys",         // 键位图：仅 play24 / play24double 的按键背景条（同 src 切不同宽度区域）
			// ── 实验过 / 备选（取消注释即启用）──
			// "lane",         // 轨道背景。v4.3 实测非淡出主因。⚠️ default 实际 id 是 lane-bg，前缀会连带 lanecover*
			// "lamp",         // 小竖条 / 分布条：1px 帧被拉伸，插值 = 颜色流淌
			// "bar",          // 选曲行背景（songbar.png）。⚠️ 同时是 graph-lamp 的【结构防线】：
			//                 // SkinDistributionGraph 内部走不带 type 的 sprite.draw，会继承前驱 type
			// "graph-lamp", "graph-rank", // folder 灯条 / 段位条（运行时 Pixmap 自建 11×1 / 28×1 纹理）
			//                 // ⚠️ 别用 "graph" 前缀：PLAY 的 graph-now/best/target 是缩小曲线图，Linear 收益大
			// "trophy", "load-progress", // v4.4 实验过，LIAO 看后放回 Linear
			// ── 待实测（切帧 sheet 紧排区域，可能帧间渗色）──
			// "note-", "hold-", "keybeam", "bomb", "ln", "hcn", "mine", "gauge",
			// "judge", "rank", "option-selector", "playlevel_bar", "hidden-cover", "close",
			// "level", "score", "bpm", "combo", "duration", "totalnotes", // 数字（SkinNumber）
	};

	// ─────────── ② 留在 Nearest 的【文字】───────────
	/**
	 * 当前为空 → 全部文字 Linear。理由：freetype 光栅化永远非 1:1 绘制，Nearest 锯齿最刺眼；
	 * freetype atlas 自带 padding，渗色风险极小。哪处文字发虚就把 id 前缀放进来。
	 */
	private static final String[] NEAREST_TEXT_PREFIXES = {
			// "title", "artist", "bartext", "genre", "dir", "search",
	};

	// ─────────── ③ 按【源图】整张钉 Nearest（纹理级，与 id 无关）───────────
	/**
	 * 命中的<b>源图文件</b>（路径后缀，小写、正斜杠）→ 整张纹理钉 Nearest。
	 *
	 * <p>为什么必须有这一层：{@code default/system.png} 被 8 个以上对象共享（lane-bg 轨道背景 /
	 * id 11·12·13 / id 15 判定线光带 / section-line / 数字 450·451 / 滑条 1051），其中任一
	 * LINEAR 兄弟每帧都会把整张图升回 Linear ⇒ ① 名单在这个文件上写死 id <b>不可能生效</b>。
	 * v4.6 起改为「钉源图整张 + 渲染期短路」（见 {@link #isPinned}）。</p>
	 *
	 * <p>代价（LIAO 2026-09-26 拍板接受）：同图的数字、滑条、lane-bg 渐变一起变硬边。
	 * 撤销：注释掉对应行即可（PINNED 恒空 → 渲染期短路自然失效）。</p>
	 */
	private static final String[] NEAREST_SOURCES = {
			// 写法："/xxx.png" = 【任意目录】同名文件都命中（通用）；"a/b.png" = 只匹配该路径后缀（特定皮肤）
			// ⚠️ 通用项必须带前导 "/"，否则 "folderlamp.png" 之类也会被 endsWith("lamp.png") 误伤
			"default/system.png", // default 判定线光带 id15 渗色（整张钉，理由见上）
			"/lamp.png",          // folder 灯条颜色互相污染的真因：紧排色块图被 Linear 在色块边界渗色（v4.9 实机定位）
	};

	// ─────────── ④ 选曲条整组钉 Nearest（引擎 songlist 块）───────────
	/**
	 * 选曲条整组钉 Nearest —— 由引擎自己的 {@code songlist} 块驱动，<b>不看 id 名、不看文件名</b>，
	 * 是唯一真正「跨皮肤通用」的锚点。
	 *
	 * <p>皮肤可以随便改素材名与 id 名（default 叫 {@code bar-song}、m_select 叫
	 * {@code default_songlist2_bar_song}），但「选曲条由哪几张图组成」是皮肤用 {@code songlist}
	 * 块声明的，引擎另有专用解析器读它（{@code JsonSelectSkinObjectLoader}：
	 * {@code if (sk.songlist != null && dst.id.equals(sk.songlist.id))}）。
	 * 该块逐部件枚举：{@code liston/listoff}（行背景）、{@code lamp}（★ 真正的清空灯）、
	 * {@code playerlamp/rivallamp}、{@code trophy/label}、{@code graph}（分布条）→ <b>全钉</b>；
	 * {@code text}（bartext，走 ttf / .fnt，与本组无关）与 {@code level}
	 * （★ default 里指 number.png，钉了会把选曲全部数字变硬边）→ <b>故意不钉</b>。</p>
	 *
	 * <p>⚠️ 纹理级特性顺带解决一个坑：灯条与行背景常在同一张图上（如 m_select 的 songbar.png，
	 * 灯条 {@code graph-lamp} 在 (690,486) 11×30）→ 钉行背景 = 整张图 Nearest = 灯条自动硬边，
	 * 不存在「灯条 id 不含 bar 所以抓不到」的问题。</p>
	 *
	 * <p>撤销：改成 {@code false}（或删掉 JsonSelectSkinObjectLoader 里的调用）。</p>
	 */
	private static final boolean PIN_SELECT_SONGLIST = true;

	/**
	 * 已钉 Nearest 的纹理（渲染期 {@code setFilter} 会查这里）。用 {@link WeakHashMap} 做键集合：
	 * 纹理被 dispose / GC 后条目自动消失，反复切皮肤也不会泄漏 Texture 引用。
	 */
	private static final Set<Texture> PINNED_TEXTURES =
			Collections.newSetFromMap(new WeakHashMap<Texture, Boolean>());

	// ─────────── ⑤ 调试开关 ───────────
	/** true：皮肤加载时把每个 id 的判定结果打到 logcat（`adb logcat | grep SkinFilter`）。 */
	private static final boolean DEBUG_LOG = false;

	private SkinTextureFilterPolicy() {
	}

	/**
	 * 按 id 应用策略。调用点：{@code setDestination}（{@code dstfilter} 已赋值）之后、
	 * {@code skin.add(obj)} 之前。
	 *
	 * @param obj 刚构建完的皮肤对象（可为 null）
	 * @param id  皮肤 json 的 dst id（可为 null / 数字字符串）
	 */
	public static void apply(SkinObject obj, String id) {
		// 每次皮肤加载打一行构建指纹（只打第一行），用于确认设备跑的是哪一版策略。
		if (!buildReported) {
			buildReported = true;
			System.out.println("[SkinFilter] " + BUILD_TAG);
		}
		if (obj == null || id == null) {
			return;
		}
		final String lid = id.toLowerCase();

		if (obj instanceof SkinText) {
			applyText(obj, lid);
		} else {
			applyObject(obj, lid);
		}
	}

	// ──────────────────── 非文字对象（图像 / 数字 / 图形…）────────────────────

	/** 除文字外的全部皮肤对象（画纹理都走 {@code SkinObject} 的 helper，读的正是 {@code imageType}）。 */
	private static void applyObject(SkinObject obj, String idLower) {
		final boolean toNearest = matches(idLower, NEAREST_PREFIXES);
		if (toNearest) {
			setNearest(obj);
		} else {
			setLinear(obj);
		}
		if (DEBUG_LOG) {
			System.out.println("[SkinFilter] " + idLower + " (" + obj.getClass().getSimpleName() + ") -> "
					+ (toNearest ? "NEAREST" : "LINEAR"));
		}
	}

	/** 钉 Nearest：盖掉皮肤声明 + 阻止 renderer 升级 + 把纹理真设回 Nearest。 */
	private static void setNearest(SkinObject obj) {
		// 1) 盖掉皮肤 json 可能写的 "filter":1（dstfilter != 0 会被 renderer 再翻成 BILINEAR）
		obj.setFilter(0);
		// 2) renderer 端：type 非 LINEAR 就不会去升纹理
		obj.setImageType(Skin.SkinObjectRenderer.TYPE_NORMAL);
		// 3) 纹理端：Linear 是纹理级状态且 renderer 只升不降，必须在这里设回去
		if (obj instanceof SkinImage) {
			forceNearestTexture((SkinImage) obj);
		}
	}

	/** 升 Linear：只动「默认图」；动画（SkinSourceMovie）的 type 由 SkinImage.draw 自己管。 */
	private static void setLinear(SkinObject obj) {
		if (obj.getImageType() == Skin.SkinObjectRenderer.TYPE_NORMAL) {
			obj.setImageType(Skin.SkinObjectRenderer.TYPE_LINEAR);
		}
	}

	/**
	 * 把该图像的纹理直接设成 Nearest —— <b>「钉 Nearest」真正生效的那一步</b>。
	 * 纹理可能尚未加载 / 依赖 state（动画、ref 图），失败就放手：
	 * {@code imageType == TYPE_NORMAL} 已足够让 renderer 不再把它升成 Linear。
	 */
	private static void forceNearestTexture(SkinImage image) {
		try {
			final TextureRegion region = image.getImage(0, null);
			final Texture texture = region != null ? region.getTexture() : null;
			if (texture != null && (texture.getMinFilter() != TextureFilter.Nearest
					|| texture.getMagFilter() != TextureFilter.Nearest)) {
				texture.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
			}
		} catch (Throwable t) {
			// 忽略：延迟加载 / 需要 MainState 的图会在这里抛，不影响 type 层面的策略。
		}
	}

	// ────────────────────────────── 文字 ──────────────────────────────

	private static void applyText(SkinObject obj, String idLower) {
		if (matches(idLower, NEAREST_TEXT_PREFIXES)) {
			obj.setFilter(0);
			if (DEBUG_LOG) {
				System.out.println("[SkinFilter] " + idLower + " -> NEAREST (text)");
			}
			return;
		}

		// SkinTextFont：draw 每帧按 getFilter() 自选 LINEAR / NORMAL，与 imageType 无关。
		// 🔴 该 draw 末尾会还原 NORMAL（兜住「text 前驱」的 type 泄漏），不要删。
		// SkinTextBitmap（.fnt 位图字体）2026-09-25 起 draw 也改读 getFilter()
		// （上游硬编码的 TYPE_BILINEAR 是纯透传 = 永远 Nearest，等于策略失效）；
		// distance-field 类型的位图字体仍走 DISTANCE_FIELD 分支，不受影响。
		if (obj instanceof SkinTextFont || obj instanceof SkinTextBitmap) {
			obj.setFilter(1);
			if (DEBUG_LOG) {
				System.out.println("[SkinFilter] " + idLower + " (" + obj.getClass().getSimpleName()
						+ ") -> LINEAR (text)");
			}
		}
		// SkinTextImage（逐字符切帧，Linear 必然字符间渗色）不碰 —— 它按设计恒 Nearest。
	}

	// ────────────────────────────── 工具 ──────────────────────────────

	private static boolean matches(String lowercaseId, String[] prefixes) {
		for (String prefix : prefixes) {
			if (lowercaseId.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	// ────────────── 纹理级钉子（③ 源图名单 / ④ 选曲条 共用）──────────────

	/** 皮肤加载期回调：源图刚建成 Texture 时调用（挂载点 {@code JSONSkinLoader.getSource()}）。 */
	public static void onTextureLoaded(String path, Object data) {
		if (data == null || !(data instanceof Texture) || path == null) {
			return;
		}
		if (!matchesSource(path)) {
			return;
		}
		pinNearest((Texture) data, path);
	}

	/**
	 * 把一张纹理钉成 Nearest 并登记 —— 「钉 Nearest」的<b>唯一底层实现</b>（③ ④ 都走这里）。
	 * 登记后渲染期 {@code SkinObjectRenderer.setFilter(Texture)} 会短路，同纹理的 Linear
	 * 兄弟对象再也升不回去。幂等：同一张纹理重复钉只打一次日志。
	 *
	 * @param tex 要钉的纹理（{@code null} 直接忽略）
	 * @param tag 日志标签（源图路径 / 皮肤 id），便于 {@code grep SkinFilter} 核对
	 */
	public static void pinNearest(Texture tex, String tag) {
		if (tex == null) {
			return;
		}
		// libGDX 的 Texture 默认就是 Nearest，这一步主要是表达意图 + 防 useMipMaps 等情形。
		if (tex.getMinFilter() != TextureFilter.Nearest || tex.getMagFilter() != TextureFilter.Nearest) {
			tex.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
		}
		if (PINNED_TEXTURES.add(tex)) {
			System.out.println("[SkinFilter] pinned texture (Nearest): " + tag);
		}
	}

	/** ④ 的对外 API：受 {@link #PIN_SELECT_SONGLIST} 总开关控制（挂载点 JsonSelectSkinObjectLoader）。 */
	public static void pinSonglist(Texture tex, String tag) {
		if (!PIN_SELECT_SONGLIST) {
			return;
		}
		pinNearest(tex, "songlist:" + tag);
	}

	/** 渲染期查询：该纹理是否已被钉成 Nearest。命中时 {@code setFilter} 直接 return（不升级）。 */
	public static boolean isPinned(Texture texture) {
		return texture != null && PINNED_TEXTURES.contains(texture);
	}

	/** 路径归一化（反斜杠 → 正斜杠、小写）后按后缀匹配源图名单。 */
	private static boolean matchesSource(String path) {
		final String p = path.replace('\\', '/').toLowerCase();
		for (String suffix : NEAREST_SOURCES) {
			if (p.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}
}
