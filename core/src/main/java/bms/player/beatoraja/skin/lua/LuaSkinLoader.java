package bms.player.beatoraja.skin.lua;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.SkinConfig;
import bms.player.beatoraja.skin.*;
import bms.player.beatoraja.skin.json.JSONSkinLoader;
import bms.player.beatoraja.skin.json.JsonSkin;
import bms.player.beatoraja.skin.property.*;
import bms.player.beatoraja.select.MusicSelector;

import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.reflect.ClassReflection;
import com.badlogic.gdx.utils.reflect.Field;
import com.badlogic.gdx.utils.reflect.ReflectionException;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.io.File;
import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Luaスキンローダー (Android 优化版)
 *
 * @author excln / Optimized for beatoraja-Android
 */
public class LuaSkinLoader extends JSONSkinLoader {

	private static final ConcurrentHashMap<Class<?>, Map<String, Field>> fieldMapCache = new ConcurrentHashMap<>();

	/** 缓存编译后的 Lua 闭包，避免重复读取磁盘和解析 */
	private LuaValue cachedClosure;
	private File cachedFile;
	/** 调用方传入的 MainState，用于判断是否 MusicSelect 走路径重定向 */
	private final MainState state;
	/** 是否已为本次加载安装过 io.open 重定向（避免重入） */
	private boolean ioRedirectInstalled = false;

	public LuaSkinLoader() {
		super(new SkinLuaAccessor(false));
		this.state = null;
	}

	public LuaSkinLoader(MainState state, Config c) {
		super(state, c, new SkinLuaAccessor(false));
		this.state = state;
	}

	private LuaValue getExecResult(File p) {
		try {
			if (p.equals(cachedFile) && cachedClosure != null) {
				return cachedClosure.call();
			}
			cachedFile = p;
			String pathStr = p.getPath().replace("\\", "/");
			com.badlogic.gdx.files.FileHandle fh = com.badlogic.gdx.Gdx.files.absolute(p.getAbsolutePath());
			if (!fh.exists()) fh = com.badlogic.gdx.Gdx.files.internal(pathStr);
			if (fh.exists()) {
				// 编译并缓存闭包。注意：这步不执行脚本，只生成可执行代码。
				cachedClosure = lua.getGlobals().load(fh.read(), "@" + p.getPath(), "bt", lua.getGlobals());
				return cachedClosure.call();
			}
		} catch (Exception e) {
			java.util.logging.Logger.getGlobal().severe("Lua 加载/编译失败: " + e.getMessage());
		}
		return LuaValue.NIL;
	}

	@Override
	public SkinHeader loadHeader(File p) {
		SkinHeader header = null;
		try {
			lua.setDirectory(p.getParentFile());
			installIoRedirectIfMusicSelect(p.getParentFile());
			LuaValue value = getExecResult(p);
			if (value.istable()) {
				LuaValue h = value.get("header");
				if (!h.isnil()) {
					sk = fromLuaValue(JsonSkin.Skin.class, h);
				} else {
					sk = fromLuaValue(JsonSkin.Skin.class, value);
				}
			}
			header = loadJsonSkinHeader(sk, p);
		} catch (Throwable e) {
			java.util.logging.Logger.getGlobal().severe("LuaSkinLoader.loadHeader 异常: " + e.getMessage());
		}
		return header;
	}

