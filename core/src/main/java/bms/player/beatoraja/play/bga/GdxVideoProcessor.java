package bms.player.beatoraja.play.bga;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.video.VideoPlayer;
import com.badlogic.gdx.video.VideoPlayerCreator;

import java.io.FileNotFoundException;
import java.nio.IntBuffer;
import java.util.logging.Logger;

/**
 * Android 原生硬件解码视频处理器
 * 基于 LibGDX 官方扩展 gdx-video（底层为 Android MediaCodec）实现，
 *
 * 生命周期注意事项：
 * - 切后台/锁屏时必须调用 pause()
 * - 切回前台时调用 resume()
 * - 退出播放界面时必须调用 dispose() 释放硬件解码器，
 *   否则切歌几次后会导致系统解码器耗尽（Codec Exception）卡死整台手机。
 *
 * 音视频同步机制：
 * - 跟踪游戏时间与视频播放时间的偏差
 * - 当偏差超过阈值时，通过调节视频播放速度或跳帧来同步
 */
public class GdxVideoProcessor implements MovieProcessor {

    private VideoPlayer videoPlayer;
    private String filepath;
    private boolean initialized = false;
    private boolean playing = false;
    private boolean disposed = false;
    private boolean preloaded = false;
    private Texture currentTexture;
    private long lastUpdateTime = -1;        // 记录上一次 update 的时间戳，防止单帧多次更新

    // 音视频同步相关字段
    private long startTime = -1;              // 视频开始播放的系统时间
    private long gameStartTime = -1;         // 对应的游戏开始时间
    private boolean loop = false;            // 是否循环播放
    private long videoDuration = -1;         // 视频时长（毫秒）
    private int syncCorrectionCount = 0;     // 同步修正计数（用于日志）
    private boolean initialSeekDone = false; // play() 后是否已做过初始 seek

    // 异常大漂移阈值 (ms) - 通常是 pause/resume 后或解码卡顿造成的
    private static final long SEVERE_DRIFT_THRESHOLD = 1000;
    private static final long SEEK_COOLDOWN_MS = 200;  // seek 冷却时间 (ms)
    private static final long SYNC_LOG_INTERVAL_MS = 60_000;  // seek log 最小间隔 (ms)
    private static final long SYNC_PERIODIC_LOG_INTERVAL_MS = 30_000; // 周期同步状态 log 间隔 (ms)

    // 快速同步 log 节流：避免漂移时 logcat 刷屏
    private long lastFastSyncLogTime = -1;
    private long lastPeriodicSyncLogTime = -1;
    private long lastSeekTime = -1;          // 上次 seek 的时间戳，用于防 seek storm

    /**
     * 复用的 IntBuffer，用于保存/恢复 GL 状态，避免每帧分配。
     * 容量 16 足以覆盖 viewport(4) + program(1) + fbo(1) 等查询。
     */
    private final IntBuffer glStateBuffer = BufferUtils.newIntBuffer(16);

    private static int instanceCounter = 0;
    private final int instanceId = ++instanceCounter;

    public GdxVideoProcessor() {
        Gdx.app.log("GdxVideoProcessor", "Instance created: #" + instanceId);
    }

    /**
     * 存储视频文件路径。VideoPlayer 将在 GL 线程上延迟初始化，
     * 因此本方法可安全地从后台线程调用（setModel 在后台线程运行）。
     */
    public void create(String filepath) {
        this.filepath = filepath;
    }

    /**
     * 仅预加载解码器（VideoPlayer），不加载视频文件。
     * 解码器创建成功后，后续 play() 时只需加载文件即可，减少首次播放延迟。
     * 此方法必须在 GL 线程上调用。
     */
    @Override
    public void preloadDecoder() {
        if (disposed || initialized) return;
        initialized = true;
        try {
            videoPlayer = VideoPlayerCreator.createVideoPlayer();
            if (videoPlayer != null) {
                videoPlayer.setVolume(0f);
                Logger.getGlobal().info("Video decoder preloaded: " + filepath);
            } else {
                Logger.getGlobal().warning("VideoPlayerCreator returned null - 当前平台可能不支持视频播放");
            }
        } catch (Exception e) {
            Logger.getGlobal().warning("GdxVideoProcessor preloadDecoder failed: " + e.getMessage());
        }
    }

