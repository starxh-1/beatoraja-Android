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

        // 渲染侧边频谱（画在最上层）
        if (spectrumRenderer != null) {
            spectrumRenderer.render();
        }
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