	@Override
	public Skin load(File p, SkinType type, SkinConfig.Property property) {
		Skin skin = null;
		SkinHeader header = loadHeader(p);
		if(header == null) return null;
		header.setSkinConfigProperty(property);

		try {
			filemap = new SkinLoader.SkinFileMap();
			for(SkinHeader.CustomFile customFile : header.getCustomFiles()) {
				if(customFile != null && customFile.getSelectedFilename() != null) {
					String normalizedKey = customFile.path.replace("\\", "/").replaceAll("/+", "/");
					if (normalizedKey.startsWith("/")) normalizedKey = normalizedKey.substring(1);
					normalizedKey = normalizePath(normalizedKey);
					filemap.put(normalizedKey, customFile.getSelectedFilename());
				}
			}
			filemap.buildIndex();

			lua.exportSkinProperty(header, property, (String path) -> {
				String rawPath = p.getParent() + "/" + path;
				rawPath = SkinLoader.normalizePath(rawPath.replace("\\", "/").replaceAll("/+", "/"));
				if (rawPath.startsWith("/")) rawPath = rawPath.substring(1);
				return getPath(rawPath, filemap).getPath();
			});

			// 重新执行以确保正确的 skin_config 导出
			LuaValue value = getExecResult(p);
			if (value.istable() && value.get("main").isfunction()) {
				value = value.get("main").call();
			}

			sk = fromLuaValue(JsonSkin.Skin.class, value);
			skin = loadJsonSkin(header, sk, type, property, p);
		} catch (Throwable e) {
			java.util.logging.Logger.getGlobal().severe("LuaSkinLoader.load 崩溃: " + e.getMessage());
			e.printStackTrace();
		}
		return skin;
	}

	private final Map<Class, Function<LuaValue, Object>> serializerMap = new HashMap<Class, Function<LuaValue, Object>>() {
		{
			put(boolean.class, LuaValue::toboolean);
			put(Boolean.class, LuaValue::toboolean);
			put(int.class, LuaValue::toint);
			put(Integer.class, LuaValue::toint);
			put(float.class, LuaValue::tofloat);
			put(Float.class, LuaValue::tofloat);
			put(String.class, LuaValue::tojstring);
			put(BooleanProperty.class, lv ->
					serializeLuaScript(lv, lua::loadBooleanProperty, lua::loadBooleanProperty, BooleanPropertyFactory::getBooleanProperty));
			put(IntegerProperty.class, lv ->
					serializeLuaScript(lv, lua::loadIntegerProperty, lua::loadIntegerProperty, IntegerPropertyFactory::getIntegerProperty));
			put(FloatProperty.class, lv ->
					serializeLuaScript(lv, lua::loadFloatProperty, lua::loadFloatProperty, FloatPropertyFactory::getRateProperty));
			put(StringProperty.class, lv ->
					serializeLuaScript(lv, lua::loadStringProperty, lua::loadStringProperty, StringPropertyFactory::getStringProperty));
			put(TimerProperty.class, lv ->
					serializeLuaScript(lv, lua::loadTimerProperty, lua::loadTimerProperty, TimerPropertyFactory::getTimerProperty));
			put(FloatWriter.class, lv ->
					serializeLuaScript(lv, lua::loadFloatWriter, lua::loadFloatWriter, FloatPropertyFactory::getRateWriter));
			put(Event.class, lv ->
					serializeLuaScript(lv, lua::loadEvent, lua::loadEvent, EventFactory::getEvent));
		}
	};

