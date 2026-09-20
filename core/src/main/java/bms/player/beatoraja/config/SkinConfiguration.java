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
import bms.player.beatoraja.skin.json.JSONSkinLoader;
import bms.player.beatoraja.skin.lr2.LR2SkinHeaderLoader;
import bms.player.beatoraja.skin.lua.LuaSkinLoader;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
	private SkinType type;
	private SkinConfig config;
	private List<SkinHeader> allSkins;
	private List<SkinHeader> availableSkins;
	private int selectedSkinIndex;
	private SkinHeader selectedSkinHeader;
	private List<CustomItemBase> customOptions;
	private int customOptionOffset;
	private int customOptionOffsetMax;
	private Skin selectedSkin;
	/**
	 * 预览里判定 / 连击 / 量表的合成取值来源，只在成功加载 play 皮肤预览时存在
	 * （见 {@link #getPlayStateValues()} 与 {@link PreviewPlayValues}）。
	 */
	private PreviewPlayValues previewPlayValues;
	private PlayerConfig player;

	/**
	 * 预览重建抑制开关：批量初始化（{@link #selectSkin}）期间会连续调用多个
	 * setCustom*，每个都带一次全量皮肤加载，先抑制掉、结束时统一加载一次。
	 */
	private boolean previewReloadSuppressed;
	/**
	 * 参数调整（Lane Size、Scratch Side 之类）请求预览重建的时刻，-1 表示无请求。
	 * 连续调整时只在用户停手后重建一次，否则每按一下都要重载整张皮肤。
	 */
	private long previewReloadRequestTime = -1;

	public SkinConfiguration(MainController main, PlayerConfig player) {
		super(main);
		this.player = player;
	}

	public void create() {
		loadSkin(SkinType.SKIN_SELECT);
		skin = (SkinConfigurationSkin) getSkin();
		loadAllSkins();
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
		if (mov != 0 && customOptions != null) {
			customOptionOffset = Math.max(0, Math.min(customOptionOffsetMax, customOptionOffset + mov));
		}
	}

	public SkinType getSkinType() {
		return type;
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
		if (customOptions != null && index + customOptionOffset < customOptions.size()) {
			return customOptions.get(index + customOptionOffset).getCategoryName();
		}
		return "";
	}

	public String getDisplayValue(int index) {
		if (customOptions != null && index + customOptionOffset < customOptions.size()) {
			return customOptions.get(index + customOptionOffset).getDisplayValue();
		}
		return "";
	}

	public SkinHeader getSelectedSkinHeader() {
		return selectedSkinHeader;
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
				if (customOptions != null && index < customOptions.size()) {
					CustomItemBase item = customOptions.get(index);
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
		saveSkinHistory();
		this.type = type != null ? type : SkinType.PLAY_7KEYS;
		java.util.logging.Logger.getGlobal().info("SkinConfiguration: changeSkinType to " + this.type);
		this.config = main.getPlayerConfig().getSkin()[this.type.getId()];
		availableSkins = new ArrayList<>();
		for (SkinHeader header : allSkins) {
			if (header.getSkinType() == type) {
				availableSkins.add(header);
			}
		}
		java.util.logging.Logger.getGlobal().info("SkinConfiguration: availableSkins for type " + this.type + " = " + availableSkins.size());
		for (int i = 0; i < availableSkins.size(); i++) {
			java.util.logging.Logger.getGlobal().info("SkinConfiguration:   [" + i + "] " + availableSkins.get(i).getName() + " - " + availableSkins.get(i).getPath());
		}
		if (config != null && this.config.getPath() != null && !config.getPath().isEmpty()) {
			final String cfgPath = normalizeSkinPath(config.getPath());
			int index = -1;
			for (int i = 0; i < availableSkins.size(); i++) {
				SkinHeader header = availableSkins.get(i);
				if (header != null && cfgPath.equals(normalizeSkinPath(header.getPath()))) {
					index = i;
					break;
				}
			}
			selectSkin(index);
		} else {
			selectSkin(-1);
		}
	}

	private void setNextSkin() { setOtherSkin(1); }

	private void setPrevSkin() { setOtherSkin(-1); }

	private void setOtherSkin(int indexDiff) {
		if (availableSkins.isEmpty()) {
			Logger.getGlobal().warning("利用可能なスキンがありません");
			return;
		}

		if (config == null) {
			config = new SkinConfig();
			main.getPlayerConfig().getSkin()[type.getId()] = config;
		} else {
			saveSkinHistory();
		}

		int index = selectedSkinIndex;
		if (index < 0) {
			// 之前没有选中的皮肤（如首次进入或 config 路径不在当前 availableSkins 中），
			// 重新尝试按 config.path 匹配一次，避免永远跳到列表首位。
			final String cfgPath = config.getPath() != null ? normalizeSkinPath(config.getPath()) : null;
			if (cfgPath != null) {
				for (int i = 0; i < availableSkins.size(); i++) {
					if (cfgPath.equals(normalizeSkinPath(availableSkins.get(i).getPath()))) {
						index = i;
						break;
					}
				}
			}
			if (index < 0) {
				index = 0;
			}
		} else {
			index = (index + indexDiff + availableSkins.size()) % availableSkins.size();
		}
		config.setPath(availableSkins.get(index).getPath());
		config.setProperties(new SkinConfig.Property());
		selectSkin(index);
	}

	private void selectSkin(int index) {
		selectedSkinIndex = index;
		if (index >= 0) {
			selectedSkinHeader = availableSkins.get(selectedSkinIndex);
			customOptions = new ArrayList<>();
			customOptionOffset = 0;
			// Loading properties from SkinHistory
			for (SkinConfig skinc : player.getSkinHistory()) {
				if (skinc.getPath().equals(selectedSkinHeader.getPath().toString())) {
					config.setProperties(skinc.getProperties());
					break;
				}
			}
			if (config.getProperties() == null) {
				config.setProperties(new SkinConfig.Property());
			}
			// updateCustom* 内部会调 setCustomOption/setFilePath/setCustomOffset，那里各自
			// 带一次预览重载；批量初始化阶段先抑制，结束时统一加载一次。
			previewReloadSuppressed = true;
			try {
				updateCustomOptions();
				updateCustomFiles();
				updateCustomOffsets();
			} finally {
				previewReloadSuppressed = false;
			}
			customOptionOffsetMax = Math.max(0, customOptions.size() - skin.getCustomPropertyCount());
			// 切换皮肤是用户主动操作，立即出预览（不走去抖）
			previewReloadRequestTime = -1;
			loadSelectedSkinPreview();
		} else {
			selectedSkinHeader = null;
			customOptions = null;
			setSelectedSkin(null);
		}
	}

	private void saveSkinHistory() {
		if(config != null && config.getPath() != null) {
			int index = -1;
			for (int i = 0; i < player.getSkinHistory().length; i++) {
				if (player.getSkinHistory()[i].getPath().equals(config.getPath())) {
					index = i;
					break;
				}
			}

			SkinConfig sc = new SkinConfig();
			sc.setPath(config.getPath());
			sc.setProperties(config.getProperties());
			if (index >= 0) {
				player.getSkinHistory()[index] = sc;
			} else {
				SkinConfig[] history = Arrays.copyOf(player.getSkinHistory(), player.getSkinHistory().length + 1);
				history[history.length - 1] = sc;
				player.setSkinHistory(history);
			}
		}
	}

	private void updateCustomOptions() {
		for (SkinHeader.CustomOption option : selectedSkinHeader.getCustomOptions()) {
			int selection = -1;
			for(SkinConfig.Option o : config.getProperties().getOption()) {
				if (o.name.equals(option.name)) {
					int i = o.value;
					if(i != OPTION_RANDOM_VALUE) {
						for (int j = 0; j < option.option.length; j++) {
							if (option.option[j] == i) {
								selection = j;
								break;
							}
						}
					} else {
						selection = option.option.length;
					}
					break;
				}
			}
			if (selection < 0) {
				if (option.def != null) {
					for (int j = 0; j < option.option.length; j++) {
						if (option.contents[j].equals(option.def)) {
							selection = j;
							break;
						}
					}
				}
				if (selection < 0) {
					selection = 0;
				}
				setCustomOption(option.name, option.option[selection]);
			}
			String[] contentsAddedRandom = new String[option.contents.length + 1];
			for(int i = 0; i < option.contents.length; i++) {
				contentsAddedRandom[i] = option.contents[i];
			}
			contentsAddedRandom[option.contents.length] = "Random";
			int[] optionAddedRandom = new int[option.option.length + 1];
			for(int i = 0; i < option.option.length; i++) {
				optionAddedRandom[i] = option.option[i];
			}
			optionAddedRandom[option.option.length] = OPTION_RANDOM_VALUE;

			CustomOptionItem item = new CustomOptionItem(option.name, contentsAddedRandom, optionAddedRandom, selection);
			customOptions.add(item);
		}
	}

	private void updateCustomFiles() {
		for (SkinHeader.CustomFile file : selectedSkinHeader.getCustomFiles()) {
			String nameValue = file.path.substring(file.path.lastIndexOf('/') + 1);
			if(file.path.contains("|")) {
				if(file.path.length() > file.path.lastIndexOf('|') + 1) {
					nameValue = file.path.substring(file.path.lastIndexOf('/') + 1, file.path.indexOf('|')) + file.path.substring(file.path.lastIndexOf('|') + 1);
				} else {
					nameValue = file.path.substring(file.path.lastIndexOf('/') + 1, file.path.indexOf('|'));
				}
			}
			final String name = nameValue;
			final String dirStr = file.path.substring(0, file.path.lastIndexOf('/'));
			File dirpath;
			if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android && !dirStr.startsWith("/")) {
				String root = System.getProperty("beatoraja.root", ".");
				dirpath = new File(root, dirStr);
			} else {
				dirpath = new File(dirStr);
			}
			// 规范化路径，解析 .. 和 . （Android 上含 .. 的路径可能导致 File.exists() 失败）
			try {
				dirpath = dirpath.getCanonicalFile();
			} catch (Exception e) {
				// getCanonicalFile 失败时尝试手动规范化
				dirpath = new File(manualNormalizePath(dirpath.getPath()));
			}

			if (!dirpath.exists()) {
				java.util.logging.Logger.getGlobal().warning("SkinConfiguration: custom file directory does NOT exist: " + dirpath + " (from " + file.path + ")");
				continue;
			}
			try {
				String[] files = dirpath.list(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String filename) {
						String lowerFilename = filename.toLowerCase();
						if (name.contains("|")) {
							String[] patterns = name.split("\\|");
							for (String p : patterns) {
								if (p.startsWith("*")) {
									if (lowerFilename.endsWith(p.substring(1).toLowerCase())) return true;
								} else {
									if (filename.equalsIgnoreCase(p)) return true;
								}
							}
							return false;
						}
						if (name.startsWith("*")) {
							String ext = name.substring(1).toLowerCase();
							return lowerFilename.endsWith(ext);
						}
						return filename.equalsIgnoreCase(name.toLowerCase()) || filename.equalsIgnoreCase(name.toUpperCase());
					}
				});

				List<String> items = new ArrayList<>();
				if (files != null) {
					Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
					for (String filename : files) {
						items.add(filename);
					}
				}
				items.add("Random");
				String selection = null;
				for(SkinConfig.FilePath f : config.getProperties().getFile()) {
					if(f.name.equals(file.name)) {
						selection = f.path;
						break;
					}
				}
				if (selection == null && file.def != null) {
					// デフォルト値のファイル名またはそれに拡張子を付けたものが存在すれば使用する
					for (String item : items) {
						if (item.equalsIgnoreCase(file.def)) {
							selection = item;
							break;
						}
						int point = item.lastIndexOf('.');
						if (point != -1 && item.substring(0, point).equalsIgnoreCase(file.def)) {
							selection = item;
							break;
						}
					}
				}
				if (selection == null) {
					// default.png 優先選択逻辑
					for (String item : items) {
						if (item.equalsIgnoreCase("default.png") || item.equalsIgnoreCase("default.bmp")) {
							selection = item;
							break;
						}
					}
				}
				if (selection == null) {
					selection = items.get(0);
				}
				setFilePath(file.name, selection);
				CustomFileItem item = new CustomFileItem(file.name, items, selection);
				customOptions.add(item);
			} catch (Throwable e) {
				e.printStackTrace();
			}
		}
	}

	private void updateCustomOffsets() {
		for (SkinHeader.CustomOffset option : selectedSkinHeader.getCustomOffsets()) {
			final String[] values = {"x","y","w","h","r","a"};
			boolean[] b = new boolean[] { option.x, option.y, option.w, option.h, option.r, option.a };
			SkinConfig.Offset ofs = null;
			for(SkinConfig.Offset o : config.getProperties().getOffset()) {
				if(o.name.equals(option.name)) {
					ofs = o;
					break;
				}
			}
			if (ofs == null) {
				int length = config.getProperties().getOffset().length;
				SkinConfig.Offset[] offsets = Arrays.copyOf(config.getProperties().getOffset(), length + 1);
				offsets[length] = new SkinConfig.Offset();
				offsets[length].name = option.name;
				ofs = offsets[length];
				config.getProperties().setOffset(offsets);
			}
			int[] v = new int[] { ofs.x, ofs.y, ofs.w, ofs.h, ofs.r, ofs.a };
			for(int i = 0; i < 6; i++) {
				if(b[i]) {
					CustomOffsetItem item = new CustomOffsetItem(option.name, values[i], i, -9999, 9999, v[i]);
					customOptions.add(item);
				}
			}
		}
	}

	private void setCustomOption(String name, int value) {
		for (SkinConfig.Option option : config.getProperties().getOption()) {
			if (option.name.equals(name)) {
				option.value = value;
				reloadSelectedSkinPreview();
				return;
			}
		}
		// 既存のコンフィグデータに存在しない場合
		int length = config.getProperties().getOption().length;
		SkinConfig.Option[] options = Arrays.copyOf(config.getProperties().getOption(), length + 1);
		options[length] = new SkinConfig.Option();
		options[length].name = name;
		options[length].value = value;
		config.getProperties().setOption(options);
		reloadSelectedSkinPreview();
	}

	private void setFilePath(String name, String path) {
		for (SkinConfig.FilePath f : config.getProperties().getFile()) {
			if(f.name.equals(name)) {
				f.path = path;
				reloadSelectedSkinPreview();
				return;
			}
		}
		// 既存のコンフィグデータに存在しない場合
		int length = config.getProperties().getFile().length;
		SkinConfig.FilePath[] paths = Arrays.copyOf(config.getProperties().getFile(), length + 1);
		paths[length] = new SkinConfig.FilePath();
		paths[length].name = name;
		paths[length].path = path;
		config.getProperties().setFile(paths);
		reloadSelectedSkinPreview();
	}

	private void setCustomOffset(String name, int kind, int value) {
		for(SkinConfig.Offset offset : config.getProperties().getOffset()) {
			if(offset.name.equals(name)) {
				setOffset(offset, kind, value);
				reloadSelectedSkinPreview();
				return;
			}
		}
		// 既存のコンフィグデータに存在しない場合
		int length = config.getProperties().getOffset().length;
		SkinConfig.Offset[] offsets = Arrays.copyOf(config.getProperties().getOffset(), length + 1);
		offsets[length] = new SkinConfig.Offset();
		offsets[length].name = name;
		setOffset(offsets[length], kind, value);
		config.getProperties().setOffset(offsets);
		reloadSelectedSkinPreview();
	}

	private void setOffset(SkinConfig.Offset offset, int kind, int value) {
		switch (kind) {
		case 0:
			offset.x = value;
			break;
		case 1:
			offset.y = value;
			break;
		case 2:
			offset.w = value;
			break;
		case 3:
			offset.h = value;
			break;
		case 4:
			offset.r = value;
			break;
		case 5:
			offset.a = value;
			break;
		}
	}

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
		if (selectedSkinHeader == null || config == null || type == SkinType.SKIN_SELECT) {
			setSelectedSkin(null);
			return;
		}

		SkinConfig previewConfig = new SkinConfig(config.getPath());
		previewConfig.setProperties(config.getProperties());
		if (!previewConfig.validate()) {
			setSelectedSkin(null);
			return;
		}
		Skin preview = null;
		try {
			preview = SkinLoader.load(this, type, previewConfig);
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
		final Mode mode = type != null ? type.getMode() : null;
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
		final Mode mode = type != null ? type.getMode() : null;
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
		if (previewReloadSuppressed || selectedSkinHeader == null) {
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

	private void loadAllSkins() {
		java.util.logging.Logger.getGlobal().info("SkinConfiguration: loadAllSkins starting...");
		allSkins = new ArrayList<SkinHeader>();
		List<File> skinPaths = new ArrayList<>();

		// Android平台适配：使用正确的皮肤目录路径
		File skinDir;
		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			// Android上使用绝对路径
			String root = System.getProperty("beatoraja.root", ".");
			skinDir = new File(root, "skin");
			java.util.logging.Logger.getGlobal().info("SkinConfiguration: Android detected, root=" + root + ", skinDir=" + skinDir);
		} else {
			// 桌面端使用相对路径
			skinDir = new File("skin");
			java.util.logging.Logger.getGlobal().info("SkinConfiguration: Desktop detected, skinDir=" + skinDir);
		}

		// 检查skinDir是否存在
		if (!skinDir.exists()) {
			java.util.logging.Logger.getGlobal().severe("SkinConfiguration: skin directory does NOT exist: " + skinDir);
		} else {
			java.util.logging.Logger.getGlobal().info("SkinConfiguration: skin directory exists: " + skinDir);
		}

		try {
			scanSkins(skinDir, skinPaths);
			java.util.logging.Logger.getGlobal().info("SkinConfiguration: scanSkins found " + skinPaths.size() + " skin files");

			for (File path : skinPaths) {
				java.util.logging.Logger.getGlobal().info("SkinConfiguration: processing: " + path);
				try {
					String pathString = path.toString().toLowerCase();
					if (pathString.endsWith(".json")) {
						JSONSkinLoader loader = new JSONSkinLoader();
						SkinHeader header = loader.loadHeader(path);
						if (header != null) {
							allSkins.add(header);
							java.util.logging.Logger.getGlobal().info("SkinConfiguration: added JSON skin: " + header.getName() + ", type=" + header.getSkinType());
						} else {
							java.util.logging.Logger.getGlobal().warning("SkinConfiguration: loadHeader returned null for JSON: " + path);
						}
					} else if (pathString.endsWith(".luaskin")) {
						LuaSkinLoader loader = new LuaSkinLoader();
						SkinHeader header = loader.loadHeader(path);
						if (header != null) {
							allSkins.add(header);
							java.util.logging.Logger.getGlobal().info("SkinConfiguration: added Lua skin: " + header.getName() + ", type=" + header.getSkinType());
						} else {
							java.util.logging.Logger.getGlobal().warning("SkinConfiguration: loadHeader returned null for Lua: " + path);
						}
					} else {
						LR2SkinHeaderLoader loader = new LR2SkinHeaderLoader(main.getConfig());
						try {
							SkinHeader header = loader.loadSkin(path, null);
							allSkins.add(header);
							java.util.logging.Logger.getGlobal().info("SkinConfiguration: added LR2 skin: " + header.getName() + ", type=" + header.getSkinType());
							// 7/14key skinは5/10keyにも加える
							if(header.getType() == SkinHeader.TYPE_LR2SKIN &&
									(header.getSkinType() == SkinType.PLAY_7KEYS || header.getSkinType() == SkinType.PLAY_14KEYS)) {
								header = loader.loadSkin(path, null);

								if(header.getSkinType() == SkinType.PLAY_7KEYS && !header.getName().toLowerCase().contains("7key")) {
									header.setName(header.getName() + " (7KEYS) ");
								} else if(header.getSkinType() == SkinType.PLAY_14KEYS && !header.getName().toLowerCase().contains("14key")) {
									header.setName(header.getName() + " (14KEYS) ");
								}
								header.setSkinType(header.getSkinType() == SkinType.PLAY_7KEYS ? SkinType.PLAY_5KEYS : SkinType.PLAY_10KEYS);
								allSkins.add(header);
							}

						} catch (IOException e) {
							java.util.logging.Logger.getGlobal().warning("SkinConfiguration: failed to load LR2 skin: " + path + ", error=" + e.getMessage());
							e.printStackTrace();
						}
					}
				} catch (Throwable t) {
					java.util.logging.Logger.getGlobal().severe("SkinConfiguration: failed to process skin: " + path + ", error=" + t.getMessage());
					t.printStackTrace();
				}
			}
		} catch (Throwable t) {
			java.util.logging.Logger.getGlobal().severe("SkinConfiguration: loadAllSkins failed with exception: " + t.getMessage());
			t.printStackTrace();
		}
		java.util.logging.Logger.getGlobal().info("SkinConfiguration: total skins loaded: " + allSkins.size());
		for (int i = 0; i < allSkins.size(); i++) {
			SkinHeader h = allSkins.get(i);
			java.util.logging.Logger.getGlobal().info("SkinConfiguration: allSkins[" + i + "] = " + h.getName() + " (" + h.getSkinType() + ") - " + h.getPath());
		}
	}

	private void scanSkins(File path, List<File> paths) {
		if (path.isDirectory()) {
			java.util.logging.Logger.getGlobal().info("SkinConfiguration: scanning directory: " + path);
			File[] sub = path.listFiles();
			if (sub != null) {
				for (File f : sub) {
					scanSkins(f, paths);
				}
			}
		} else if (path.getName().toLowerCase().endsWith(".lr2skin")
				|| path.getName().toLowerCase().endsWith(".luaskin")
				|| path.getName().toLowerCase().endsWith(".json")) {
			paths.add(path);
			java.util.logging.Logger.getGlobal().info("SkinConfiguration: found skin file: " + path);
		}
	}

	@Override
	public void dispose() {
		// 预览皮肤是独立加载的实例，不随界面皮肤的 dispose 一起释放，必须在这里显式清掉，
		// 否则每进一次皮肤选择界面就泄漏一张皮肤的全部纹理引用。
		setSelectedSkin(null);
		super.dispose();
	}

	private abstract static class CustomItemBase {
		protected final String categoryName;
		protected final int min;
		protected final int max;
		protected int value;
		protected String displayValue;

		public CustomItemBase(String categoryName, int count) {
			this.categoryName = categoryName;
			min = 0;
			max = count - 1;
		}

		public CustomItemBase(String categoryName, int min, int max) {
			this.categoryName = categoryName;
			this.min = min;
			this.max = max;
		}

		public String getCategoryName() {
			return categoryName;
		}

		public int getMin() {
			return min;
		}

		public int getMax() {
			return max;
		}

		public int getvalue() {
			return value;
		}

		public String getDisplayValue() {
			return displayValue;
		}

		public abstract void setValue(int value);
	}

	private class CustomOptionItem extends CustomItemBase {
		String[] values;
		int[] options;

		public CustomOptionItem(String name, String[] items, int[] options, int index) {
			super(name, items.length);
			this.values = items;
			this.options = options;
			this.value = index;
			this.displayValue = values[index];
		}

		public void setValue(int i) {
			value = i;
			displayValue = values[value];
			setCustomOption(categoryName, options[value]);
		}
	}

	private class CustomFileItem extends CustomItemBase {
		List<String> displayValues;
		List<String> actualValues;

		public CustomFileItem(String name, List<String> paths, String selection) {
			super(name, paths.size());
			actualValues = paths;
			displayValues = new ArrayList<String>();
			int i=0;
			for (String path : paths) {
				displayValues.add(path); // 修改：直接显示完整文件名，方便识别 default.png
				if (path.equals(selection)) {
					this.value = i;
					this.displayValue = path;
				}
				i++;
			}
		}

		public void setValue(int i) {
			value = i;
			displayValue = actualValues.get(i);
			setFilePath(categoryName, actualValues.get(i));
		}
	}

	private class CustomOffsetItem extends CustomItemBase {
		String offsetName;
		int kind;

		public CustomOffsetItem(String offsetName, String kindName, int kind, int min, int max, int selection) {
			super(offsetName + " - " + kindName, min, max);
			this.offsetName = offsetName;
			this.kind = kind;
			this.value = selection;
			this.displayValue = String.valueOf(this.value);
		}

		public void setValue(int i) {
			value = i;
			displayValue = String.valueOf(value);
			setCustomOffset(offsetName, kind, value);
		}
	}

	/**
	 * 规范化皮肤路径用于匹配：消除绝对/相对路径差异（Android 上 config 默认路径为
	 * "skin/default/select.json"，而 header.getPath() 是 File.toString() 的绝对路径），
	 * 以及大小写差异与多余分隔符。
	 */
	private static String normalizeSkinPath(String path) {
		if (path == null) return null;
		String p = path.replace("\\", "/");
		try {
			File f = new File(p);
			String abs;
			try {
				abs = f.getCanonicalPath();
			} catch (Exception e) {
				abs = f.getAbsolutePath();
			}
			return abs.replace("\\", "/").toLowerCase();
		} catch (Throwable t) {
			return p.toLowerCase();
		}
	}

	/**
	 * 手动规范化路径（不依赖 java.nio.file），兼容 API 21。
	 * 解析 . 和 .. 以及多余的 /
	 */
	private static String manualNormalizePath(String path) {
		String p = path.replace("\\", "/");
		boolean isAbsolute = p.startsWith("/");
		String[] parts = p.split("/");
		java.util.ArrayList<String> stack = new java.util.ArrayList<>();
		for (String part : parts) {
			if (part.isEmpty() || part.equals(".")) continue;
			if (part.equals("..")) {
				if (!stack.isEmpty()) {
					stack.remove(stack.size() - 1);
				}
			} else {
				stack.add(part);
			}
		}
		StringBuilder sb = new StringBuilder();
		if (isAbsolute) sb.append("/");
		for (int i = 0; i < stack.size(); i++) {
			if (i > 0) sb.append("/");
			sb.append(stack.get(i));
		}
		return sb.toString();
	}
}
