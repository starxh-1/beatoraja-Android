package com.starxh.beatoraja;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ScreenUtils;

import java.io.File;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.BMSPlayerMode;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.play.BMSPlayer;
import bms.player.beatoraja.result.MusicResult;
import bms.player.beatoraja.result.CourseResult;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class BeatorajaGame extends ApplicationAdapter {
    private MainController controller;
    private SideSpectrumRenderer spectrumRenderer;
    private File rootPath;
    private Config bmsConfig;
    private PlayerConfig playerConfig;
    private BMSPlayerMode mode;
    private boolean useAudio;

    public BeatorajaGame() {
    }

    public BeatorajaGame(File rootPath, Config bmsConfig, PlayerConfig playerConfig, BMSPlayerMode mode, boolean useAudio) {
        this.rootPath = rootPath;
        this.bmsConfig = bmsConfig;
        this.playerConfig = playerConfig;
        this.mode = mode;
        this.useAudio = useAudio;
    }

    @Override
    public void create() {
        // 使用传入的参数初始化 beatoraja 核心控制器
        controller = new MainController(rootPath, bmsConfig, playerConfig, mode, useAudio);
        controller.setBeatorajaGame(this);
        controller.create();
        spectrumRenderer = new SideSpectrumRenderer();
    }

    @Override
    public void render() {
        // 调用控制器的渲染方法
        if (controller != null) {
            controller.render();
        }

        // 渲染频谱（仅在游玩和结果界面显示，且配置中启用了频谱）
        if (spectrumRenderer != null && controller != null) {
            MainState state = controller.getCurrentState();
            if (state != null && (state instanceof BMSPlayer)) {
                Config cfg = controller.getConfig();
                if (cfg != null && cfg.getAudioVisualizationMode() != Config.MODE_OFF) {
                    configureSpectrumRenderer(cfg);
                    spectrumRenderer.render();
                }
            }
        }
    }

    private void configureSpectrumRenderer(Config cfg) {
        spectrumRenderer.setMode(cfg.getAudioVisualizationMode());

        // 检查当前 skin 的 In-Game Spectrum 选项是否开启
        boolean inGameSpectrumOption = true;
        if (controller != null) {
            MainState state = controller.getCurrentState();
            if (state != null) {
                bms.player.beatoraja.skin.Skin skin = state.getSkin();
                if (skin != null && skin.header != null) {
                    bms.player.beatoraja.skin.SkinHeader.CustomOption[] options = skin.header.getCustomOptions();
                    if (options != null) {
                        for (bms.player.beatoraja.skin.SkinHeader.CustomOption opt : options) {
                            if ("In-Game Spectrum".equals(opt.name)) {
                                int selectedOp = opt.getSelectedOption();
                                // op 982 = OFF, op 983 = ON
                                inGameSpectrumOption = (selectedOp == 983);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // 检查当前 skin 是否有 spectrum offset (id=60)
        boolean skinHasSpectrum = false;
        float specX = 0, specY = 0, specW = 0, specH = 0;

        if (controller != null) {
            MainState state = controller.getCurrentState();
            if (state != null) {
                bms.player.beatoraja.skin.Skin skin = state.getSkin();
                if (skin != null && skin.header != null) {
                    bms.player.beatoraja.skin.SkinType skinType = skin.header.getSkinType();
                    bms.player.beatoraja.SkinConfig sc = controller.getPlayerConfig().getSkin()[skinType.getId()];

                    // 读取 skin 目录下的 spectrumconfig.json 作为辅助配置
                    float configX = 0, configY = 0, configW = 0, configH = 0;
                    try {
                        File configFile = null;
                        if (sc != null && sc.getPath() != null) {
                            File parent = new File(sc.getPath()).getParentFile();
                            if (parent != null) {
                                configFile = new File(parent, "spectrumconfig.json");
                            }
                        }
                        if (configFile == null || !configFile.exists()) {
                            // 尝试从 skin header 的 path 获取
                            String headerPath = skin.header.getPath();
                            if (headerPath != null) {
                                File headerParent = new File(headerPath).getParentFile();
                                if (headerParent != null) {
                                    configFile = new File(headerParent, "spectrumconfig.json");
                                }
                            }
                        }
                        if (configFile != null && configFile.exists()) {
                            byte[] data = readFile(configFile);
                            String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                            com.badlogic.gdx.utils.JsonValue jsonValue = new com.badlogic.gdx.utils.JsonReader().parse(json);
                            if (jsonValue != null) {
                                if (jsonValue.has("x")) configX = jsonValue.getFloat("x");
                                if (jsonValue.has("y")) configY = jsonValue.getFloat("y");
                                if (jsonValue.has("w")) configW = jsonValue.getFloat("w");
                                if (jsonValue.has("h")) configH = jsonValue.getFloat("h");
                            }
                        }
                    } catch (Exception e) {
                        com.badlogic.gdx.Gdx.app.log("Spectrum", "Failed to read spectrumconfig.json: " + e.getMessage());
                    }

                    // 检查 skin 是否支持 spectrum（通过 skin header 的 CustomOffset 定义）
                    bms.player.beatoraja.skin.SkinHeader.CustomOffset[] offsets = skin.header.getCustomOffsets();
                    if (offsets != null) {
                        for (bms.player.beatoraja.skin.SkinHeader.CustomOffset off : offsets) {
                            if ("spectrum".equalsIgnoreCase(off.name) || off.name.toLowerCase().contains("spectrum")) {
                                skinHasSpectrum = true;
                                break;
                            }
                        }
                    }

                    // 直接用 PlayerConfig > json > hardcoded
                    bms.player.beatoraja.PlayerConfig playerConfig = controller.getPlayerConfig();
                    int pX = playerConfig != null ? playerConfig.getSpectrumOffsetX() : 0;
                    int pY = playerConfig != null ? playerConfig.getSpectrumOffsetY() : 0;
                    int pW = playerConfig != null ? playerConfig.getSpectrumOffsetW() : 0;
                    int pH = playerConfig != null ? playerConfig.getSpectrumOffsetH() : 0;

                    if (pX != 0) specX = pX;
                    else if (configX != 0) specX = configX;
                    else specX = 680;

                    if (pY != 0) specY = pY;
                    else if (configY != 0) specY = configY;
                    else specY = 10;

                    if (pW != 0) specW = pW;
                    else if (configW != 0) specW = configW;
                    else specW = 320;

                    if (pH != 0) specH = pH;
                    else if (configH != 0) specH = configH;
                    else specH = 80;
                }
            }
        }

        // 如果 skin 没有 spectrum offset 或者 In-Game Spectrum 选项关闭，则在游戏框外（黑边区域）渲染
        if (!skinHasSpectrum || !inGameSpectrumOption) {
            spectrumRenderer.setRenderInGameArea(false);
            spectrumRenderer.setRenderMono(false);
            spectrumRenderer.setGameArea(0, 0, 0, 0);
            return;
        }

        // skin 有 spectrum offset，在游戏内区域渲染
        spectrumRenderer.setRenderInGameArea(true);
        spectrumRenderer.setRenderMono(true);
        //        com.badlogic.gdx.Gdx.app.log("Spectrum", "Skin has spectrum offset, inGameArea=true");
        //        com.badlogic.gdx.Gdx.app.log("Spectrum", "Final setGameArea: x=" + specX + " y=" + specY + " w=" + specW + " h=" + specH);
        spectrumRenderer.setGameArea(specX, specY, specW, specH);
    }

    @Override
    public void resize(int width, int height) {
        if (controller != null) {
            controller.resize(width, height);
        }
        if (spectrumRenderer != null) {
            spectrumRenderer.resize(width, height);
        }
    }

    @Override
    public void pause() {
        if (controller != null) {
            controller.pause();
        }
    }

    @Override
    public void resume() {
        if (controller != null) {
            controller.resume();
        }
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.dispose();
        }
        if (spectrumRenderer != null) {
            spectrumRenderer.dispose();
        }
    }

    private static byte[] readFile(File file) throws java.io.IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        return data;
    }

    /**
     * 获取MainController实例，供Android平台调用
     * @return MainController实例
     */
    public MainController getMainController() {
        return controller;
    }

    /**
     * 更新频谱渲染配置（供FloatingMenu调用）
     */
    public void updateSpectrumConfig() {
        if (controller != null) {
            Config cfg = controller.getConfig();
            if (cfg != null) {
                configureSpectrumRenderer(cfg);
            }
        }
    }
}
