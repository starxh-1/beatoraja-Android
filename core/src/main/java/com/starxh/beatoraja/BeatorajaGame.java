package com.starxh.beatoraja;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;

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
                    // 从 skin 的 destination 信息获取 frame_spectrum 的实际尺寸
                    float frameW = 0, frameH = 0;
                    com.badlogic.gdx.Gdx.app.log("Spectrum", "Total skin objects: " + skin.getAllSkinObjects().length);
                    for (bms.player.beatoraja.skin.SkinObject obj : skin.getAllSkinObjects()) {
                        int[] offsetIds = obj.getOffsetID();
                        com.badlogic.gdx.Gdx.app.log("Spectrum", "Object offsetIds length: " + (offsetIds != null ? offsetIds.length : 0));
                        if (offsetIds != null && offsetIds.length > 0) {
                            for (int oid : offsetIds) {
                                com.badlogic.gdx.Gdx.app.log("Spectrum", "Checking offset id: " + oid);
                                if (oid == 60) {
                                    bms.player.beatoraja.skin.SkinObject.SkinObjectDestination[] dests = obj.getAllDestination();
                                    if (dests != null && dests.length > 0) {
                                        bms.player.beatoraja.skin.SkinObject.SkinObjectDestination dest = dests[0];
                                        frameW = dest.region.width;
                                        frameH = dest.region.height;
                                        com.badlogic.gdx.Gdx.app.log("Spectrum", "Found frame_spectrum! frameW=" + frameW + " frameH=" + frameH);
                                    }
                                    break;
                                }
                            }
                            // 只有找到60才break，否则继续检查下一个对象
                        }
                    }
                    com.badlogic.gdx.Gdx.app.log("Spectrum", "Final frameW=" + frameW + " frameH=" + frameH);

                    bms.player.beatoraja.skin.SkinHeader.CustomOffset[] offsets = skin.header.getCustomOffsets();
                    if (offsets != null) {
                        for (bms.player.beatoraja.skin.SkinHeader.CustomOffset off : offsets) {
                            if ("spectrum".equalsIgnoreCase(off.name) || off.name.toLowerCase().contains("spectrum")) {
                                skinHasSpectrum = true;
                                bms.player.beatoraja.SkinConfig.Offset vals = off.getOffset();
                                if (vals != null) {
                                    // 只有玩家明确设置过（非0）才用玩家配置，否则用skin自身的值
                                    if (vals.x != 0) specX = vals.x;
                                    if (vals.y != 0) specY = vals.y;
                                    if (vals.w != 0) specW = vals.w;
                                    else if (frameW > 0) specW = frameW;
                                    if (vals.h != 0) specH = vals.h;
                                    else if (frameH > 0) specH = frameH;
                                } else {
                                    // offset 为 null，使用 frame_spectrum 的实际尺寸
                                    if (frameW > 0) specW = frameW;
                                    if (frameH > 0) specH = frameH;
                                }
                                // x/y 为 0 时使用默认值，与 Lua skin 保持一致
                                if (specX == 0) specX = 680;
                                if (specY == 0) specY = 10;
                                // w/h 为 0 时使用默认值
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
