
package bms.player.beatoraja.select;

import java.util.LinkedList;
import java.util.Objects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;

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
    private final LinkedList<String> commands = new LinkedList<String>();
    private PreviewThread preview;
    private SongData current;
    
    // Android平台：预览音效已禁用（libGDX native崩溃问题）
    private Music androidMusicPlayer = null;
    private String androidMusicPath = null;

    public PreviewMusicProcessor(AudioDriver audio, Config config) {
        this.audio = audio;
        this.config = config;
    }

    public void setDefault(String path) {
        if (path != null) {
            this.defaultMusic = path;
        }
    }
    
    /**
     * Android平台使用Music流式播放（不阻塞）
     */
    private void playAndroidMusic(String path, float volume, boolean loop) {
        if (path == null || path.isEmpty()) {
            return;
        }

        try {
            // 停止之前的播放
            if (androidMusicPlayer != null) {
                androidMusicPlayer.stop();
                androidMusicPlayer.dispose();
                androidMusicPlayer = null;
            }

            // 查找音频文件
            FileHandle fileHandle = null;
            for (FileHandle fh : AudioDriver.getPaths(path)) {
                fileHandle = fh;
                break;
            }

            if (fileHandle == null || !fileHandle.exists()) {
                java.util.logging.Logger.getGlobal().warning("Music file not found: " + path);
                return;
            }

            // 检查文件大小（防止空文件或损坏文件）
            long fileSize = fileHandle.length();
            if (fileSize == 0) {
                java.util.logging.Logger.getGlobal().warning("Music file is empty: " + path);
                return;
            }

            // 创建Music实例（流式播放，不会完整加载文件）
            androidMusicPlayer = Gdx.audio.newMusic(fileHandle);
            androidMusicPlayer.setVolume(volume);
            androidMusicPlayer.setLooping(loop);
            androidMusicPlayer.play();
            androidMusicPath = path;

            java.util.logging.Logger.getGlobal().info("Music started: " + path + " (" + fileSize + " bytes)");

        } catch (Exception e) {
            java.util.logging.Logger.getGlobal().warning("Failed to play music: " + path + " - " + e.getMessage());
            // 确保清理掉可能损坏的player
            if (androidMusicPlayer != null) {
                try {
                    androidMusicPlayer.dispose();
                } catch (Exception ignored) {
                }
                androidMusicPlayer = null;
            }
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
        private String playing;
        private float currentVolume;
        private boolean isAndroid;

        public void run() {
            isAndroid = com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android;

            if (isAndroid) {
                // Android平台：预览音乐已禁用（libGDX Music会native崩溃）
                // 音效(Sound)不受影响，可以正常播放
                while(!stop) {
                    try {
                        commands.pollFirst(); // 使用pollFirst避免竞态条件
                        sleep(50);
                    } catch (InterruptedException e) {
                    }
                }
                return;
            }
            
            // 桌面平台：正常播放
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
                String path = commands.pollFirst();
                if(path == null) {
                    try {
                        sleep(50);
                    } catch (InterruptedException e) {
                    }
                    continue;
                }
                if(path == null || path.length() == 0) {
                    path = defaultMusic;
                }

                if(!Objects.equals(path, playing)) {
                    stopPreview(true);
                    float v = getVolume();
                    if(!Objects.equals(path, defaultMusic) && path != null) {
                        if (!isAndroid) {
                            try {
                                audio.play(path, v, config != null && config.getSongPreview() == SongPreview.LOOP);
                            } catch (Exception e) {
                                java.util.logging.Logger.getGlobal().warning("Failed to play preview: " + path + " - " + e.getMessage());
                            }
                        }
                    } else if (defaultMusic != null && !isAndroid) {
                        try {
                            audio.setVolume(defaultMusic, v);
                        } catch (Exception e) {
                            java.util.logging.Logger.getGlobal().warning("Failed to set volume: " + e.getMessage());
                        }
                    }
                    playing = path;
                } else if(!Objects.equals(playing, defaultMusic) && !audio.isPlaying(playing)){
                    stopPreview(true);
                    if (defaultMusic != null) {
                        try {
                            audio.setVolume(defaultMusic, getVolume());
                        } catch (Exception e) {
                            java.util.logging.Logger.getGlobal().warning("Failed to set default music volume: " + e.getMessage());
                        }
                    }
                    playing = defaultMusic;
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
            stopPreview(false);
        }

        private void stopPreview(boolean fadeout) {
            if(playing != null && !playing.equals(defaultMusic) && !isAndroid) {
                try {
                    audio.stop(playing);
                } catch (Exception e) {
                    java.util.logging.Logger.getGlobal().warning("Failed to stop preview: " + e.getMessage());
                }
            }
        }
    }
}
