package bms.player.beatoraja.skin;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.SkinConfig;
import bms.player.beatoraja.skin.json.JSONSkinLoader;
import bms.player.beatoraja.skin.lr2.LR2SkinHeaderLoader;
import bms.player.beatoraja.skin.lua.LuaSkinLoader;

import static bms.player.beatoraja.skin.SkinProperty.OPTION_RANDOM_VALUE;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * スキン調整モデル —— 皮肤清单与参数清单的<b>唯一实现</b>。
 *
 * <p>两个界面共用同一个实例（由 {@link MainController#getSkinAdjustModel()} 提供）：</p>
 * <ul>
 *   <li>SKINCONFIG 界面（{@code SkinConfiguration}）—— 委托给它，不再自己维护一份；</li>
 *   <li>AUTOPLAY 中的皮肤调整窗口（{@code FloatingMenu}）—— 浏览皮肤 / 读参数 / 改值。</li>
 * </ul>
 *
 * <p>这样「option 的默认项怎么挑、file 的 {@code |} 模式怎么解析、offset 一名最多产出 6 条」
 * 这类逻辑只有一份，不会在两个界面之间分叉。</p>
 *
 * <p>🔴 {@link #ensureScanned()} <b>会执行 Lua 皮肤脚本</b>
 * （{@code LuaSkinLoader.loadHeader} 会建 LuaJ 全局表），必须在渲染线程调用 ——
 * 与 SKINCONFIG 界面现有的行为一致，不要挪到后台线程。</p>
 */
public class SkinAdjustModel {

	/**
	 * 「值被改动」的通知。两个实现方各自决定后续动作：
	 * SKINCONFIG 界面走去抖重载预览，AUTOPLAY 窗口走去抖重载正在演奏的皮肤。
	 */
	public interface ChangeListener {
		/** 某个参数值被写入 SkinConfig 之后调用（也会在批量初始化时被调用）。 */
		void onSkinConfigChanged();
	}

	private final MainController main;
	private final PlayerConfig player;
	/** 多播：SKINCONFIG 界面与 AUTOPLAY 窗口都会注册（后者仅在窗口打开期间注册） */
	private final List<ChangeListener> listeners = new ArrayList<>();

	/** 当前皮肤类型（= 要调整的那一类皮肤的 type） */
	private SkinType type = SkinType.PLAY_7KEYS;
	/** 当前类型的 SkinConfig（= {@code player.getSkin()[type.getId()]}） */
	private SkinConfig config;
	/** 扫描出来的全部皮肤 header */
	private final List<SkinHeader> allSkins = new ArrayList<>();
	/** 按 {@link #type} 过滤后的皮肤 header */
	private final List<SkinHeader> availableSkins = new ArrayList<>();
	private boolean scanned = false;
	private int selectedSkinIndex = -1;
	private SkinHeader selectedSkinHeader;
	/** 扁平的参数清单（option + file + offset 混在一起） */
	private final List<ItemBase> items = new ArrayList<>();

	public SkinAdjustModel(MainController main, PlayerConfig player) {
		this.main = main;
		this.player = player;
	}

	public void addChangeListener(ChangeListener listener) {
		if (listener != null && !listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public void removeChangeListener(ChangeListener listener) {
		listeners.remove(listener);
	}

	private void notifyChanged() {
		// 遍历副本：通知过程中监听器可能注册/注销自己
		for (ChangeListener l : new ArrayList<>(listeners)) {
			l.onSkinConfigChanged();
		}
	}

	// ─────────────────── 皮肤清单 ───────────────────

	public boolean isScanned() {
		return scanned;
	}

	/**
	 * 尚未扫描过则扫一次。见类注释：会执行 Lua 脚本，必须在渲染线程调用。
	 * <p>扫完后调用方需要自己决定 type（{@link #setType(SkinType)}）。</p>
	 */
	public void ensureScanned() {
		if (scanned) {
			return;
		}
		scanned = true;
		loadAllSkins();
	}

	public SkinType getType() {
		return type;
	}

	public SkinConfig getConfig() {
		return config;
	}

	public List<SkinHeader> getSkins() {
		return availableSkins;
	}

	public SkinHeader getSelectedSkinHeader() {
		return selectedSkinHeader;
	}

	public int getSelectedSkinIndex() {
		return selectedSkinIndex;
	}

	/** 参数清单（只读视图；顺序 = option → file → offset） */
	public List<ItemBase> getItems() {
		return items;
	}

	public int getItemCount() {
		return items.size();
	}

	public ItemBase getItem(int index) {
		return (index >= 0 && index < items.size()) ? items.get(index) : null;
	}

	/**
	 * 切换皮肤类型：重建 availableSkins、取出对应的 SkinConfig，并按 config.path 选中。
	 * <p>不落盘、不写 SkinHistory —— 那两件事由调用方（{@code changeSkinType}）负责。</p>
	 */
	public void setType(SkinType type) {
		this.type = (type != null) ? type : SkinType.PLAY_7KEYS;
		this.config = player.getSkin()[this.type.getId()];
		filterAndSelect();
	}

	/**
	 * 选中「某个类型的当前生效皮肤」；config 缺失时先按默认皮肤补一个。
	 *
	 * <p>AUTOPLAY 的皮肤调整窗口开窗时用这个而不是 {@link #setType} ——
	 * 玩家可能从没进过皮肤选择界面，那样 {@code player.getSkin()[type]} 还是 null，
	 * 补一个之后参数清单才有内容可读。</p>
	 */
	public void selectCurrent(SkinType type) {
		this.type = (type != null) ? type : SkinType.PLAY_7KEYS;
		this.config = player.getSkin()[this.type.getId()];
		ensureConfig();
		filterAndSelect();
	}

	private void filterAndSelect() {
		Logger.getGlobal().info("SkinAdjustModel: type = " + this.type);
		availableSkins.clear();
		for (SkinHeader header : allSkins) {
			if (header.getSkinType() == this.type) {
				availableSkins.add(header);
			}
		}
		Logger.getGlobal().info("SkinAdjustModel: availableSkins = " + availableSkins.size());
		int index = -1;
		if (config != null && config.getPath() != null && !config.getPath().isEmpty()) {
			final String cfgPath = normalizeSkinPath(config.getPath());
			for (int i = 0; i < availableSkins.size(); i++) {
				SkinHeader header = availableSkins.get(i);
				if (header != null && cfgPath.equals(normalizeSkinPath(header.getPath()))) {
					index = i;
					break;
				}
			}
			if (index < 0) {
				// 扫描清单里没有这张皮肤（皮肤目录不在标准位置 / 清单为空）。
				// 直接按 config.path 载入 header，让它至少出现在清单首位 ——
				// 这样 AUTOPLAY 窗口在「没扫到任何皮肤」时仍然能读参数。
				SkinHeader header = loadHeader(new File(config.getPath()));
				if (header != null) {
					availableSkins.add(header);
					index = availableSkins.size() - 1;
					Logger.getGlobal().info("SkinAdjustModel: header loaded directly from config.path: "
							+ header.getName());
				}
			}
		}
		selectSkin(index);
	}

	/**
	 * 同类型内循环切换皮肤（{@code indexDiff} = ±1）。
	 * <p>语义与 SKINCONFIG 界面的 {@code setOtherSkin} 一致：先保存旧皮肤的 properties，
	 * 再写入新皮肤路径并清空 properties；{@code config} 为 null 时按需建立一个。</p>
	 *
	 * @return 换成功返回 true（没有可用皮肤时返回 false）
	 */
	public boolean cycleSkin(int indexDiff) {
		if (availableSkins.isEmpty()) {
			Logger.getGlobal().warning("SkinAdjustModel: 利用可能なスキンがありません");
			return false;
		}
		if (availableSkins.size() == 1 && selectedSkinIndex == 0) {
			// 只有一个候选、且已经落在它上面：再翻就是「重载同一张皮肤」，纯浪费一次全量 load。
			// 选曲界面的 MUSIC SELECT 皮肤多半只有一张，开窗后按 < / > 就会踩到。
			// 🔴 注意不能连 selectedSkinIndex == -1 一起挡掉 —— 那是「还没选中」，
			//    按一下应当把这张唯一的皮肤正式选中（建立 config、写入 path 并重建清单）。
			return false;
		}

		if (config == null) {
			config = new SkinConfig();
			player.getSkin()[type.getId()] = config;
		} else {
			saveSkinHistory();
		}

		int index = selectedSkinIndex;
		if (index < 0) {
			// 之前没有选中项（首次进入 / config.path 不在清单里）：按路径再匹配一次，
			// 避免永远跳到列表首位。
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
		notifyChanged();
		return true;
	}

	/**
	 * 按索引选中皮肤并重建参数清单。
	 * <p>索引无效（&lt;0 或越界）时清空选中项与清单。</p>
	 * <p>会读取 {@code player.getSkinHistory()} 里保存过的 properties 并采用之。</p>
	 */
	public void selectSkin(int index) {
		selectedSkinIndex = index;
		items.clear();
		if (index < 0 || index >= availableSkins.size()) {
			selectedSkinHeader = null;
			return;
		}
		selectedSkinHeader = availableSkins.get(index);

		// 从 SkinHistory 恢复这张皮肤上次用过的 properties
		if (config != null) {
			final String path = selectedSkinHeader.getPath() != null
					? selectedSkinHeader.getPath().toString() : null;
			if (path != null) {
				for (SkinConfig skinc : player.getSkinHistory()) {
					if (skinc.getPath() != null && skinc.getPath().equals(path)) {
						config.setProperties(skinc.getProperties());
						break;
					}
				}
			}
			if (config.getProperties() == null) {
				config.setProperties(new SkinConfig.Property());
			}
		}
		rebuildItems();
	}

	/**
	 * 把 config 换成一个「当前实际生效」的配置，保证后续能读写参数。
	 *
	 * <p>用得上这个的场景：玩家从没进过皮肤选择界面，{@code player.getSkin()[type]}
	 * 还是 null。那时 {@code SkinLoader.load} 走的是 {@link SkinConfig.Default} 兜底，
	 * 所以这里照同样的路径建立一个 SkinConfig 并写回 PlayerConfig ——
	 * 这与 {@code setOtherSkin} 的既有做法一致。</p>
	 */
	private void ensureConfig() {
		if (config != null && config.getPath() != null && !config.getPath().isEmpty()) {
			if (config.getProperties() == null) {
				config.setProperties(new SkinConfig.Property());
			}
			return;
		}
		SkinConfig sc = (config != null) ? config : new SkinConfig();
		if (sc.getPath() == null || sc.getPath().isEmpty()) {
			SkinConfig.Default def = SkinConfig.Default.get(type);
			if (def == null) {
				return;
			}
			sc.setPath(def.path);
		}
		if (sc.getProperties() == null) {
			sc.setProperties(new SkinConfig.Property());
		}
		player.getSkin()[type.getId()] = sc;
		config = sc;
	}

	/** 把当前 config 写回 SkinHistory（同路径则替换，否则追加）。 */
	public void saveSkinHistory() {
		if (config == null || config.getPath() == null) {
			return;
		}
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

	// ─────────────────── 参数清单 ───────────────────

	/** 按 option → file → offset 的顺序重建扁平参数清单。 */
	public void rebuildItems() {
		items.clear();
		if (selectedSkinHeader == null || config == null || config.getProperties() == null) {
			return;
		}
		updateCustomOptions();
		updateCustomFiles();
		updateCustomOffsets();
	}

	private void updateCustomOptions() {
		for (SkinHeader.CustomOption option : selectedSkinHeader.getCustomOptions()) {
			int selection = -1;
			for (SkinConfig.Option o : config.getProperties().getOption()) {
				if (o.name.equals(option.name)) {
					int i = o.value;
					if (i != OPTION_RANDOM_VALUE) {
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
				setOption(option.name, option.option[selection]);
			}
			String[] contentsAddedRandom = new String[option.contents.length + 1];
			for (int i = 0; i < option.contents.length; i++) {
				contentsAddedRandom[i] = option.contents[i];
			}
			contentsAddedRandom[option.contents.length] = "Random";
			int[] optionAddedRandom = new int[option.option.length + 1];
			for (int i = 0; i < option.option.length; i++) {
				optionAddedRandom[i] = option.option[i];
			}
			optionAddedRandom[option.option.length] = OPTION_RANDOM_VALUE;

			CustomOptionItem item = new CustomOptionItem(option.name, contentsAddedRandom, optionAddedRandom, selection);
			items.add(item);
		}
	}

	private void updateCustomFiles() {
		for (SkinHeader.CustomFile file : selectedSkinHeader.getCustomFiles()) {
			String nameValue = file.path.substring(file.path.lastIndexOf('/') + 1);
			if (file.path.contains("|")) {
				if (file.path.length() > file.path.lastIndexOf('|') + 1) {
					nameValue = file.path.substring(file.path.lastIndexOf('/') + 1, file.path.indexOf('|'))
							+ file.path.substring(file.path.lastIndexOf('|') + 1);
				} else {
					nameValue = file.path.substring(file.path.lastIndexOf('/') + 1, file.path.indexOf('|'));
				}
			}
			final String name = nameValue;
			final String dirStr = file.path.substring(0, file.path.lastIndexOf('/'));
			File dirpath;
			if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android
					&& !dirStr.startsWith("/")) {
				String root = System.getProperty("beatoraja.root", ".");
				dirpath = new File(root, dirStr);
			} else {
				dirpath = new File(dirStr);
			}
			// 规范化路径，解析 .. 和 .（Android 上含 .. 的路径可能导致 File.exists() 失败）
			try {
				dirpath = dirpath.getCanonicalFile();
			} catch (Exception e) {
				dirpath = new File(manualNormalizePath(dirpath.getPath()));
			}

			if (!dirpath.exists()) {
				Logger.getGlobal().warning("SkinAdjustModel: custom file directory does NOT exist: "
						+ dirpath + " (from " + file.path + ")");
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

				List<String> candidates = new ArrayList<>();
				if (files != null) {
					Arrays.sort(files, String.CASE_INSENSITIVE_ORDER);
					for (String filename : files) {
						candidates.add(filename);
					}
				}
				candidates.add("Random");
				String selection = null;
				for (SkinConfig.FilePath f : config.getProperties().getFile()) {
					if (f.name.equals(file.name)) {
						selection = f.path;
						break;
					}
				}
				if (selection == null && file.def != null) {
					// デフォルト値のファイル名またはそれに拡張子を付けたものが存在すれば使用する
					for (String item : candidates) {
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
					for (String item : candidates) {
						if (item.equalsIgnoreCase("default.png") || item.equalsIgnoreCase("default.bmp")) {
							selection = item;
							break;
						}
					}
				}
				if (selection == null) {
					selection = candidates.get(0);
				}
				setFile(file.name, selection);
				CustomFileItem item = new CustomFileItem(file.name, candidates, selection);
				items.add(item);
			} catch (Throwable e) {
				e.printStackTrace();
			}
		}
	}

	private void updateCustomOffsets() {
		for (SkinHeader.CustomOffset option : selectedSkinHeader.getCustomOffsets()) {
			final String[] values = {"x", "y", "w", "h", "r", "a"};
			boolean[] b = new boolean[] { option.x, option.y, option.w, option.h, option.r, option.a };
			SkinConfig.Offset ofs = null;
			for (SkinConfig.Offset o : config.getProperties().getOffset()) {
				if (o.name.equals(option.name)) {
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
			for (int i = 0; i < 6; i++) {
				if (b[i]) {
					CustomOffsetItem item = new CustomOffsetItem(option.name, values[i], i, -9999, 9999, v[i]);
					items.add(item);
				}
			}
		}
	}

	// ─────────────────── 写值 ───────────────────

	/** 写 option（按 name 找到则改，找不到则追加）。 */
	public void setOption(String name, int value) {
		for (SkinConfig.Option option : config.getProperties().getOption()) {
			if (option.name.equals(name)) {
				option.value = value;
				notifyChanged();
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
		notifyChanged();
	}

	/** 写 file（按 name 找到则改，找不到则追加）。 */
	public void setFile(String name, String path) {
		for (SkinConfig.FilePath f : config.getProperties().getFile()) {
			if (f.name.equals(name)) {
				f.path = path;
				notifyChanged();
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
		notifyChanged();
	}

	/** 写 offset（按 name 找到则改，找不到则追加）；{@code kind} 0..5 = x/y/w/h/r/a。 */
	public void setOffset(String name, int kind, int value) {
		for (SkinConfig.Offset offset : config.getProperties().getOffset()) {
			if (offset.name.equals(name)) {
				setOffsetValue(offset, kind, value);
				notifyChanged();
				return;
			}
		}
		// 既存のコンフィグデータに存在しない場合
		int length = config.getProperties().getOffset().length;
		SkinConfig.Offset[] offsets = Arrays.copyOf(config.getProperties().getOffset(), length + 1);
		offsets[length] = new SkinConfig.Offset();
		offsets[length].name = name;
		setOffsetValue(offsets[length], kind, value);
		config.getProperties().setOffset(offsets);
		notifyChanged();
	}

	private void setOffsetValue(SkinConfig.Offset offset, int kind, int value) {
		switch (kind) {
			case 0: offset.x = value; break;
			case 1: offset.y = value; break;
			case 2: offset.w = value; break;
			case 3: offset.h = value; break;
			case 4: offset.r = value; break;
			case 5: offset.a = value; break;
		}
	}

	// ─────────────────── 参数项 ───────────────────

	/**
	 * 参数项基类。两个界面都通过它读名称 / 当前值 / 取值范围，并 {@link #setValue(int)} 改值。
	 */
	public abstract static class ItemBase {
		protected final String categoryName;
		protected final int min;
		protected final int max;
		protected int value;
		protected String displayValue;

		/** 取值域为 {@code 0 .. count-1}（option / file 这类列表型参数） */
		public ItemBase(String categoryName, int count) {
			this.categoryName = categoryName;
			this.min = 0;
			this.max = count - 1;
		}

		/** 显式给定取值域（offset 这类数值型参数，且是「到边界则停」而不是 wrap） */
		public ItemBase(String categoryName, int min, int max) {
			this.categoryName = categoryName;
			this.min = min;
			this.max = max;
		}

		public String getCategoryName() { return categoryName; }

		public int getMin() { return min; }

		public int getMax() { return max; }

		public int getvalue() { return value; }

		public String getDisplayValue() { return displayValue; }

		/**
		 * 这类参数的「到边界」语义：{@code true} = 到顶则回到底（循环），
		 * {@code false} = 到边界则停。
		 * <p>option / file 的取值是枚举列表，循环最自然；offset 是数值区间
		 * （±9999），循环反而让人推不平 —— AUTOPLAY 窗口按本方法区分。</p>
		 * <p>注意：SKINCONFIG 界面走的是皮肤自己的 －/＋ 按钮
		 * （{@code SkinConfiguration.executeEvent}），历史行为是一律 wrap，
		 * 本方法<b>不</b>改变那条路径。</p>
		 */
		public boolean isWrapAround() { return true; }

		/**
		 * 本参数是否有「中性值」可以一键复位（目前只有 offset —— 它的 0 就是不动）。
		 * <p>故意与 {@link #isWrapAround()} 分开声明：那个说的是「到边界怎么办」，
		 * 这个说的是「有没有可回到的默认点」。两者当前恰好重合，但语义独立 ——
		 * 绑在一起的话，将来改 wrap 行为会顺带改掉「点值区归零」。</p>
		 */
		public boolean hasNeutralValue() { return false; }

		public abstract void setValue(int value);
	}

	private class CustomOptionItem extends ItemBase {
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
			setOption(categoryName, options[value]);
		}
	}

	private class CustomFileItem extends ItemBase {
		List<String> displayValues;
		List<String> actualValues;

		public CustomFileItem(String name, List<String> paths, String selection) {
			super(name, paths.size());
			actualValues = paths;
			displayValues = new ArrayList<String>();
			int i = 0;
			for (String path : paths) {
				displayValues.add(path); // 直接显示完整文件名，方便识别 default.png
				if (path.equals(selection)) {
					this.value = i;
					this.displayValue = path;
				}
				i++;
			}
			if (this.displayValue == null && !displayValues.isEmpty()) {
				// config 里选的路径已经不在候选里（文件被删 / 换了目录）：
				// 退到第一项，否则值列会一直空着（值区也没法点）
				this.value = 0;
				this.displayValue = displayValues.get(0);
			}
		}

		public void setValue(int i) {
			value = i;
			displayValue = actualValues.get(i);
			setFile(categoryName, actualValues.get(i));
		}
	}

	private class CustomOffsetItem extends ItemBase {
		String offsetName;
		int kind;

		public CustomOffsetItem(String offsetName, String kindName, int kind, int min, int max, int selection) {
			super(offsetName + " - " + kindName, min, max);
			this.offsetName = offsetName;
			this.kind = kind;
			this.value = selection;
			this.displayValue = String.valueOf(this.value);
		}

		@Override
		public boolean isWrapAround() {
			return false;
		}

		@Override
		public boolean hasNeutralValue() {
			return true;
		}

		public void setValue(int i) {
			value = i;
			displayValue = String.valueOf(value);
			setOffset(offsetName, kind, value);
		}
	}

	// ─────────────────── 扫描 ───────────────────

	private void loadAllSkins() {
		Logger.getGlobal().info("SkinAdjustModel: loadAllSkins starting...");
		allSkins.clear();
		List<File> skinPaths = new ArrayList<>();

		// Android 平台适配：使用正确的皮肤目录路径
		File skinDir;
		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			String root = System.getProperty("beatoraja.root", ".");
			skinDir = new File(root, "skin");
		} else {
			skinDir = new File("skin");
		}
		if (!skinDir.exists()) {
			Logger.getGlobal().severe("SkinAdjustModel: skin directory does NOT exist: " + skinDir);
		}

		try {
			scanSkins(skinDir, skinPaths);
			Logger.getGlobal().info("SkinAdjustModel: scanSkins found " + skinPaths.size() + " skin files");

			for (File path : skinPaths) {
				try {
					SkinHeader header = loadHeader(path);
					if (header != null) {
						allSkins.add(header);
					} else {
						Logger.getGlobal().warning("SkinAdjustModel: loadHeader returned null for " + path);
					}
				} catch (Throwable t) {
					Logger.getGlobal().severe("SkinAdjustModel: failed to process skin: "
							+ path + ", error=" + t.getMessage());
					t.printStackTrace();
				}
			}
		} catch (Throwable t) {
			Logger.getGlobal().severe("SkinAdjustModel: loadAllSkins failed with exception: " + t.getMessage());
			t.printStackTrace();
		}
		Logger.getGlobal().info("SkinAdjustModel: total skins loaded: " + allSkins.size());
	}

	/**
	 * 按扩展名选 loader 载入单张皮肤的 header（只读头部，不加载 Skin 实体）。
	 *
	 * <p>🔴 {@code .luaskin} 分支会执行 Lua 脚本、建 LuaJ 全局表 —— 与
	 * {@link #ensureScanned()} 一样，必须在渲染线程调用。</p>
	 *
	 * <p>LR2 皮肤额外做「7/14key 的皮肤同时作为 5/10key 的第二份」这件事；
	 * 那一份由本方法直接追加进 {@link #allSkins}（不通过返回值）。</p>
	 *
	 * @return 载入的 header；失败返回 null
	 */
	private SkinHeader loadHeader(File path) {
		try {
			String pathString = path.toString().toLowerCase();
			if (pathString.endsWith(".json")) {
				return new JSONSkinLoader().loadHeader(path);
			}
			if (pathString.endsWith(".luaskin")) {
				return new LuaSkinLoader().loadHeader(path);
			}
			LR2SkinHeaderLoader loader = new LR2SkinHeaderLoader(main.getConfig());
			SkinHeader header = loader.loadSkin(path, null);
			if (header != null && header.getType() == SkinHeader.TYPE_LR2SKIN
					&& (header.getSkinType() == SkinType.PLAY_7KEYS
						|| header.getSkinType() == SkinType.PLAY_14KEYS)) {
				SkinHeader second = loader.loadSkin(path, null);
				if (second.getSkinType() == SkinType.PLAY_7KEYS
						&& !second.getName().toLowerCase().contains("7key")) {
					second.setName(second.getName() + " (7KEYS) ");
				} else if (second.getSkinType() == SkinType.PLAY_14KEYS
						&& !second.getName().toLowerCase().contains("14key")) {
					second.setName(second.getName() + " (14KEYS) ");
				}
				second.setSkinType(second.getSkinType() == SkinType.PLAY_7KEYS
						? SkinType.PLAY_5KEYS : SkinType.PLAY_10KEYS);
				allSkins.add(second);
			}
			return header;
		} catch (IOException e) {
			Logger.getGlobal().warning("SkinAdjustModel: failed to load skin header: "
					+ path + ", error=" + e.getMessage());
			return null;
		}
	}

	private void scanSkins(File path, List<File> paths) {
		if (path.isDirectory()) {
			File[] sub = path.listFiles();
			if (sub != null) {
				for (File f : sub) {
					scanSkins(f, paths);
				}
			}
		} else {
			String name = path.getName().toLowerCase();
			if (name.endsWith(".lr2skin") || name.endsWith(".luaskin") || name.endsWith(".json")) {
				paths.add(path);
			}
		}
	}

	// ─────────────────── 路径工具 ───────────────────

	/**
	 * 规范化皮肤路径用于匹配：消除绝对/相对路径差异（Android 上 config 默认路径为
	 * "skin/default/select.json"，而 header.getPath() 是 File.toString() 的绝对路径），
	 * 以及大小写差异与多余分隔符。
	 */
	public static String normalizeSkinPath(String path) {
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
	public static String manualNormalizePath(String path) {
		String p = path.replace("\\", "/");
		boolean isAbsolute = p.startsWith("/");
		String[] parts = p.split("/");
		ArrayList<String> stack = new ArrayList<>();
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
