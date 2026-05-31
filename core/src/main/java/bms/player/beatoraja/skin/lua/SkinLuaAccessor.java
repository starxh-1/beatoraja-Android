package bms.player.beatoraja.skin.lua;

import com.badlogic.gdx.files.FileHandle;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaDouble;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaInteger;
import org.luaj.vm2.LuaNumber;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ResourceFinder;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.util.function.Function;
import java.util.logging.Logger;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.SkinConfig;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.SkinHeader.CustomOffset;
import bms.player.beatoraja.skin.SkinHeader.CustomOption;
import bms.player.beatoraja.skin.SkinProperty;
import bms.player.beatoraja.skin.property.BooleanProperty;
import bms.player.beatoraja.skin.property.Event;
import bms.player.beatoraja.skin.property.EventFactory;
import bms.player.beatoraja.skin.property.FloatProperty;
import bms.player.beatoraja.skin.property.FloatWriter;
import bms.player.beatoraja.skin.property.IntegerProperty;
import bms.player.beatoraja.skin.property.StringProperty;
import bms.player.beatoraja.skin.property.TimerProperty;

/**
 * Luaスキンからデータを参照するためのクラス
 *
 * @author excln
 */
public class SkinLuaAccessor {

	private final Globals globals;

	// 各機能のエクスポート先を globals にするかどうか
	private final boolean isGlobal;

	// isGlobal == false のとき、エクスポートするモジュール名
	private static final String MAIN_STATE = "main_state";
	private static final String TIMER_UTIL = "timer_util";
	private static final String EVENT_UTIL = "event_util";

	public Globals getGlobals() {
		return globals;
	}

	public SkinLuaAccessor(boolean isGlobal) {
		globals = JsePlatform.standardGlobals();
		// 设置自定义资源查找器，解决 Android 上 require 找不到文件的问题
		globals.finder = new ResourceFinder() {
			@Override
			public java.io.InputStream findResource(String name) {
				String p = name.replace("\\", "/");
				try {
					// 首先尝试从 package.path 中定义的目录查找
					String packagePath = null;
					try {
						LuaValue pathVal = globals.get("package").get("path");
						if (!pathVal.isnil()) {
							packagePath = pathVal.tojstring();
						}
					} catch (Exception e) {
						// package 可能还未初始化
					}

					if (packagePath != null) {
						String[] paths = packagePath.split(";");
						for (String pathPattern : paths) {
							// 将 ?.lua 替换为实际文件名
							String searchPath = pathPattern.replace("?.lua", p).replace("?", p.replace(".lua", ""));
							if (searchPath.contains(p)) {
								FileHandle fh = tryFindFile(searchPath);
								if (fh != null && fh.exists()) {
									return fh.read();
								}
							}
						}
					}

					// 如果 package.path 中没找到，直接尝试查找
					FileHandle fh = tryFindFile(p);
					if (fh != null && fh.exists()) {
						return fh.read();
					}
				} catch (Exception e) {
					Logger.getGlobal().severe("Lua findResource failed: name=" + name + ", error=" + e.getMessage());
					// 忽略错误，让 Lua 尝试下一个搜索路径
				}
				Logger.getGlobal().warning("fail to open node (Lua require): " + name);
				return null;
			}

			private FileHandle tryFindFile(String path) {
				FileHandle fh = null;
				if (com.badlogic.gdx.Gdx.app != null && com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
					// 1. 绝对路径
					if (path.startsWith("/") || path.startsWith("data/")) {
						fh = com.badlogic.gdx.Gdx.files.absolute(path.startsWith("/") ? path : "/" + path);
						if (fh != null && !fh.exists()) {
							fh = null;
						}
					}
					// 2. 使用 beatoraja.root 前缀的绝对路径（Android上assets被复制到外部存储）
					if (fh == null) {
						String root = System.getProperty("beatoraja.root");
						if (root != null && !root.isEmpty()) {
							String absPath = root + "/" + path;
							fh = com.badlogic.gdx.Gdx.files.absolute(absPath);
							if (!fh.exists()) {
								fh = null;
							}
						}
					}
					// 3. 本地私有目录 (files) - /data/data/.../files/
					if (fh == null) {
						fh = com.badlogic.gdx.Gdx.files.local(path);
						if (!fh.exists()) {
							fh = null;
						}
					}
					// 4. 内部 assets
					if (fh == null) {
						fh = com.badlogic.gdx.Gdx.files.internal(path);
						if (!fh.exists()) {
							fh = null;
						}
					}
				} else {
					// PC 端
					fh = com.badlogic.gdx.Gdx.files.internal(path);
					if (fh == null || !fh.exists()) {
						fh = com.badlogic.gdx.Gdx.files.absolute(path);
					}
				}
				return fh;
			}
		};
		this.isGlobal = isGlobal;

		if (!isGlobal) {
			// ヘッダ読み込み時に require("main_state") だけでエラーになると面倒なので、空のテーブルを入れておく
			globals.package_.setIsLoaded(MAIN_STATE, new LuaTable());
			globals.package_.setIsLoaded(TIMER_UTIL, new LuaTable());
			globals.package_.setIsLoaded(EVENT_UTIL, new LuaTable());
		}

		// 导出 SkinProperty 中所有的常量到全局环境，让 Lua 脚本可以直接访问 OPTION_*, NUMBER_*, TIMER_* 等定义
		try {
			for (java.lang.reflect.Field field : SkinProperty.class.getFields()) {
				if (field.getType() == int.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					String name = field.getName();
					int value = field.getInt(null);
					globals.set(name, LuaInteger.valueOf(value));
				}
			}
		} catch (Exception e) {
			Logger.getGlobal().severe("Failed to export SkinProperty constants to Lua: " + e.getMessage());
		}
	}

