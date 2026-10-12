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
    private boolean songUpdated;

    public BeatorajaGame() {
    }

    public BeatorajaGame(File rootPath, Config bmsConfig, PlayerConfig playerConfig, BMSPlayerMode mode, boolean songUpdated) {
        this.rootPath = rootPath;
        this.bmsConfig = bmsConfig;
        this.playerConfig = playerConfig;
        this.mode = mode;
        this.songUpdated = songUpdated;
    }

    @Override
    public void create() {
        // 使用传入的参数初始化 beatoraja 核心控制器
        controller = new MainController(rootPath, bmsConfig, playerConfig, mode, songUpdated);
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

        // 渲染频谱：只在游玩（PLAY）界面显示。
        // 调整页同样只在 PLAY 可进入（FloatingMenu 的入口项仅 PLAY 可见），
        // 所以这里无需为"其他界面预览"开特例。
        if (spectrumRenderer != null && controller != null) {
            MainState state = controller.getCurrentState();
            if (state instanceof BMSPlayer) {
                Config cfg = controller.getConfig();
                if (cfg != null && cfg.getEffectiveAudioVisualizationMode() != Config.MODE_OFF) {
                    configureSpectrumRenderer(cfg, controller.isSpectrumAdjustOpen());
                    spectrumRenderer.render();
                }
            }
        }
    }

    private void configureSpectrumRenderer(Config cfg, boolean adjusting) {
        // 读 effective：磁盘字段是「全局兜底值」，运行期用它会无视当前玩家的设置。
        spectrumRenderer.setMode(cfg.getEffectiveAudioVisualizationMode());

        // 频谱坐标所处的空间 = 皮肤 header 的 w/h，加上主渲染当前的真实视口矩形。
        // 两者必须每帧同步：皮肤可能是 1280x720 / 1920x1080，且开启"拉伸全屏"时
        // 视口不再等于等比矩形 —— 频谱区域要按同一套映射贴到屏幕上。
        if (controller != null) {
            spectrumRenderer.setViewportRect(controller.getViewportX(), controller.getViewportY(),
                    controller.getViewportW(), controller.getViewportH());
        }

        // 检查当前 skin 的 In-Game Spectrum 选项是否开启
        boolean inGameSpectrumOption = true;
        if (controller != null) {
            MainState state = controller.getCurrentState();
            if (state != null) {
                bms.player.beatoraja.skin.Skin skin = state.getSkin();
                if (skin != null && skin.header != null) {
                    spectrumRenderer.setSkinSpace(skin.getWidth(), skin.getHeight());
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
        int[] area = InGameSpectrumConfig.resolve(controller);

        if (controller != null) {
            MainState state = controller.getCurrentState();
            if (state != null) {
                bms.player.beatoraja.skin.Skin skin = state.getSkin();
                if (skin != null && skin.header != null) {
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
                }
            }
        }

        // 调整页打开时无条件按游戏内区域渲染：即使皮肤把 In-Game Spectrum 选项关掉
        // （那会把频谱画到黑边），调整中也按目标区域显示，否则调位置看不到效果。
        if (adjusting) {
            spectrumRenderer.setRenderInGameArea(true);
            spectrumRenderer.setRenderMono(true);
            spectrumRenderer.setGameArea(area[0], area[1], area[2], area[3]);
            return;
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
        spectrumRenderer.setGameArea(area[0], area[1], area[2], area[3]);
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

    /**
     * 更新频谱渲染配置（供 FloatingMenu / InGameSpectrumConfig 调用）
     */
    public void updateSpectrumConfig() {
        if (controller != null) {
            Config cfg = controller.getConfig();
            if (cfg != null) {
                configureSpectrumRenderer(cfg, controller.isSpectrumAdjustOpen());
            }
        }
    }
}
