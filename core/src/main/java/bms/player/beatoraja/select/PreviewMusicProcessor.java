
package bms.player.beatoraja.select;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.Config.SongPreview;
import bms.player.beatoraja.song.SongData;

/**
 * 選曲画面のプレビュー再生を管理するクラス
 * @author exch
 */
public class PreviewMusicProcessor {

    private final AudioDriver audio;
    private final Config config;
    private String defaultMusic = "sound/select.wav";
    // ConcurrentLinkedQueue: completion listener 也会 enqueue,所以线程安全
    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<String>();
    private PreviewThread preview;
    private SongData current;

    public PreviewMusicProcessor(AudioDriver audio, Config config) {
        this.audio = audio;
        this.config = config;
    }

    public void setDefault(String path) {
        if (path != null) {
            this.defaultMusic = path;
        }
    }

    public void start(String previewPath) {
        if(preview == null) {
            preview = new PreviewThread();
            preview.start();
        }
        // 确保队列中不存入 null
        commands.add(previewPath == null ? "" : previewPath);
    }

    public void start(SongData song) {
        this.current = song;
        if (song == null) {
            start("");
        } else {
            String p = song.getPreview();
            // 验证预览路径是否有效
            if (p != null && p.length() > 0) {
                java.io.File file = new java.io.File(p);
                if (!file.exists()) {
                    java.util.logging.Logger.getGlobal().warning("Preview file does not exist: " + p);
                    p = ""; // 回退到默认音乐
                } else if (!file.canRead()) {
                    java.util.logging.Logger.getGlobal().warning("Preview file is not readable: " + p);
                    p = ""; // 回退到默认音乐
                } else if (file.length() == 0) {
                    java.util.logging.Logger.getGlobal().warning("Preview file is empty: " + p);
                    p = ""; // 回退到默认音乐
                } else {
                    java.util.logging.Logger.getGlobal().info("Starting preview: " + p + " (size: " + file.length() + " bytes)");
                }
            } else {
                p = "";
            }
            start(p);
        }
    }

    public SongData getSongData() {
        return current;
    }

    public void stop() {
        if (preview != null) {
            preview.stop = true;
            preview = null;
        }
    }

    private float getVolume() {
        if (config != null && config.getAudioConfig() != null) {
            return config.getAudioConfig().getSystemvolume();
        }
        return 1.0f;
    }

    class PreviewThread extends Thread {

        private boolean stop;
        // 用 volatile 防止 PreviewThread 主循环与 completion 回调线程看到不一致的 playing 状态
        private volatile String playing;
        private float currentVolume;

        public void run() {
            float vol = getVolume();
            if (defaultMusic != null) {
                try {
                    audio.play(defaultMusic, vol, true);
                } catch (Exception e) {
                    java.util.logging.Logger.getGlobal().warning("Failed to play default music: " + e.getMessage());
                }
            }
            playing = defaultMusic;
            currentVolume = vol;
            while(!stop) {
                String path = commands.poll();
                if(path == null) {
                    try {
                        sleep(50);
                    } catch (InterruptedException e) {
                    }
                    continue;
                }
                if(path.length() == 0) {
                    path = defaultMusic;
                }

                if(!Objects.equals(path, playing)) {
                    stopPreview(true);
                    float v = getVolume();
                    if(!Objects.equals(path, defaultMusic) && path != null) {
                        // 播放 preview 时静音 select BGM
                        if (defaultMusic != null) {
                            try {
                                audio.setVolume(defaultMusic, 0);
                            } catch (Exception e) {
                                java.util.logging.Logger.getGlobal().warning("Failed to mute BGM: " + e.getMessage());
                            }
                        }
                        // 对非循环的 preview 注册 completion 回调,播完后及时恢复 BGM
                        boolean loop = config != null && config.getSongPreview() == SongPreview.LOOP;
                        try {
                            audio.play(path, v, loop);
                        } catch (Exception e) {
                            java.util.logging.Logger.getGlobal().warning("Failed to play preview: " + path + " - " + e.getMessage());
                        }
                        if (!loop) {
                            final String previewPath = path;
                            try {
                                audio.setOnCompletionListener(previewPath, () -> {
                                    // ONCE 播放完毕:native 报告完成,enqueue 空命令让 PreviewThread 走恢复 BGM 分支
                                    if (Objects.equals(playing, previewPath)) {
                                        commands.add("");
                                    }
                                });
                            } catch (Exception e) {
                                java.util.logging.Logger.getGlobal().warning("Failed to set completion listener: " + e.getMessage());
                            }
                        }
                    } else {
                        if (defaultMusic != null) {
                            try {
                                audio.setVolume(defaultMusic, v);
                            } catch (Exception e) {
                                java.util.logging.Logger.getGlobal().warning("Failed to set volume: " + e.getMessage());
                            }
                        }
                    }
                    playing = path;
                } else {
                    float v = getVolume();
                    if(currentVolume != v && playing != null){
                        try {
                            audio.setVolume(playing, v);
                            currentVolume = v;
                        } catch (Exception e) {
                            java.util.logging.Logger.getGlobal().warning("Failed to adjust volume: " + e.getMessage());
                        }
                    }
                }
                try {
                    sleep(50);
                } catch (InterruptedException e) {
                }
            }
            // 线程退出前清理监听器
            if (playing != null) {
                try {
                    audio.setOnCompletionListener(playing, null);
                } catch (Exception ignore) {}
            }
            stopPreview(false);
        }

        private void stopPreview(boolean fadeout) {
            if(playing != null && !playing.equals(defaultMusic)) {
                try {
                    audio.setOnCompletionListener(playing, null);
                } catch (Exception ignore) {}
                try {
                    audio.stop(playing);
                } catch (Exception e) {
                    java.util.logging.Logger.getGlobal().warning("Failed to stop preview: " + e.getMessage());
                }
            }
        }
    }
}
