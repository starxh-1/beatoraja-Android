package bms.player.beatoraja.launcher;

import java.net.URL;
import java.util.ResourceBundle;

import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainLoader;
import bms.player.beatoraja.PlayerConfig;

/**
 * Android 空实现
 * 桌面端主设置界面在手机端暂不可用，保留核心状态和空方法防止外部调用报错
 */
public class PlayConfigurationView {

    private Config config;
    private PlayerConfig player;
    private MainLoader loader;
    private boolean songUpdated = false;

    public void initialize(URL arg0, ResourceBundle arg1) {
        // 空实现
    }

    public void setBMSInformationLoader(MainLoader loader) {
        this.loader = loader;
    }

    public void update(Config config) {
        this.config = config;
    }

    public void changePlayer() {
        // 空实现
    }

    public void addPlayer() {
        // 空实现
    }

    public void updatePlayer() {
        // 空实现
    }

    public void commit() {
        // 空实现
    }

    public void commitPlayer() {
        // 空实现
    }

    public void addBGMPath() {
        // 空实现
    }

    public void addSoundPath() {
        // 空实现
    }

    public void updatePlayConfig() {
        // 空实现
    }

    public void start() {
        // 空实现
    }

    public void loadAllBMS() {
        // 空实现
    }

    public void loadDiffBMS() {
        // 空实现
    }

    public void loadBMSPath(String updatepath){
        // 空实现
    }

    public void loadBMS(String updatepath, boolean updateAll) {
        // 空实现
    }

    public void importScoreDataFromLR2() {
        // 空实现
    }

    public void startTwitterAuth() {
        // 空实现
    }

    public void startPINAuth() {
        // 空实现
    }

    public void exit() {
        // 空实现
    }

    // 这个枚举极其重要，绝对不能删，游戏核心逻辑依赖它来判断键盘模式
    public enum PlayMode {
        BEAT_5K("5KEYS"),
        BEAT_7K("7KEYS"),
        BEAT_10K("10KEYS"),
        BEAT_14K("14KEYS"),
        POPN_9K("9KEYS"),
        KEYBOARD_24K("24KEYS"),
        KEYBOARD_24K_DOUBLE("24KEYS DOUBLE");

        public final String name;

        private PlayMode(String name) {
            this.name = name;
        }

        public String toString() {
            return name;
        }
    }
}