    /**
     * 在 GL 线程上延迟创建 VideoPlayer 实例（内部使用）。
     * @return true 表示初始化成功
     */
    private boolean ensureInitialized() {
        if (!initialized) preloadDecoder();
        return videoPlayer != null;
    }

    @Override
    public void update(long time) {
        if (disposed || videoPlayer == null || !playing) return;

        // 防止单帧多次调用 update 导致硬件解码器状态异常或闪烁
        if (time == lastUpdateTime) return;
        lastUpdateTime = time;

        // 同步纠正：可能内部调用 videoPlayer.seek()
        synchronizeVideo(time);

        try {
            // ─── 核心 GL 状态保存 ───
            glStateBuffer.clear();
            // 1. 保存 Viewport
            Gdx.gl20.glGetIntegerv(GL20.GL_VIEWPORT, glStateBuffer);
            int vx = glStateBuffer.get(0), vy = glStateBuffer.get(1), vw = glStateBuffer.get(2), vh = glStateBuffer.get(3);

            // 2. 保存当前 FBO
            Gdx.gl20.glGetIntegerv(0x8CA6, glStateBuffer); // GL_FRAMEBUFFER_BINDING
            int lastFbo = glStateBuffer.get(0);

            // 3. 保存当前 Shader Program
            Gdx.gl20.glGetIntegerv(0x8B8D, glStateBuffer); // GL_CURRENT_PROGRAM
            int lastProgram = glStateBuffer.get(0);

            // 4. 保存当前 Active Texture
            Gdx.gl20.glGetIntegerv(GL20.GL_ACTIVE_TEXTURE, glStateBuffer);
            int lastActiveTexture = glStateBuffer.get(0);

            // ─── 环境清理 ───
            // 解绑纹理，防止 updateTexImage 冲突。移除 glFlush 以提升触控响应性能。
            Gdx.gl20.glBindTexture(GL20.GL_TEXTURE_2D, 0);

            // ─── 驱动解码器：一次 update 取新帧 ───
            if (videoPlayer.update()) {
                Texture tex = videoPlayer.getTexture();
                if (tex != null) {
                    currentTexture = tex;
                }
            }

            // ─── 现场恢复 ───
            Gdx.gl20.glActiveTexture(lastActiveTexture);
            Gdx.gl20.glUseProgram(lastProgram);
            Gdx.gl20.glBindFramebuffer(GL20.GL_FRAMEBUFFER, lastFbo);
            Gdx.gl20.glViewport(vx, vy, vw, vh);
        } catch (Exception e) {
            Gdx.app.log("GdxVideoProcessor", "Update error: " + e.getMessage());
        }
    }

    @Override
    public Texture getFrame() {
        return currentTexture;
    }

    /**
     * 同步策略：视频是连续流（30fps），游戏是渲染循环（60fps），两者帧率不同。
     * 不做每帧强制对齐——会让视频反复 seek 引起卡顿。
     *
     * 只在以下情况 seek：
     * 1. 首次同步（play 后第一次）：seek 到目标位置，处理 MediaCodec 冷启动延迟
     * 2. 异常大漂移（> 1s）：通常是 pause/resume 后，seek 一次拉齐
     *
     * @param gameTime 当前游戏时间（毫秒）
     */
    private void synchronizeVideo(long gameTime) {
        long targetVideoTime = gameTime - gameStartTime;
        if (targetVideoTime < 0) return;

        if (loop && videoDuration > 0 && targetVideoTime > videoDuration) {
            targetVideoTime = targetVideoTime % videoDuration;
        }

        // 用 VideoPlayer 真实时间戳（来自 MediaPlayer.getCurrentPosition()），
        // 取代之前的 System.currentTimeMillis() 近似，避免暂停/卡顿时漂移放大。
        long currentVideoTime = videoPlayer.getCurrentTimestamp();
        long drift = currentVideoTime - targetVideoTime;

        if (shouldLogPeriodicSync()) {
            Gdx.app.log("GdxVideoProcessor", "Sync: target=" + targetVideoTime + "ms, current=" + currentVideoTime + "ms, drift=" + drift + "ms");
        }
        syncCorrectionCount++;

        // 首次同步：seek 到目标位置，处理 MediaCodec 冷启动延迟（100-300ms）
        if (!initialSeekDone) {
            initialSeekDone = true;
            int target = (int) targetVideoTime;
            try {
                videoPlayer.seek(target);
                lastSeekTime = System.currentTimeMillis();
                if (shouldLogFastSync()) {
                    Logger.getGlobal().info("Video sync: initial seek to " + target + "ms (drift was " + drift + "ms)");
                }
            } catch (Exception e) {
                Logger.getGlobal().warning("Video initial seek failed: " + e.getMessage());
            }
            return;
        }

        // 异常大漂移（> 1s）：通常是 pause/resume 后或解码卡顿
        if (Math.abs(drift) > SEVERE_DRIFT_THRESHOLD) {
            long now = System.currentTimeMillis();
            if (lastSeekTime > 0 && now - lastSeekTime < SEEK_COOLDOWN_MS) {
                return;
            }
            int target = (int) targetVideoTime;
            try {
                videoPlayer.seek(target);
                lastSeekTime = now;
                if (shouldLogFastSync()) {
                    Logger.getGlobal().info("Video sync: severe drift " + drift + "ms, seek to " + target + "ms (count=" + syncCorrectionCount + ")");
                }
            } catch (Exception e) {
                Logger.getGlobal().warning("Video seek failed: " + e.getMessage());
            }
        }
        // 其他情况：不主动同步，让视频自由播放
    }

