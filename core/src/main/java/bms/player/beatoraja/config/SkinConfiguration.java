package bms.player.beatoraja.config;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import static bms.player.beatoraja.skin.SkinProperty.*;

import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.SkinConfig;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
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
	private PlayerConfig player;

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
			int index = -1;
			for (int i = 0; i < availableSkins.size(); i++) {
				SkinHeader header = availableSkins.get(i);
				if (header != null && config.getPath().equals(header.getPath())) {
					index = i;
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

		int index = selectedSkinIndex < 0 ? 0 : (selectedSkinIndex + indexDiff + availableSkins.size()) % availableSkins.size();
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
			updateCustomOptions();
			updateCustomFiles();
			updateCustomOffsets();
			customOptionOffsetMax = Math.max(0, customOptions.size() - skin.getCustomPropertyCount());
		} else {
			selectedSkinHeader = null;
			customOptions = null;
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
	}

	private void setFilePath(String name, String path) {
		for (SkinConfig.FilePath f : config.getProperties().getFile()) {
			if(f.name.equals(name)) {
				f.path = path;
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
	}

	private void setCustomOffset(String name, int kind, int value) {
		for(SkinConfig.Offset offset : config.getProperties().getOffset()) {
			if(offset.name.equals(name)) {
				setOffset(offset, kind, value);
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
}