	public BooleanProperty loadBooleanProperty(String script) {
		try {
			final LuaValue lv = globals.load("return " + script);
			return loadBooleanProperty(lv.checkfunction());
		} catch (RuntimeException e) {
			Logger.getGlobal().warning("Lua解析時の例外 : " + e.getMessage());
		}
		return null;
	}

	public BooleanProperty loadBooleanProperty(LuaFunction function) {
		return new BooleanProperty() {
			@Override
			public boolean isStatic(MainState state) {
				return false;
			}

			@Override
			public boolean get(MainState state) {
				try {
					return function.call().toboolean();
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外 : " + e.getMessage());
					return false;
				}
			}
		};
	}

	public IntegerProperty loadIntegerProperty(String script) {
		try {
			final LuaValue lv = globals.load("return " + script);
			return loadIntegerProperty(lv.checkfunction());
		} catch (RuntimeException e) {
			Logger.getGlobal().warning("Lua解析時の例外 : " + e.getMessage());
		}
		return null;
	}

	public IntegerProperty loadIntegerProperty(LuaFunction function) {
		return new IntegerProperty() {
			@Override
			public int get(MainState state) {
				try{
					return function.call().toint();
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外 : " + e.getMessage());
					return 0;
				}
			}
		};
	}

	public FloatProperty loadFloatProperty(String script) {
		try {
			final LuaValue lv = globals.load("return " + script);
			return loadFloatProperty(lv.checkfunction());
		} catch (RuntimeException e) {
			Logger.getGlobal().warning("Lua解析時の例外 : " + e.getMessage());
		}
		return null;
	}

	public FloatProperty loadFloatProperty(LuaFunction function) {
		return new FloatProperty() {
			@Override
			public float get(MainState state) {
				try{
					return function.call().tofloat();
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外 : " + e.getMessage());
					return 0f;
				}
			}
		};
	}

	public StringProperty loadStringProperty(String script) {
		try {
			final LuaValue lv = globals.load("return " + script);
			return loadStringProperty(lv.checkfunction());
		} catch (RuntimeException e) {
			Logger.getGlobal().warning("Lua解析時の例外 : " + e.getMessage());
		}
		return null;
	}

	public StringProperty loadStringProperty(LuaFunction function) {
		return new StringProperty() {
			@Override
			public String get(MainState state) {
				try {
					return function.call().tojstring();
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外：" + e.getMessage());
					return "";
				}
			}
		};
	}

	/**
	 * Creates a timer property from Lua code.
	 * If {@code script} returns a function, the returned function is regarded as a timer function
	 * which will be called every frame or more frequently.
	 * Otherwise, {@code script} itself is regarded as a timer function.
	 * <p>NOTE: The former case is useful to synthesize a stateful custom timer in a JSON skin.</p>
	 * <p>NOTE: A timer function returns (i) start time in microseconds if on, or (ii) Long.MIN_VALUE if off.</p>
	 * @param script Lua script producing a function (producing a number) or a number
	 * @return new timer property
	 */
	public TimerProperty loadTimerProperty(String script) {
		try {
			final LuaValue lv = globals.load("return " + script);
			final LuaValue trialCallResult = lv.call();
			if (trialCallResult.isfunction()) {
				// タイマー関数を返す場合
				return loadTimerProperty(trialCallResult.checkfunction());
			} else {
				// 数値を返す場合
				return loadTimerProperty(lv.checkfunction());
			}
		} catch (RuntimeException e) {
			Logger.getGlobal().warning("Lua解析時の例外 : " + e.getMessage());
		}
		return null;
	}

