package bms.player.beatoraja.config;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import static bms.player.beatoraja.skin.SkinProperty.*;

import bms.model.Mode;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.PlayStateValues;
import bms.player.beatoraja.SkinConfig;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.play.PlaySkin;
import bms.player.beatoraja.play.PreviewNoteLayer;
import bms.player.beatoraja.play.PreviewPlayValues;
import bms.player.beatoraja.play.SkinNote;
import bms.player.beatoraja.skin.*;

import java.util.logging.Logger;

/**
 * スキンコンフィグ
 *
 * @author excln
 */
public class SkinConfiguration extends MainState {

	/** 参数调整后等待多久（ms）才真正重建预览，用于合并连按。 */
	private static final long PREVIEW_RELOAD_DELAY_MS = 120;

	private SkinConfigurationSkin skin;
	/**
	 * 皮肤清单与参数清单的唯一实现（见 {@link SkinAdjustModel}），与 AUTOPLAY 的
	 * 皮肤调整窗口共用同一个实例。本类只保留「宿主界面」的部分：
	 * 滚动窗口位置、预览重建、SkinHistory 的落盘时机。
	 */
	private final SkinAdjustModel model;
	private int customOptionOffset;
	private int customOptionOffsetMax;
	private Skin selectedSkin;
	/**
	 * 预览里判定 / 连击 / 量表的合成取值来源，只在成功加载 play 皮肤预览时存在
	 * （见 {@link #getPlayStateValues()} 与 {@link PreviewPlayValues}）。
	 */
	private PreviewPlayValues previewPlayValues;

	/**
	 * 参数调整（Lane Size、Scratch Side 之类）请求预览重建的时刻，-1 表示无请求。
	 * 连续调整时只在用户停手后重建一次，否则每按一下都要重载整张皮肤。
	 */
	private long previewReloadRequestTime = -1;

	public SkinConfiguration(MainController main, SkinAdjustModel model) {
		super(main);
		this.model = model;
		// 参数被写入 SkinConfig 时重建预览（去抖统一由 render() 处理）
		model.addChangeListener(this::reloadSelectedSkinPreview);
	}

	/** 共用的皮肤调整模型（AUTOPLAY 的皮肤调整窗口用的是同一个实例） */
	public SkinAdjustModel getAdjustModel() {
		return model;
	}

	public void create() {
		loadSkin(SkinType.SKIN_SELECT);
		skin = (SkinConfigurationSkin) getSkin();
		model.ensureScanned();
		if (skin != null) {
			changeSkinType(SkinType.getSkinTypeById(skin.getDefaultSkinType()));
		} else {
			// 皮肤加载失败时使用默认皮肤类型
			changeSkinType(SkinType.PLAY_7KEYS);
		}
	}

	public void render() {

		// 参数调整后的预览重建：用户停手一小段时间后再统一重载一次，避免连按/长按时
		// 每帧都做一次全量皮肤加载。
		if (previewReloadRequestTime > 0
				&& System.currentTimeMillis() - previewReloadRequestTime >= PREVIEW_RELOAD_DELAY_MS) {
			previewReloadRequestTime = -1;
			loadSelectedSkinPreview();
		}

		if (main.getInputProcessor().isControlKeyPressed(ControlKeys.ESCAPE)) {
			main.saveConfig();
			main.changeState(MainStateType.MUSICSELECT);
		}
	}

	public void input() {
		BMSPlayerInputProcessor input = main.getInputProcessor();
		int mov = -input.getScroll();
		input.resetScroll();
		if (mov != 0 && model.getItemCount() > 0) {
			customOptionOffset = Math.max(0, Math.min(customOptionOffsetMax, customOptionOffset + mov));
		}
	}

	public SkinType getSkinType() {
		return model.getType();
	}

	public float getSkinSelectPosition() {
		return (float)customOptionOffset / customOptionOffsetMax;
	}

	public void setSkinSelectPosition(float value) {
		if (value >= 0 && value < 1) {
			customOptionOffset = (int) (customOptionOffsetMax * value);
		}
	}

	public String getCategoryName(int index) {
		SkinAdjustModel.ItemBase item = model.getItem(index + customOptionOffset);
		return (item != null) ? item.getCategoryName() : "";
	}

	public String getDisplayValue(int index) {
		SkinAdjustModel.ItemBase item = model.getItem(index + customOptionOffset);
		return (item != null) ? item.getDisplayValue() : "";
	}

	public SkinHeader getSelectedSkinHeader() {
		return model.getSelectedSkinHeader();
	}

