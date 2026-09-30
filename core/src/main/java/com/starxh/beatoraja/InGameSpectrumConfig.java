package com.starxh.beatoraja;

import java.io.File;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.skin.Skin;
import bms.player.beatoraja.skin.SkinType;

/**
 * In-Game Spectrum 区域参数的<b>唯一</b>解析与写回入口。
 *
 * <p>取值优先级：PlayerConfig（浮動菜单调过的值，非 0 才算设置过）
 * → 皮肤目录下的 spectrumconfig.json → 内置默认值。</p>
 *
 * <p>坐标约定：与 JSON/Lua 皮肤自身的 dst 一致，x 从左边起算、<b>y 从底部起算</b>
 * （单位是皮肤坐标，见 {@link SideSpectrumRenderer} 的映射）。</p>
 *
 * <p>之前渲染器和浮動菜单各自解析一遍（菜单还只读 PlayerConfig），两边不一致导致
 * 菜单显示全 0、改了不生效。现在统一到本类。</p>
 */
public final class InGameSpectrumConfig {

    /** 最终兜底默认值：与内置 GenericTheme 的 spectrumconfig.json 保持一致
     *  （左下角、note 密度图右侧）。 */
    public static final int DEFAULT_X = 445;
    public static final int DEFAULT_Y = 7;
    public static final int DEFAULT_W = 220;
    public static final int DEFAULT_H = 50;

    /** 数值边界：防止长按 / 手滑把区域调到离谱的位置 */
    private static final int MIN_POS = -4000;
    private static final int MAX_POS = 4000;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 4000;

    /** spectrumconfig.json 解析结果缓存（仅在渲染线程访问；键 = 路径 + lastModified） */
    private static boolean cachedValid;
    private static String cachedPath;
    private static long cachedModified;
    private static int[] cachedValues;

    private InGameSpectrumConfig() {
    }

    /** 解析当前<b>实际生效</b>的频谱区域，返回 {x, y, w, h} */
    public static int[] resolve(MainController controller) {
        int[] json = readSkinConfig(controller);
        PlayerConfig pc = controller != null ? controller.getPlayerConfig() : null;

        int x = pick(pc != null ? pc.getSpectrumOffsetX() : 0, json, 0, DEFAULT_X);
        int y = pick(pc != null ? pc.getSpectrumOffsetY() : 0, json, 1, DEFAULT_Y);
        int w = pick(pc != null ? pc.getSpectrumOffsetW() : 0, json, 2, DEFAULT_W);
        int h = pick(pc != null ? pc.getSpectrumOffsetH() : 0, json, 3, DEFAULT_H);
        return new int[] { x, y, w, h };
    }

    private static int pick(int playerValue, int[] json, int index, int fallback) {
        if (playerValue != 0) {
            return playerValue;
        }
        if (json != null) {
            return json[index];
        }
        return fallback;
    }

    /**
     * 写回频谱区域（内存 + 立即让渲染器生效），并落盘 PlayerConfig。
     * 值会被裁剪到合理范围。
     */
    public static void apply(MainController controller, int x, int y, int w, int h) {
        if (controller == null) {
            return;
        }
        PlayerConfig pc = controller.getPlayerConfig();
        if (pc == null) {
            return;
        }
        x = clamp(x, MIN_POS, MAX_POS);
        y = clamp(y, MIN_POS, MAX_POS);
        w = clamp(w, MIN_SIZE, MAX_SIZE);
        h = clamp(h, MIN_SIZE, MAX_SIZE);

        pc.setSpectrumOffsetX(x);
        pc.setSpectrumOffsetY(y);
        pc.setSpectrumOffsetW(w);
        pc.setSpectrumOffsetH(h);

        Object game = controller.getBeatorajaGame();
        if (game instanceof BeatorajaGame) {
            ((BeatorajaGame) game).updateSpectrumConfig();
        }
    }