	/**
	 * Creates a timer property from Lua function.
	 * The given function is always regarded as a timer function which will be called every frame.
	 * @param timerFunction Lua function producing a number
	 * @return new timer property
	 */
	public TimerProperty loadTimerProperty(LuaFunction timerFunction) {
		return new TimerProperty() {
			@Override
			public long getMicro(MainState state) {
				try {
					return timerFunction.call().tolong();
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外：" + e.getMessage());
					return Long.MIN_VALUE;
				}
			}
		};
	}

	public Event loadEvent(String script) {
		try {
			final LuaValue lv = globals.load(script);
			return loadEvent(lv.checkfunction());
		} catch (RuntimeException e) {
			Logger.getGlobal().warning("Lua解析時の例外 : " + e.getMessage());
		}
		return null;
	}

	public Event loadEvent(LuaFunction function) {
		switch (function.narg()) {
		case 0:
			return EventFactory.createZeroArgEvent(state -> {
				try{
					function.call();
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外 : " + e.getMessage());
				}
			});
		case 1:
			return EventFactory.createOneArgEvent((state, arg1) -> {
				try{
					function.call(LuaNumber.valueOf(arg1));
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外 : " + e.getMessage());
				}
			});
		case 2:
			return EventFactory.createTwoArgEvent((state, arg1, arg2) -> {
				try{
					function.call(LuaNumber.valueOf(arg1), LuaNumber.valueOf(arg2));
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外 : " + e.getMessage());
				}
			});
		default:
			return null;
		}
	}

	public FloatWriter loadFloatWriter(String script) {
		try {
			final LuaValue lv = globals.load(script);
			return loadFloatWriter(lv.checkfunction());
		} catch (RuntimeException e) {
			Logger.getGlobal().warning("Lua解析時の例外 : " + e.getMessage());
		}
		return null;
	}

	public FloatWriter loadFloatWriter(LuaFunction function) {
		return new FloatWriter() {
			@Override
			public void set(MainState state, float value) {
				try{
					function.call(LuaDouble.valueOf(value));
				} catch (RuntimeException e) {
					Logger.getGlobal().warning("Lua実行時の例外：" + e.getMessage());
				}
			}
		};
	}

	public LuaValue exec(String script) {
		return globals.load(script).call();
	}

