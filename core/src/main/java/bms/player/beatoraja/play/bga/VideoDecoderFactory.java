package bms.player.beatoraja.play.bga;

/**
 * Factory for creating MovieProcessor instances with format-based routing.
 *
 * Strategy:
 * 1. Try GdxVideoProcessor first (handles mp4, webm, m4v natively via MediaCodec)
 * 2. If format is unsupported (wmv, mpg, avi, mkv, flv) or GdxVideoProcessor fails,
 *    fall back to ExoPlayerProcessor
 *
 * This avoids creating both decoders simultaneously (MediaCodec pool conflict).
 */
public class VideoDecoderFactory {

    /**
     * Format extensions that gdx-video cannot handle.
     * These need ExoPlayer fallback.
     */
    private static final String[] FALLBACK_EXTENSIONS = {
        "wmv", "mpg", "mpeg", "avi", "mkv", "flv", "m1v", "m2v"
    };

    /**
     * Format extensions that gdx-video handles natively.
     */
    private static final String[] PRIMARY_EXTENSIONS = {
        "mp4", "webm", "m4v"
    };

    /**
     * Create a MovieProcessor appropriate for the given filepath.
     *
     * @param filepath Absolute path to the video file
     * @return MovieProcessor instance (GdxVideoProcessor or ExoPlayerProcessor)
     */
    public static MovieProcessor create(String filepath) {
        String extension = getExtension(filepath);

        if (isFallbackFormat(extension)) {
            ExoPlayerProcessor exo = new ExoPlayerProcessor();
            exo.create(filepath);
            return exo;
        } else {
            GdxVideoProcessor gdx = new GdxVideoProcessor();
            gdx.create(filepath);
            return gdx;
        }
    }

    /**
     * Check if a format should use ExoPlayer fallback.
     */
    private static boolean isFallbackFormat(String extension) {
        if (extension == null) return true;
        extension = extension.toLowerCase();
        for (String ext : FALLBACK_EXTENSIONS) {
            if (ext.equals(extension)) return true;
        }
        return false;
    }

    /**
     * Get file extension from filepath.
     */
    private static String getExtension(String filepath) {
        if (filepath == null) return null;
        int lastDot = filepath.lastIndexOf('.');
        if (lastDot < 0) return null;
        return filepath.substring(lastDot + 1);
    }
}