	/**
	 * 当前选中皮肤的实体，供实时预览（{@link SkinPreview}）渲染。
	 * 只有 {@link SkinType#SKIN_SELECT}（宿主界面自身）恒为 null。
	 */
	public Skin getSelectedSkin() {
		return selectedSkin;
	}

	public void executeEvent(int id, int arg1, int arg2) {
		switch (id) {
		case BUTTON_CHANGE_SKIN:
			if (arg1 >= 0) {
				setNextSkin();
			} else {
				setPrevSkin();
			}
			break;
		default:
			if (SkinPropertyMapper.isSkinCustomizeButton(id)) {
				int index = SkinPropertyMapper.getSkinCustomizeIndex(id) + customOptionOffset;
				SkinAdjustModel.ItemBase item = model.getItem(index);
				if (item != null) {
					if (arg1 >= 0) {
						if (item.getvalue() < item.getMax()) {
							item.setValue(item.getvalue() + 1);
						} else {
							item.setValue(item.getMin());
						}
					} else {
						if (item.getvalue() > item.getMin()) {
							item.setValue(item.getvalue() - 1);
						} else {
							item.setValue(item.getMax());
						}
					}
				}
			} else if (SkinPropertyMapper.isSkinSelectTypeId(id)) {
				SkinType t = SkinPropertyMapper.getSkinSelectType(id);
				changeSkinType(t);
			} else {
				super.executeEvent(id, arg1, arg2);
			}
		}
	}

	private void changeSkinType(SkinType type) {
		model.saveSkinHistory();
		model.setType(type);
		Logger.getGlobal().info("SkinConfiguration: changeSkinType to " + model.getType());
		onModelSelectionChanged();
	}

	/**
	 * model 的「选中皮肤 / 参数清单」变化后，刷新宿主界面自己的状态并重建预览。
	 *
	 * <p>清单重建期间每次写值都会通知本类（{@code reloadSelectedSkinPreview}），
	 * 那只会记下一个时刻；这里统一把它清掉并立即加载一次 —— 换皮肤是用户主动操作，
	 * 不该等去抖。</p>
	 */
	private void onModelSelectionChanged() {
		if (model.getSelectedSkinIndex() >= 0 && model.getSelectedSkinHeader() != null) {
			customOptionOffset = 0;
			customOptionOffsetMax = Math.max(0, model.getItemCount() - skin.getCustomPropertyCount());
			previewReloadRequestTime = -1;
			loadSelectedSkinPreview();
		} else {
			customOptionOffset = 0;
			customOptionOffsetMax = 0;
			setSelectedSkin(null);
		}
	}

	private void setNextSkin() { setOtherSkin(1); }

	private void setPrevSkin() { setOtherSkin(-1); }

	private void setOtherSkin(int indexDiff) {
		if (model.cycleSkin(indexDiff)) {
			onModelSelectionChanged();
		}
	}

	// selectSkin / saveSkinHistory / updateCustom* / setCustom* 已迁到 SkinAdjustModel
	// （皮肤清单与参数清单的唯一实现，与 AUTOPLAY 的皮肤调整窗口共用）。
	// 本类只保留 onModelSelectionChanged() 这一层「接回预览」的适配。

	/**
	 * 加载「当前选中皮肤」的实体，供实时预览渲染。
	 *
	 * <p>关键点是**用当前 config（含用户改过的 properties）加载一份独立的 Skin 实例**，
	 * 而不是复用 play 状态里那张 —— 预览要反映的就是这些 properties（Lane Size、
	 * Scratch Side …）。加载完立即 {@code prepare}，让不满足条件的对象在进入预览前
	 * 就被剔除。</p>
	 */
	private void loadSelectedSkinPreview() {
		// 每次重建都先清掉上一轮的合成取值：它只在「有 play 皮肤预览」时有效
		previewPlayValues = null;
		// SKIN_SELECT 自己就是宿主界面，不预览自己；
		// RESULT / COURSE_RESULT 上游（beatoraja-master）原本也在此排除，本分支 2026-09-20 放开：
		// 三条加载链（JSON / Lua / LR2，见 SkinLoader.load）都支持这两个类型，预览里拿不到
		// 游玩数据也只是静默少画几件东西（音符层与合成判定/量表已按 instanceof PlaySkin 跳过），
		// 加载失败同样落到 setSelectedSkin(null)。退场动画（-110 全屏黑图）由
		// SkinPreview.resolvePreviewTime() 钳制，不会一进预览就变黑。
		if (model.getSelectedSkinHeader() == null || model.getConfig() == null || model.getType() == SkinType.SKIN_SELECT) {
			setSelectedSkin(null);
			return;
		}

		SkinConfig previewConfig = new SkinConfig(model.getConfig().getPath());
		previewConfig.setProperties(model.getConfig().getProperties());
		if (!previewConfig.validate()) {
			setSelectedSkin(null);
			return;
		}
		Skin preview = null;
		try {
			preview = SkinLoader.load(this, model.getType(), previewConfig);
			if (preview != null) {
				// 先挂音符层再 prepare：让它走一遍标准的 load()/校验流程
				attachPreviewNoteLayer(preview);
				// 判定 / 量表的合成取值也必须在 prepare 之前就位 ——
				// SkinJudge / SkinGauge 在首次 prepare 里就会向 getPlayStateValues() 取值
				setupPreviewPlayValues(preview);
				preview.prepare(this);
			}
		} catch (Throwable e) {
			Logger.getGlobal().warning("皮肤预览加载失败 : " + e);
			previewPlayValues = null;
			preview = null;
		}
		setSelectedSkin(preview);
	}