    /**
     * 快速同步 log 节流：60 秒内只打一次，避免漂移期间 logcat 刷屏。
     * 首次触发（lastFastSyncLogTime == -1）始终打。
     */
    private boolean shouldLogFastSync() {
        long now = System.currentTimeMillis();
        if (lastFastSyncLogTime < 0 || now - lastFastSyncLogTime >= SYNC_LOG_INTERVAL_MS) {
            lastFastSyncLogTime = now;
            return true;
        }
        return false;
    }

    /**
     * 周期同步状态 log 节流：30 秒一次，便于看长期漂移趋势。
     * 首次触发（lastPeriodicSyncLogTime == -1）始终打。
     */
    private boolean shouldLogPeriodicSync() {
        long now = System.currentTimeMillis();
        if (lastPeriodicSyncLogTime < 0 || now - lastPeriodicSyncLogTime >= SYNC_PERIODIC_LOG_INTERVAL_MS) {
            lastPeriodicSyncLogTime = now;
            return true;
        }
        return false;
    }

    @Override
    public void play(long time, boolean loop) {
        if (disposed) {
            disposed = false;
            initialized = false;
            preloaded = false;
        }
        Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video play requested: " + filepath + " (preloaded=" + preloaded + ", time=" + time + ")");
        try {
            if (!ensureInitialized()) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video play failed: ensureInitialized returned false");
                return;
            }

            videoPlayer.setLooping(loop);
            this.loop = loop;

            if (!preloaded) {
                FileHandle fh = Gdx.files.absolute(filepath);
                if (!fh.exists()) {
                    Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video file not found: " + filepath);
                    return;
                }
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Loading video: " + filepath);
                boolean loaded = videoPlayer.load(fh);
                if (!loaded) {
                    Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video load failed: " + filepath);
                    safeDispose();
                    return;
                }
            }

            videoPlayer.play();
            playing = true;

            // 初始化同步时间基准
            startTime = System.currentTimeMillis();
            gameStartTime = time;
            syncCorrectionCount = 0;
            lastFastSyncLogTime = -1;
            lastPeriodicSyncLogTime = -1;
            lastSeekTime = -1;
            initialSeekDone = false;  // play() 后第一次同步会做初始 seek

            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video playback started successfully (gameStartTime=" + time + ")");
        } catch (Exception e) {
            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video play exception: " + e.getMessage());
            playing = false;
            safeDispose();
        }
    }

    @Override
    public void stop() {
        if (disposed || videoPlayer == null) return;
        try {
            videoPlayer.stop();
            playing = false;
            preloaded = false;
            initialized = false;
            startTime = -1;
            gameStartTime = -1;
            lastSeekTime = -1;
            initialSeekDone = false;
            currentTexture = null;
        } catch (Exception e) {
            // 静默失败
        }
    }

    /**
     * 切后台/锁屏时暂停视频。
     * Android MediaPlayer 对生命周期极敏感，必须及时暂停。
     */
    @Override
    public void pause() {
        if (disposed || videoPlayer == null || !playing) return;
        try {
            videoPlayer.pause();
        } catch (Exception e) {
            // 静默失败
        }
    }

    /**
     * 切回前台时恢复播放。
     * 注：gdx-video 的 resume() 虽标记为 @Deprecated 但仍可使用，
     * 在没有替代 API 之前保持使用。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void resume() {
        if (disposed || videoPlayer == null || !playing) return;
        try {
            videoPlayer.resume();
            // 恢复后由同步纠正机制自动拉齐：gameStartTime 不变，
            // videoPlayer.getCurrentTimestamp() 返回实际位置，synchronizeVideo()
            // 检测到偏差后会 seek 到目标位置。
        } catch (Exception e) {
            // 静默失败
        }
    }

    /**
     * 严格释放底层硬件解码器资源。
     * 不调用此方法，切歌几次后会导致系统解码器耗尽（Codec Exception）卡死整台手机。
     */
    @Override
    public void dispose() {
        safeDispose();
    }

    /**
     * 安全释放所有资源，任何异常均被捕获，绝不让释放过程导致游戏崩溃。
     */
    private void safeDispose() {
        disposed = true;
        initialized = false;
        preloaded = false;
        playing = false;
        startTime = -1;
        gameStartTime = -1;
        if (videoPlayer != null) {
            try {
                videoPlayer.dispose();
            } catch (Exception e) {
                Logger.getGlobal().warning("GdxVideoProcessor dispose error (已忽略): " + e.getMessage());
            }
            videoPlayer = null;
        }
        // currentTexture 由 VideoPlayer 内部管理，dispose videoPlayer 时一并释放
        currentTexture = null;
    }

    /**
     * gdx-video 输出标准 RGBA 纹理，使用 Linear 滤镜 + 默认 Shader 即可，
     * 不需要 FFmpeg 专用的 YUV→RGB 转换 Shader。
     */
    @Override
    public int getRenderType() {
        return 1; // SkinObjectRenderer.TYPE_LINEAR
    }

    /**
     * 预加载视频资源：预先初始化 VideoPlayer 并加载视频文件
     * 这样可以避免第一次播放时的延迟（创建解码器、加载文件等）
     * 此方法必须在 GL 线程上调用（通过 Gdx.app.postRunnable）
     *
     * P0: 预热解码器流水线。load 之后立刻 play→等第一帧→pause，
     * 让 MediaCodec 启动解码线程、填缓冲区、产出第一帧，
     * 避免真实 play() 时冷启动消耗 100~300ms。
     * 代价：真实 play() 会从 warmup 暂停处（~30-50ms）恢复，
     *      产生小起始偏移，由同步纠正机制吸收。
     */
    @Override
    public void preload() {
        if (disposed || preloaded) return;
        Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload requested for: " + filepath);

        try {
            // 1. 初始化 VideoPlayer（在 GL 线程上）
            if (!ensureInitialized()) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload: ensureInitialized failed");
                return;
            }

            // 2. 加载视频文件（但不播放）
            FileHandle fh = Gdx.files.absolute(filepath);
            if (!fh.exists()) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload: file not found: " + filepath);
                return;
            }

            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Loading video for preload...");
            boolean loaded = videoPlayer.load(fh);
            if (!loaded) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload: load failed: " + filepath);
                safeDispose();
                return;
            }

            // 3. 预热：play→等第一帧→pause，预算 500ms
            long warmupStart = System.currentTimeMillis();
            long warmupDeadline = warmupStart + 500;
            boolean firstFrameDecoded = false;
            videoPlayer.play();
            while (System.currentTimeMillis() < warmupDeadline) {
                if (videoPlayer.update()) {
                    firstFrameDecoded = true;
                    break;
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            videoPlayer.pause();
            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId
                    + " preload: decoder warmed" + (firstFrameDecoded ? "" : " (timeout)")
                    + " in " + (System.currentTimeMillis() - warmupStart) + "ms");

            // 标记为已预加载，play() 将跳过 load() 直接播放
            preloaded = true;
            playing = false;

            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video preloaded successfully: " + filepath);
        } catch (Exception e) {
            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video preload failed: " + e.getMessage());
            safeDispose();
        }
    }
}
