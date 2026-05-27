package bms.player.beatoraja.play.bga;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.BufferUtils;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.logging.Logger;

/**
 * Media3 ExoPlayer-based video processor for BGA playback.
 *
 * Handles formats unsupported by gdx-video: wmv, mpg, avi, mkv, flv, etc.
 *
 * Uses reflection to access Media3 APIs at runtime to avoid compile-time Android dependencies in core module.
 *
 * Lifecycle:
 * - pause()/resume() for app lifecycle events
 * - dispose() must be called when done to release ExoPlayer resources
 */
public class ExoPlayerProcessor implements MovieProcessor {

    private Object player;
    private String filepath;
    private boolean disposed = false;
    private boolean playing = false;
    private boolean preloaded = false;

    private Texture currentTexture;
    private Pixmap framePixmap;
    private int lastWidth = -1;
    private int lastHeight = -1;

    private final IntBuffer glStateBuffer = BufferUtils.newIntBuffer(16);

    private long startTime = -1;
    private long gameStartTime = -1;
    private boolean loop = false;

    private static int instanceCounter = 0;
    private final int instanceId = ++instanceCounter;

    private static Class<?> exoPlayerClass;
    private static Class<?> mediaItemClass;
    private static Method setMediaItemMethod;
    private static Method prepareMethod;
    private static Method playMethod;
    private static Method pauseMethod;
    private static Method stopMethod;
    private static Method releaseMethod;
    private static Method setLoopingMethod;
    private static Method seekToMethod;
    private static Method clearMediaItemsMethod;
    private static Method getCurrentBitmapMethod;
    private static Method builderBuildMethod;
    private static Method builderSetApplicationMethod;

    static {
        try {
            exoPlayerClass = Class.forName("androidx.media3.exoplayer.ExoPlayer");
            mediaItemClass = Class.forName("androidx.media3.common.MediaItem");

            Class<?> builderClass = Class.forName("androidx.media3.exoplayer.ExoPlayer$Builder");
            builderSetApplicationMethod = builderClass.getMethod("setApplication", Class.forName("android.app.Application"));
            builderBuildMethod = builderClass.getMethod("build");

            setMediaItemMethod = exoPlayerClass.getMethod("setMediaItem", mediaItemClass);
            prepareMethod = exoPlayerClass.getMethod("prepare");
            playMethod = exoPlayerClass.getMethod("play");
            pauseMethod = exoPlayerClass.getMethod("pause");
            stopMethod = exoPlayerClass.getMethod("stop");
            releaseMethod = exoPlayerClass.getMethod("release");
            setLoopingMethod = exoPlayerClass.getMethod("setLooping", boolean.class);
            seekToMethod = exoPlayerClass.getMethod("seekTo", long.class);
            clearMediaItemsMethod = exoPlayerClass.getMethod("clearMediaItems");
            getCurrentBitmapMethod = exoPlayerClass.getMethod("getCurrentBitmap");

            Logger.getGlobal().info("ExoPlayer Media3 reflection initialized successfully");
        } catch (Exception e) {
            Logger.getGlobal().warning("Failed to initialize ExoPlayer Media3 reflection: " + e.getMessage());
        }
    }

    public ExoPlayerProcessor() {
        Gdx.app.log("ExoPlayerProcessor", "Instance created: #" + instanceId);
    }

    public void create(String filepath) {
        this.filepath = filepath;
    }

    @Override
    public void preloadDecoder() {
        if (disposed || player != null) return;
        if (exoPlayerClass == null) return;

        try {
            Class<?> builderClass = Class.forName("androidx.media3.exoplayer.ExoPlayer$Builder");
            Object builder = builderClass.getConstructor().newInstance();
            Object app = Gdx.app;
            builderSetApplicationMethod.invoke(builder, app);
            player = builderBuildMethod.invoke(builder);
            Logger.getGlobal().info("ExoPlayer decoder preloaded: " + filepath);
        } catch (Exception e) {
            Logger.getGlobal().warning("ExoPlayerProcessor preloadDecoder failed: " + e.getMessage());
        }
    }

    @Override
    public void preload() {
        if (disposed || preloaded) return;
        if (player == null) preloadDecoder();
        if (player == null) return;

        try {
            Method fromUri = mediaItemClass.getMethod("fromUri", String.class);
            Object mediaItem = fromUri.invoke(null, filepath);
            setMediaItemMethod.invoke(player, mediaItem);
            prepareMethod.invoke(player);
            preloaded = true;
            Gdx.app.log("ExoPlayerProcessor", "Instance #" + instanceId + " preloaded: " + filepath);
        } catch (Exception e) {
            Gdx.app.log("ExoPlayerProcessor", "Instance #" + instanceId + " preload failed: " + e.getMessage());
        }
    }