	/**
	 * 给预览皮肤挂上「自绘音符层」，插在 {@code SkinNote} 之后（同一绘制层）。
	 *
	 * <p>预览环境里没有 play 会话，{@code SkinNote} 自己画不出音符（它要求 state 是
	 * {@code BMSPlayer}，见 {@link PreviewNoteLayer} 的说明）。这里补一层合成动画，
	 * 让预览能反映这张皮肤在游玩时的样子；插在 SkinNote 之后是为了层级与真实音符一致
	 * （不会盖住 lane cover 之类后绘制的元素）。</p>
	 */
	private void attachPreviewNoteLayer(Skin preview) {
		// 非 play 皮肤（选曲/结果等）本就没有轨道，静默跳过
		if (!(preview instanceof PlaySkin)) {
			return;
		}
		final Mode mode = model.getType() != null ? model.getType().getMode() : null;
		if (mode == null) {
			return;
		}
		for (SkinObject obj : preview.getAllSkinObjects()) {
			if (obj instanceof SkinNote) {
				preview.insertSkinObjectAfter(obj,
						new PreviewNoteLayer((SkinNote) obj, (PlaySkin) preview, mode));
				return;
			}
		}
	}

	/**
	 * 给预览挂上「合成游玩态数值」：判定 / 连击 / 量表。
	 *
	 * <p>前提与音符层一致 —— 这三样只有 play 皮肤才有，其它皮肤静默跳过。
	 * 必须在 {@code preview.prepare()} **之前**调用，否则首次 prepare 时取不到值。</p>
	 */
	private void setupPreviewPlayValues(Skin preview) {
		if (!(preview instanceof PlaySkin)) {
			return;
		}
		final Mode mode = model.getType() != null ? model.getType().getMode() : null;
		if (mode == null) {
			return;
		}
		int gaugeType = 0;
		try {
			gaugeType = resource.getPlayerConfig().getGauge();
		} catch (Throwable e) {
			// 拿不到就用默认值（0 = ASSIST EASY）
		}
		previewPlayValues = new PreviewPlayValues(this, mode, gaugeType);
	}

	/**
	 * 预览里判定 / 连击 / 量表的取值来源。
	 *
	 * <p>预览的 state 就是本类，而 {@code SkinJudge} / {@code SkinGauge} 统一从这个入口
	 * 取游玩态数值 —— 真游玩时那边拿到的是 {@code BMSPlayer}。入口是在
	 * {@link MainState} 上声明的，见 {@link PlayStateValues}。</p>
	 */
	@Override
	public PlayStateValues getPlayStateValues() {
		return previewPlayValues;
	}

	/**
	 * 参数被调整后的预览重建请求。
	 *
	 * <p>不做立即重建：改一个参数就要全量重载皮肤，而用户通常是连按（还会长按），
	 * 逐次重建会把界面拖死。这里只记下时刻，由 {@link #render()} 在停手后统一重建一次。</p>
	 */
	private void reloadSelectedSkinPreview() {
		if (model.getSelectedSkinHeader() == null) {
			return;
		}
		previewReloadRequestTime = System.currentTimeMillis();
	}

	private void setSelectedSkin(Skin skin) {
		if (selectedSkin == skin) {
			return;
		}
		if (selectedSkin != null) {
			selectedSkin.dispose();
		}
		selectedSkin = skin;
	}

	@Override
	public void dispose() {
		// 预览皮肤是独立加载的实例，不随界面皮肤的 dispose 一起释放，必须在这里显式清掉，
		// 否则每进一次皮肤选择界面就泄漏一张皮肤的全部纹理引用。
		setSelectedSkin(null);
		super.dispose();
	}

}
