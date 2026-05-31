package bms.player.beatoraja.skin.lua;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.SkinConfig;
import bms.player.beatoraja.skin.*;
import bms.player.beatoraja.skin.json.JSONSkinLoader;
import bms.player.beatoraja.skin.json.JsonSkin;
import bms.player.beatoraja.skin.property.*;

import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.reflect.ClassReflection;
import com.badlogic.gdx.utils.reflect.Field;
import com.badlogic.gdx.utils.reflect.ReflectionException;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

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

	public LuaSkinLoader() {
		super(new SkinLuaAccessor(false));
	}

	public LuaSkinLoader(MainState state, Config c) {
		super(state, c, new SkinLuaAccessor(false));
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
			filemap = new ObjectMap<>();
			for(SkinHeader.CustomFile customFile : header.getCustomFiles()) {
				if(customFile != null && customFile.getSelectedFilename() != null) {
					String normalizedKey = customFile.path.replace("\\", "/").replaceAll("/+", "/");
					if (normalizedKey.startsWith("/")) normalizedKey = normalizedKey.substring(1);
					normalizedKey = normalizePath(normalizedKey);
					filemap.put(normalizedKey, customFile.getSelectedFilename());
				}
			}

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
}
