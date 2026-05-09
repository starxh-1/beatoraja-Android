package com.starxh.beatoraja;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ScreenUtils;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private Path rootPath;
    private Config bmsConfig;
    private PlayerConfig playerConfig;
    private BMSPlayerMode mode;
    private boolean useAudio;

    public BeatorajaGame() {
    }

    public BeatorajaGame(Path rootPath, Config bmsConfig, PlayerConfig playerConfig, BMSPlayerMode mode, boolean useAudio) {
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
                if (cfg != null && cfg.isShowAudioSpectrum()) {
                    configureSpectrumRenderer(cfg);
                    spectrumRenderer.render();
                }
            }
        }
    }

    private void configureSpectrumRenderer(Config cfg) {
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
                    // 读取 skin 目录下的 spectrumconfig.json 作为默认值
                    float configX = 0, configY = 0, configW = 0, configH = 0;
                    try {
                        java.nio.file.Path skinPath = skin.header.getPath();
                        if (skinPath != null) {
                            java.nio.file.Path configPath = skinPath.getParent().resolve("spectrumconfig.json");
                            if (java.nio.file.Files.exists(configPath)) {
                                String json = new String(java.nio.file.Files.readAllBytes(configPath));
                                Json jsonReader = new Json();
                                java.util.Map<String, Object> config = jsonReader.fromJson(java.util.Map.class, json);
                                if (config != null) {
                                    if (config.get("x") != null) configX = ((Number) config.get("x")).floatValue();
                                    if (config.get("y") != null) configY = ((Number) config.get("y")).floatValue();
                                    if (config.get("w") != null) configW = ((Number) config.get("w")).floatValue();
                                    if (config.get("h") != null) configH = ((Number) config.get("h")).floatValue();
                                }
                            }
                        }
                    } catch (Exception e) {
                        com.badlogic.gdx.Gdx.app.log("Spectrum", "Failed to read spectrumconfig.json: " + e.getMessage());
                    }

                    bms.player.beatoraja.skin.SkinHeader.CustomOffset[] offsets = skin.header.getCustomOffsets();
                    if (offsets != null) {
                        for (bms.player.beatoraja.skin.SkinHeader.CustomOffset off : offsets) {
                            if ("spectrum".equalsIgnoreCase(off.name) || off.name.toLowerCase().contains("spectrum")) {
                                skinHasSpectrum = true;
                                bms.player.beatoraja.SkinConfig.Offset vals = off.getOffset();
                                if (vals != null) {
                                    // 玩家配置优先，其次json配置，最后hardcoded
                                    if (vals.x != 0) specX = vals.x;
                                    else if (configX != 0) specX = configX;
                                    if (vals.y != 0) specY = vals.y;
                                    else if (configY != 0) specY = configY;
                                    if (vals.w != 0) specW = vals.w;
                                    else if (configW != 0) specW = configW;
                                    if (vals.h != 0) specH = vals.h;
                                    else if (configH != 0) specH = configH;
                                } else {
                                    // 使用json配置
                                    if (configX != 0) specX = configX;
                                    if (configY != 0) specY = configY;
                                    if (configW != 0) specW = configW;
                                    if (configH != 0) specH = configH;
                                }
                                // 仍然为0则用hardcoded
                                if (specX == 0) specX = 680;
                                if (specY == 0) specY = 10;
                                if (specW == 0) specW = 320;
                                if (specH == 0) specH = 80;
                                break;
                            }
                        }
                    }
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

    /**
     * 获取MainController实例，供Android平台调用
     * @return MainController实例
     */
    public MainController getMainController() {
        return controller;
    }
}