    @Override
    public void play(long time, boolean loop) {
        if (disposed) {
            disposed = false;
            preloaded = false;
        }

        Gdx.app.log("ExoPlayerProcessor", "Instance #" + instanceId + " Video play: " + filepath + " (time=" + time + ", loop=" + loop + ")");

        try {
            if (player == null) {
                preloadDecoder();
                if (player == null) {
                    Gdx.app.log("ExoPlayerProcessor", "Instance #" + instanceId + " play failed: player is null");
                    return;
                }
            }

            this.loop = loop;
            setLoopingMethod.invoke(player, loop);

            if (!preloaded) {
                Method fromUri = mediaItemClass.getMethod("fromUri", String.class);
                Object mediaItem = fromUri.invoke(null, filepath);
                setMediaItemMethod.invoke(player, mediaItem);
                prepareMethod.invoke(player);
                preloaded = true;
            }

            if (time > 0) {
                seekToMethod.invoke(player, time);
            }

            playMethod.invoke(player);
            playing = true;

            startTime = System.currentTimeMillis();
            gameStartTime = time;

            Gdx.app.log("ExoPlayerProcessor", "Instance #" + instanceId + " playback started");
        } catch (Exception e) {
            Gdx.app.log("ExoPlayerProcessor", "Instance #" + instanceId + " play exception: " + e.getMessage());
            playing = false;
        }
    }

    @Override
    public void update(long time) {
        if (disposed || player == null || !playing) return;

        try {
            glStateBuffer.clear();
            Gdx.gl20.glGetIntegerv(GL20.GL_VIEWPORT, glStateBuffer);
            int vx = glStateBuffer.get(0), vy = glStateBuffer.get(1), vw = glStateBuffer.get(2), vh = glStateBuffer.get(3);

            Gdx.gl20.glGetIntegerv(0x8CA6, glStateBuffer);
            int lastFbo = glStateBuffer.get(0);
            Gdx.gl20.glGetIntegerv(0x8B8D, glStateBuffer);
            int lastProgram = glStateBuffer.get(0);
            Gdx.gl20.glGetIntegerv(GL20.GL_ACTIVE_TEXTURE, glStateBuffer);
            int lastActiveTexture = glStateBuffer.get(0);

            Gdx.gl20.glBindTexture(GL20.GL_TEXTURE_2D, 0);

            Object bitmap = getCurrentBitmapMethod.invoke(player);
            if (bitmap != null) {
                Class<?> bitmapClass = Class.forName("android.graphics.Bitmap");
                Method getWidth = bitmapClass.getMethod("getWidth");
                Method getHeight = bitmapClass.getMethod("getHeight");
                Method getPixels = bitmapClass.getMethod("getPixels", int[].class, int.class, int.class, int.class, int.class, int.class, int.class);

                int width = (Integer) getWidth.invoke(bitmap);
                int height = (Integer) getHeight.invoke(bitmap);

                if (framePixmap == null || width != lastWidth || height != lastHeight) {
                    if (framePixmap != null) framePixmap.dispose();
                    framePixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
                    lastWidth = width;
                    lastHeight = height;
                }

                int[] pixels = new int[width * height];
                getPixels.invoke(bitmap, pixels, 0, width, 0, 0, width, height);
                // Convert int[] ARGB to Pixmap RGBA8888 format
                ByteBuffer pixelsBuf = BufferUtils.newByteBuffer(pixels.length * 4);
                for (int pixel : pixels) {
                    // ARGB to RGBA: AARRGGBB -> RRGGBBAA
                    int a = (pixel >> 24) & 0xFF;
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    pixelsBuf.put((byte) r);
                    pixelsBuf.put((byte) g);
                    pixelsBuf.put((byte) b);
                    pixelsBuf.put((byte) a);
                }
                pixelsBuf.position(0);
                framePixmap.setPixels(pixelsBuf);

                if (currentTexture != null &&
                    (currentTexture.getWidth() != width || currentTexture.getHeight() != height)) {
                    currentTexture.dispose();
                    currentTexture = null;
                }

                if (currentTexture == null) {
                    currentTexture = new Texture(framePixmap);
                } else {
                    currentTexture.draw(framePixmap, 0, 0);
                }
            }

            Gdx.gl20.glActiveTexture(lastActiveTexture);
            Gdx.gl20.glUseProgram(lastProgram);
            Gdx.gl20.glBindFramebuffer(GL20.GL_FRAMEBUFFER, lastFbo);
            Gdx.gl20.glViewport(vx, vy, vw, vh);

        } catch (Exception e) {
            Gdx.app.log("ExoPlayerProcessor", "Update error: " + e.getMessage());
        }
    }

    @Override
    public Texture getFrame() {
        return currentTexture;
    }

    @Override
    public void stop() {
        if (disposed || player == null) return;
        try {
            stopMethod.invoke(player);
            clearMediaItemsMethod.invoke(player);
            playing = false;
            preloaded = false;
            startTime = -1;
            gameStartTime = -1;
            currentTexture = null;
        } catch (Exception e) {
        }
    }

    @Override
    public void pause() {
        if (disposed || player == null || !playing) return;
        try {
            pauseMethod.invoke(player);
        } catch (Exception e) {
        }
    }

    @Override
    public void resume() {
        if (disposed || player == null || !playing) return;
        try {
            playMethod.invoke(player);
            if (gameStartTime >= 0) {
                startTime = System.currentTimeMillis();
            }
        } catch (Exception e) {
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        playing = false;
        if (player != null) {
            try {
                releaseMethod.invoke(player);
            } catch (Exception e) {
                Logger.getGlobal().warning("ExoPlayerProcessor dispose error (ignored): " + e.getMessage());
            }
            player = null;
        }
        if (currentTexture != null) {
            currentTexture.dispose();
            currentTexture = null;
        }
        if (framePixmap != null) {
            framePixmap.dispose();
            framePixmap = null;
        }
    }

    @Override
    public int getRenderType() {
        return 1;
    }
}