	private static <T> T serializeLuaScript(LuaValue lv, Function<LuaFunction, T> asFunction, Function<String, T> asScript, Function<Integer, T> byId) {
		if (lv.isfunction()) {
			return asFunction.apply(lv.checkfunction());
		} else if (lv.isnumber() && byId != null) {
			return byId.apply(lv.toint());
		} else if (lv.isstring()) {
			return asScript.apply(lv.tojstring());
		} else {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	<T> T fromLuaValue(Class<T> cls, LuaValue lv) {
		if (lv == null || lv.isnil()) return null;

		if (serializerMap.containsKey(cls)) {
			return (T) serializerMap.get(cls).apply(lv);
		} else if (cls.isArray()) {
			Class<?> componentClass = cls.getComponentType();
			if (lv.istable()) {
				LuaTable table = (LuaTable) lv;
				int len = table.length();
				// 优化：针对 Lua 1-based 连续数组进行快速转换
				if (len > 0) {
					Object array = Array.newInstance(componentClass, len);
					for (int i = 0; i < len; i++) {
						Array.set(array, i, fromLuaValue(componentClass, table.get(i + 1)));
					}
					return (T) array;
				}
				// 备选方案：处理非连续数组或名值对
				LuaValue[] keys = table.keys();
				Object array = Array.newInstance(componentClass, keys.length);
				for (int i = 0; i < keys.length; i++) {
					Array.set(array, i, fromLuaValue(componentClass, table.get(keys[i])));
				}
				return (T) array;
			} else {
				return (T) Array.newInstance(componentClass, 0);
			}
		} else {
			try {
				T instance = (T) ClassReflection.newInstance(cls);
				Map<String, Field> fields = fieldMapCache.get(cls);
				if (fields == null) {
					fields = new HashMap<>();
					for (Field f : ClassReflection.getFields(cls)) {
						fields.put(f.getName(), f);
					}
					fieldMapCache.put(cls, fields);
				}

				if (lv.istable()) {
					LuaTable table = (LuaTable) lv;
					LuaValue[] keys = table.keys();
					for (LuaValue key : keys) {
						String keyName = key.tojstring();
						Field field = fields.get(keyName);
						if (field != null) {
							Object value = fromLuaValue(field.getType(), table.get(key));
							if (value != null) {
								try {
									field.set(instance, value);
								} catch (Exception e) {
									// 忽略类型不匹配
								}
							}
						}
					}
				}
				return instance;
			} catch (ReflectionException e) {
				return null;
			}
		}
	}

	/**
	 * 仅当加载 MusicSelect 皮肤时，把 lua 的 io.open / io.lines 重定向为以皮肤目录为基准。
	 * 在 Android（user.dir == "/"）下，Lua 皮肤脚本里相对路径（如 "skin/hij_simple/djpoint_log.txt"）
	 * 无法正确解析。此方法拦截 io.open 和 io.lines，将相对路径先尝试皮肤目录，
	 * 再尝试 beatoraja.root（Android 外部存储根），最后回退到原始调用。
	 * 其它状态（PLAY/RESULT 等）走原始 io 函数。
	 */
	private void installIoRedirectIfMusicSelect(File skinDir) {
		if (ioRedirectInstalled) return;
		if (state == null || !(state instanceof MusicSelector)) return;
		if (skinDir == null) return;
		LuaTable io;
		try {
			LuaValue ioVal = lua.getGlobals().get("io");
			if (!(ioVal instanceof LuaTable)) return;
			io = (LuaTable) ioVal;
		} catch (Throwable t) {
			return;
		}
		final LuaValue originalOpen = io.get("open");
		final String androidRoot = System.getProperty("beatoraja.root");
		final File skinDirFinal = skinDir;

		// Helper: resolve a relative path against multiple base directories
		java.util.function.Function<String, String> resolvePath = path -> {
			if (path == null || path.isEmpty()) return path;
			File f = new File(path);
			if (f.isAbsolute()) return path;
			// Try skin directory first
			f = new File(skinDirFinal, path);
			if (f.exists()) return f.getAbsolutePath();
			// Try beatoraja.root (covers "skin/xxx/yyy.txt" style paths)
			if (androidRoot != null && !androidRoot.isEmpty()) {
				File f2 = new File(androidRoot, path);
				if (f2.exists()) return f2.getAbsolutePath();
			}
			// Fallback: resolve relative to skinDir even if file doesn't exist yet (for writes)
			return new File(skinDirFinal, path).getAbsolutePath();
		};

		io.set("open", new TwoArgFunction() {
			@Override
			public LuaValue call(LuaValue arg1, LuaValue arg2) {
				String resolved = resolvePath.apply(arg1.tojstring());
				return originalOpen.call(LuaValue.valueOf(resolved), arg2);
			}
		});

		// io.lines in LuaJ's IoLib does NOT delegate to io.open internally;
		// it opens files directly via openFile(), so we must override it separately.
		final LuaValue originalLines = io.get("lines");
		io.set("lines", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				if (args.narg() > 0) {
					LuaValue arg1 = args.arg1();
					String resolved = resolvePath.apply(arg1.tojstring());
					LuaValue[] newArgs = new LuaValue[args.narg()];
					newArgs[0] = LuaValue.valueOf(resolved);
					for (int i = 1; i < args.narg(); i++) {
						newArgs[i] = args.arg(i + 1);
					}
					return originalLines.invoke(LuaValue.varargsOf(newArgs));
				}
				return originalLines.invoke(args);
			}
		});
		ioRedirectInstalled = true;
	}
}
