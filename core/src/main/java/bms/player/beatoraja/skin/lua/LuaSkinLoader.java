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
import java.util.function.Function;

/**
 * Luaスキンローダー
 *
 * @author excln
 */
public class LuaSkinLoader extends JSONSkinLoader {

	public LuaSkinLoader() {
		super(new SkinLuaAccessor(false));
	}

	public LuaSkinLoader(MainState state, Config c) {
		super(state, c, new SkinLuaAccessor(false));
	}

	@Override
	public SkinHeader loadHeader(File p) {
		java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: starting for " + p);
		SkinHeader header = null;
		try {
			lua.setDirectory(p.getParentFile());
			java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: directory set to " + p.getParentFile());
			LuaValue value = lua.execFile(p);
			java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: execFile done, value.istable=" + value.istable());
			// Lua皮肤标准格式: return { header = ..., main = function() ... end }
			// loadHeader 只需要读取 header，header 已经在外层 table，不需要调用 main()
			// 因为 main() 需要 skin_config 全局变量，而 skin_config 此时还未导出
			if (value.istable() && !value.get("header").isnil()) {
				// 从外层table获取header
				java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: found header in outer table");
				value = value.get("header");
			} else if (value.istable() && value.get("main").isfunction()) {
				// 如果header不在外层，fallback到调用main
				java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: calling main()");
				LuaValue mainFunc = value.get("main");
				value = mainFunc.call();
			}
			java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: calling fromLuaValue");
			sk = fromLuaValue(JsonSkin.Skin.class, value);
			java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: calling loadJsonSkinHeader");
			header = loadJsonSkinHeader(sk, p);
			java.util.logging.Logger.getGlobal().info("LuaSkinLoader.loadHeader: success, header=" + (header != null ? header.getName() : "null"));
		} catch (Throwable e) {
			java.util.logging.Logger.getGlobal().severe("LuaSkinLoader.loadHeader: failed for " + p + ", error=" + e.getMessage());
			e.printStackTrace();
		}
		return header;
	}

	@Override
	public Skin loadSkin(File p, SkinType type, SkinConfig.Property property) {
		return load(p, type, property);
	}

	@Override
	public Skin load(File p, SkinType type, SkinConfig.Property property) {
		Skin skin = null;
		SkinHeader header = loadHeader(p);
		if(header == null) {
			java.util.logging.Logger.getGlobal().severe("LuaSkinLoader: loadHeader returned null, skin loading failed");
			return null;
		}
		header.setSkinConfigProperty(property);

		try {
			filemap = new ObjectMap<>();
			for(SkinHeader.CustomFile customFile : header.getCustomFiles()) {
				if(customFile.getSelectedFilename() != null) {
					// 归一化 filemap 的 Key，确保与加载贴图时的 imagePath 格式一致
					String normalizedKey = customFile.path.replace("\\", "/").replaceAll("/+", "/");
					if (normalizedKey.startsWith("/")) normalizedKey = normalizedKey.substring(1);
					normalizedKey = normalizePath(normalizedKey);

					filemap.put(normalizedKey, customFile.getSelectedFilename());
					java.util.logging.Logger.getGlobal().info("LuaSkinLoader: filemap mapping: " + normalizedKey + " -> " + customFile.getSelectedFilename());
				}
			}

			lua.exportSkinProperty(header, property, (String path) -> {
				String rawPath = p.getParent() + "/" + path;
				rawPath = rawPath.replace("\\", "/").replaceAll("/+", "/");
				if (rawPath.startsWith("/")) rawPath = rawPath.substring(1);
				// Normalize ".." parent references for Android AssetManager compatibility
				// (Android assets do not resolve ".." in paths)
				rawPath = SkinLoader.normalizePath(rawPath);
				return getPath(rawPath, filemap).getPath();
			});
			java.util.logging.Logger.getGlobal().info("LuaSkinLoader: Starting full skin load, p=" + p.toString());
			LuaValue value = lua.execFile(p);
			// Lua皮肤标准格式: return { header = ..., main = function() ... end }
			// 需要调用main()函数获取完整的skin定义
			if (value.istable() && value.get("main").isfunction()) {
				java.util.logging.Logger.getGlobal().info("LuaSkinLoader: Found main function, calling it...");
				LuaValue mainFunc = value.get("main");
				value = mainFunc.call();
				java.util.logging.Logger.getGlobal().info("LuaSkinLoader: main function returned, is table=" + value.istable());
			}
			sk = fromLuaValue(JsonSkin.Skin.class, value);
			java.util.logging.Logger.getGlobal().info(String.format("LuaSkinLoader: Conversion complete - source.length=%d, image.length=%d, note=null? %b",
				sk.source != null ? sk.source.length : 0,
				sk.image != null ? sk.image.length : 0,
				sk.note == null));
			skin = loadJsonSkin(header, sk, type, property, p);
			java.util.logging.Logger.getGlobal().info("LuaSkinLoader: loadJsonSkin complete, skin=" + (skin != null ? "success" : "null"));
		} catch (Throwable e) {
			java.util.logging.Logger.getGlobal().severe("LuaSkinLoader: Exception during load: " + e.getMessage());
			e.printStackTrace();
		}
		return skin;
	}

	private Map<Class, Function<LuaValue, Object>> serializerMap = new HashMap<Class, Function<LuaValue, Object>>() {
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
		if (serializerMap.containsKey(cls)) {
			return (T) serializerMap.get(cls).apply(lv);
		} else if (cls.isArray()) {
			Class componentClass = cls.getComponentType();
			if (lv.istable()) {
				LuaTable table = (LuaTable) lv;
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
				Field[] fields = ClassReflection.getFields(cls);
				if (lv.istable()) {
					LuaTable table = (LuaTable)lv;
					for (LuaValue key : table.keys()) {
						String keyName = key.tojstring();
						for (Field field : fields) {
							if (field.getName().equals(keyName)) {
								Object value = fromLuaValue(field.getType(), table.get(key));
								field.set(instance, value);
								break;
							}
						}
					}
				} else if (lv.isuserdata()) {
				}
				return instance;
			} catch (ReflectionException e) {
				return null;
			}
		}
	}
}