    /** 把 PlayerConfig 落盘（调整结束后调用一次即可，避免长按期间频繁写文件） */
    public static void save(MainController controller) {
        if (controller == null) {
            return;
        }
        PlayerConfig pc = controller.getPlayerConfig();
        Config config = controller.getConfig();
        if (pc != null && config != null) {
            PlayerConfig.write(config.getPlayerpath(), pc);
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * 读取皮肤目录下的 spectrumconfig.json，返回 {x, y, w, h}；不存在或解析失败返回 null。
     *
     * <p>本方法在 PLAY 渲染路径上被<b>每帧</b>调用（{@code BeatorajaGame.configureSpectrumRenderer}），
     * 所以结果按「绝对路径 + lastModified」缓存：文件没变就直接复用同一个 int[]，
     * 不读盘、不解析、不产生每帧分配（性能准入 R1）。换皮肤或改文件（mtime 变化）自动失效。</p>
     */
    public static int[] readSkinConfig(MainController controller) {
        File file = findSkinConfigFile(controller);
        if (file == null || !file.exists()) {
            cachedValid = false;
            cachedPath = null;
            cachedValues = null;
            return null;
        }
        String path = file.getAbsolutePath();
        long modified = file.lastModified();
        if (cachedValid && cachedModified == modified && path.equals(cachedPath)) {
            return cachedValues;
        }

        int[] result = null;
        try {
            // 禁用 java.nio.file 与 File.toPath()：两者都是 API 26 才有的东西，而本应用
            // minSdk 21 —— 真机（Android 5.1.1 / API 22）实测直接 NoSuchMethodError。
            // 用 libGDX 的 FileHandle 读文本。
            String json = new com.badlogic.gdx.files.FileHandle(file).readString("UTF-8");
            com.badlogic.gdx.utils.JsonValue v = new com.badlogic.gdx.utils.JsonReader().parse(json);
            if (v != null) {
                result = new int[] {
                        v.has("x") ? v.getInt("x") : DEFAULT_X,
                        v.has("y") ? v.getInt("y") : DEFAULT_Y,
                        v.has("w") ? v.getInt("w") : DEFAULT_W,
                        v.has("h") ? v.getInt("h") : DEFAULT_H
                };
            }
        } catch (Throwable t) {
            // 这里必须兜 Throwable：NoSuchMethodError / NoClassDefFoundError 都是 Error，
            // 不是 Exception —— 本方法跑在 GLThread 的 render() 里，漏出去就是整个 app FATAL。
            com.badlogic.gdx.Gdx.app.log("Spectrum", "Failed to read spectrumconfig.json: " + t);
        }

        cachedValid = true;
        cachedPath = path;
        cachedModified = modified;
        cachedValues = result;
        return result;
    }

    /** 定位当前皮肤对应的 spectrumconfig.json（皮肤配置目录优先，其次 skin header 所在目录） */
    public static File findSkinConfigFile(MainController controller) {
        if (controller == null) {
            return null;
        }
        MainState state = controller.getCurrentState();
        if (state == null) {
            return null;
        }
        Skin skin = state.getSkin();
        if (skin == null || skin.header == null) {
            return null;
        }
        try {
            PlayerConfig pc = controller.getPlayerConfig();
            if (pc != null && pc.getSkin() != null) {
                SkinType type = skin.header.getSkinType();
                if (type != null && type.getId() >= 0 && type.getId() < pc.getSkin().length) {
                    bms.player.beatoraja.SkinConfig sc = pc.getSkin()[type.getId()];
                    if (sc != null && sc.getPath() != null) {
                        File parent = new File(sc.getPath()).getParentFile();
                        if (parent != null) {
                            File f = new File(parent, "spectrumconfig.json");
                            if (f.exists()) {
                                return f;
                            }
                        }
                    }
                }
            }
            String headerPath = skin.header.getPath();
            if (headerPath != null) {
                File parent = new File(headerPath.toString()).getParentFile();
                if (parent != null) {
                    File f = new File(parent, "spectrumconfig.json");
                    if (f.exists()) {
                        return f;
                    }
                }
            }
        } catch (Throwable t) {
            com.badlogic.gdx.Gdx.app.log("Spectrum", "findSkinConfigFile failed: " + t.getMessage());
        }
        return null;
    }
}