	public LuaValue execFile(File path) {
		String p = path.getPath().replace("\\", "/");
		try {
			FileHandle fh = null;
			if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
				// 1. 尝试作为绝对路径读取 (如果路径以 / 开始或者是完整的 Android 数据路径)
				if (path.isAbsolute() || p.startsWith("/data/")) {
					fh = com.badlogic.gdx.Gdx.files.absolute(p);
				}

				// 2. 使用 beatoraja.root 前缀的绝对路径（Android上assets被复制到外部存储）
				if (fh == null || !fh.exists()) {
					String root = System.getProperty("beatoraja.root");
					if (root != null && !root.isEmpty()) {
						fh = com.badlogic.gdx.Gdx.files.absolute(root + "/" + p);
					}
				}

				// 3. 尝试作为本地私有目录路径读取
				if (fh == null || !fh.exists()) {
					fh = com.badlogic.gdx.Gdx.files.local(p);
				}

				// 4. 尝试作为 assets 内部路径读取
				if (fh == null || !fh.exists()) {
					fh = com.badlogic.gdx.Gdx.files.internal(p);
				}
			} else {
				// PC 端逻辑
				fh = com.badlogic.gdx.Gdx.files.internal(p);
				if (!fh.exists()) {
					fh = com.badlogic.gdx.Gdx.files.absolute(p);
				}
			}

			if (fh != null && fh.exists()) {
				java.io.InputStream is = fh.read();
				if (is != null) {
					return globals.load(is, "@" + p, "bt", globals).call();
				}
			}

			// 如果走到这里说明文件确实没找到，抛出异常进入 catch
			throw new java.io.FileNotFoundException("Cannot find skin file: " + p);
		} catch (Exception e) {
			Logger.getGlobal().severe("Lua 加载失败 (" + p + "): " + e.getMessage());
			return LuaValue.NIL;
		}
	}

	public void setDirectory(File path) {
		String p = path.getPath().replace("\\", "/");
		LuaTable pkg = globals.get("package").checktable();
		// 将当前目录加入 Lua 搜索路径
		pkg.set("path", pkg.get("path").tojstring() + ";" + p + "/?.lua");
	}

	/**
	 * MainState にアクセスするための機能をエクスポートする。
	 * isGlobal == true のとき、グローバル変数としてそのまま追加
	 * それ以外のとき、モジュール "main_state" にエクスポート
	 * (Lua からは main_state = require("main_state") などとすることで利用可能)
	 * @param state MainState
	 */
	public void exportMainStateAccessor(MainState state) {
		MainStateAccessor accessor = new MainStateAccessor(state);
		if (isGlobal) {
			accessor.export(globals);
		} else {
			LuaTable mainStateTable = new LuaTable();
			accessor.export(mainStateTable);
			globals.package_.setIsLoaded(MAIN_STATE, mainStateTable);
		}
	}

	/**
	 * その他のユーティリティーをエクスポートする。
	 * ロードがそれほど重くなく、JSONスキンから使う可能性もあることが前提
	 * @param state MainState
	 */
	public void exportUtilities(MainState state) {
		TimerUtility timerUtil = new TimerUtility(state);
		EventUtility eventUtil = new EventUtility(state);
		if (isGlobal) {
			timerUtil.export(globals);
			eventUtil.export(globals);
		} else {
			LuaTable timerUtilTable = new LuaTable();
			timerUtil.export(timerUtilTable);
			globals.package_.setIsLoaded(TIMER_UTIL, timerUtilTable);
			LuaTable eventUtilTable = new LuaTable();
			eventUtil.export(eventUtilTable);
			globals.package_.setIsLoaded(EVENT_UTIL, eventUtilTable);
		}
	}

	/**
	 * スキン設定をエクスポートする。
	 * isGlobal にかかわらず、グローバル変数 skin_config にデータがセットされた状態にする。
	 * Lua スキンは skin_config が nil のときヘッダのみ読み込めるようにする。
	 *
	 * @param header スキンヘッダデータ
	 * @param property Property (スキン設定データ)
	 * @param filePathGetter スキン設定を元にファイルパスを解決する関数
	 */
	public void exportSkinProperty(SkinHeader header, SkinConfig.Property property, Function<String, String> filePathGetter) {
		LuaTable table = new LuaTable();
		exportSkinPropertyToTable(header, property, filePathGetter, table);
		globals.set("skin_config", table);
	}

	private void exportSkinPropertyToTable(SkinHeader header, SkinConfig.Property property, Function<String, String> filePathGetter, LuaTable table) {
		LuaTable file_path = new LuaTable();
		for (SkinConfig.FilePath file : property.getFile()) {
			file_path.set(file.name, file.path);
		}
		table.set("file_path", file_path);
		table.set("get_path", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue value) {
				return LuaString.valueOf(filePathGetter.apply(value.tojstring()));
			}
		});

		LuaTable options = new LuaTable();
		LuaTable enabled_options = new LuaTable();

		for (CustomOption option : header.getCustomOptions()) {
			int opvalue = option.getSelectedOption();
			options.set(option.name, opvalue);
			enabled_options.insert(enabled_options.length() + 1, LuaInteger.valueOf(opvalue));
		}

		table.set("option", options);
		table.set("enabled_options", enabled_options);

		LuaTable offsets = new LuaTable();

		for(CustomOffset offset : header.getCustomOffsets()) {
			SkinConfig.Offset ofs = null;
			for (SkinConfig.Offset of : property.getOffset()) {
				if(offset.name.equals(of.name)) {
					ofs = of;
					break;
				}
			}
			if(ofs == null) {
				ofs = new SkinConfig.Offset();
				ofs.name = offset.name;
			}
			LuaTable offsetTable = new LuaTable();
			offsetTable.set("x", ofs.x);
			offsetTable.set("y", ofs.y);
			offsetTable.set("w", ofs.w);
			offsetTable.set("h", ofs.h);
			offsetTable.set("r", ofs.r);
			offsetTable.set("a", ofs.a);
			offsets.set(ofs.name, offsetTable);
		}
		table.set("offset", offsets);
	}
}
