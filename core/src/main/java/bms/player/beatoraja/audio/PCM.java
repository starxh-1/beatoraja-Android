package bms.player.beatoraja.audio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.BufferUtils;
import java.lang.reflect.Method;


/**
 * PCM音源処理用クラス (Android 适配版)
 */
public abstract class PCM<T> {

    protected static final boolean USE_UNSAFE = false;

    public final int channels;
    public final int sampleRate;
    public final T sample;
    public final int start;
    public final int len;

    PCM(int channels, int sampleRate, int start, int len, T sample) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.start = start;
        this.len = len;
        this.sample = sample;
    }

    public static PCM load(String pathStr, AudioDriver driver) {
        FileHandle handle = Gdx.files.absolute(pathStr);
        if (!handle.exists()) {
            handle = Gdx.files.internal(pathStr);
        }
        return load(handle, driver);
    }

    public static PCM load(FileHandle handle, AudioDriver driver) {
        if (handle == null || !handle.exists()) {
            return null;
        }
        try {
            PCMLoader loader = new PCMLoader(driver);
            loader.loadPCM(handle);

            PCM pcm = null;
            if(loader.bitsPerSample > 16) {
                pcm = FloatPCM.loadPCM(loader);
            } else if(loader.bitsPerSample == 16) {
                if(loader.pcm.isDirect()) {
                    pcm = ShortDirectPCM.loadPCM(loader);
                } else {
                    pcm = ShortPCM.loadPCM(loader);
                }
            } else {
                pcm = ShortPCM.loadPCM(loader);
            }

            if(pcm != null && ((AbstractAudioDriver)driver).channels != 0 && pcm.channels != ((AbstractAudioDriver)driver).channels) {
                pcm = pcm.changeChannels(((AbstractAudioDriver)driver).channels);
            }
            if(pcm != null && ((AbstractAudioDriver)driver).getSampleRate() != 0 && pcm.sampleRate != ((AbstractAudioDriver)driver).getSampleRate()) {
                pcm = pcm.changeSampleRate(((AbstractAudioDriver)driver).getSampleRate());
            }

            if(pcm != null && pcm.validate()) {
                return pcm;
            } else {
                Logger.getGlobal().warning("音源読み込み失敗: " + handle.path());
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 快速读取音频文件头获取时长（毫秒）。支持 WAV, FLAC, OGG Vorbis。
     * 仅解析文件头/尾，不进行解码，极低开销。
     * @return 时长（毫秒），读取失败返回 0
     */
    public static int getWavDurationMs(String path) {
        FileHandle handle = Gdx.files.absolute(path);
        if (!handle.exists()) {
            handle = Gdx.files.internal(path);
        }
        if (!handle.exists()) {
            return 0;
        }
        File file = handle.file();

        int dur = tryWavDuration(file);
        if (dur > 0) return dur;
        dur = tryFlacDuration(file);
        if (dur > 0) return dur;
        dur = tryOggDuration(file);
        if (dur > 0) return dur;

        return 0;
    }

    private static int tryWavDuration(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] header = new byte[44];
            if (in.read(header) < 44) return 0;
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') return 0;
            int channels = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8);
            int sampleRate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8)
                | ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
            int bitsPerSample = (header[34] & 0xFF) | ((header[35] & 0xFF) << 8);
            int dataSize = (header[40] & 0xFF) | ((header[41] & 0xFF) << 8)
                | ((header[42] & 0xFF) << 16) | ((header[43] & 0xFF) << 24);
            if (sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0) return 0;
            int totalSamples = dataSize / (bitsPerSample / 8) / channels;
            return (int) (totalSamples * 1000L / sampleRate);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * FLAC STREAMINFO 解析。STREAMINFO 必须是第一个 metadata block。
     * 格式: "fLaC"(4) + STREAMINFO header(4) + data(34) = 42 bytes
     */
    private static int tryFlacDuration(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] header = new byte[42];
            if (in.read(header) < 42) return 0;
            if (header[0] != 'f' || header[1] != 'L' || header[2] != 'a' || header[3] != 'C') return 0;
            // metadata block header: bit 0=last, bits 1-7=type(0=STREAMINFO), bits 8-31=length
            if ((header[4] & 0x7F) != 0) return 0;
            // STREAMINFO at offset 8: sample_rate(20) + channels-1(3) + bps-1(5) + total_samples(36) = 64 bits
            int sampleRate = ((header[18] & 0xFF) << 12) | ((header[19] & 0xFF) << 4) | ((header[20] & 0xF0) >>> 4);
            long totalSamples = ((long)(header[21] & 0x0F) << 32)
                | ((long)(header[22] & 0xFF) << 24)
                | ((long)(header[23] & 0xFF) << 16)
                | ((long)(header[24] & 0xFF) << 8)
                | (long)(header[25] & 0xFF);
            if (sampleRate <= 0) return 0;
            return (int) (totalSamples * 1000L / sampleRate);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * OGG Vorbis 时长解析。读取开头获取 sampleRate，读取末尾获取 granule position。
     */
    private static int tryOggDuration(File file) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");

            // 读取开头 4KB 获取 Vorbis ID header 中的 sample rate
            byte[] head = new byte[4096];
            int read = raf.read(head);
            if (read < 100) return 0;

            // 找第一个 "OggS"
            int oggs = -1;
            for (int i = 0; i < read - 4; i++) {
                if (head[i] == 'O' && head[i+1] == 'g' && head[i+2] == 'g' && head[i+3] == 'S') {
                    oggs = i; break;
                }
            }
            if (oggs < 0) return 0;

            int segCount = head[oggs + 26] & 0xFF;
            int dataStart = oggs + 27 + segCount;
            if (dataStart >= read) return 0;

            int dataLen = 0;
            for (int i = 0; i < segCount; i++) dataLen += head[oggs + 27 + i] & 0xFF;
            int dataEnd = Math.min(dataStart + dataLen, read);

            // 在第一页数据中找 "vorbis" 定位 ID header
            int vorbisPos = -1;
            for (int i = dataStart; i < dataEnd - 6; i++) {
                if (head[i] == 'v' && head[i+1] == 'o' && head[i+2] == 'r' && head[i+3] == 'b'
                    && head[i+4] == 'i' && head[i+5] == 's') {
                    vorbisPos = i; break;
                }
            }
            if (vorbisPos < 0) return 0;

            // Vorbis ID: packet_type(1) + "vorbis"(6) + version(4) + channels(1) + sample_rate(4)
            int srOffset = vorbisPos + 12;
            if (srOffset + 4 >= read) return 0;
            int sampleRate = (head[srOffset] & 0xFF) | ((head[srOffset+1] & 0xFF) << 8)
                | ((head[srOffset+2] & 0xFF) << 16) | ((head[srOffset+3] & 0xFF) << 24);
            if (sampleRate <= 0) return 0;

            // 读取末尾 100KB 找最后一个 OggS page 的 granule position
            long fileLen = raf.length();
            int tailSize = (int) Math.min(100000, fileLen);
            byte[] tail = new byte[tailSize];
            raf.seek(fileLen - tailSize);
            raf.readFully(tail);

            int lastOggs = -1;
            for (int i = tailSize - 26; i >= 0; i--) {
                if (tail[i] == 'O' && tail[i+1] == 'g' && tail[i+2] == 'g' && tail[i+3] == 'S') {
                    lastOggs = i; break;
                }
            }
            if (lastOggs < 0) return 0;

            // granule position at OggS+6, 8 bytes little-endian
            long granule = (tail[lastOggs+6] & 0xFFL)
                | ((tail[lastOggs+7] & 0xFFL) << 8)
                | ((tail[lastOggs+8] & 0xFFL) << 16)
                | ((tail[lastOggs+9] & 0xFFL) << 24)
                | ((tail[lastOggs+10] & 0xFFL) << 32)
                | ((tail[lastOggs+11] & 0xFFL) << 40)
                | ((tail[lastOggs+12] & 0xFFL) << 48)
                | ((tail[lastOggs+13] & 0xFFL) << 56);
            if (granule <= 0) return 0;
            return (int) (granule * 1000L / sampleRate);
        } catch (Exception e) {
            return 0;
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ex) {}
        }
    }

    public abstract PCM<T> changeSampleRate(int sample);
    public abstract PCM<T> changeFrequency(float rate);
    public abstract PCM<T> changeChannels(int channels);
    public abstract PCM<T> slice(long starttime, long duration);
    public abstract boolean validate();

    protected static ByteBuffer getDirectByteBuffer(int capacity) {
        ByteBuffer result = USE_UNSAFE ? BufferUtils.newUnsafeByteBuffer(capacity) : ByteBuffer.allocateDirect(capacity);
        return result.order(ByteOrder.LITTLE_ENDIAN);
    }

    static class PCMLoader {
        ByteBuffer pcm;
        int channels = 0;
        int sampleRate = 0;
        int bitsPerSample = 0;
        private final AudioDriver driver;

        public PCMLoader(AudioDriver driver) { this.driver = driver; }

        public void loadPCM(FileHandle file) throws IOException {
            pcm = null;
            final String ext = file.extension().toLowerCase();

            if (ext.equals("wav")) {
                try (WavInputStream input = new WavInputStream(file.read())) {
                    channels = input.channels;
                    sampleRate = input.sampleRate;
                    bitsPerSample = input.bitsPerSample;
                    pcm = getDirectByteBuffer(input.dataRemaining);
                    byte[] temp = new byte[input.dataRemaining];
                    int read = 0;
                    while (read < temp.length) {
                        int r = input.read(temp, read, temp.length - read);
                        if (r == -1) break;
                        read += r;
                    }
                    pcm.put(temp);
                    pcm.flip();
                }
            } else if (ext.equals("ogg") || ext.equals("mp3") || ext.equals("flac")) {
                try {
                    Method decodeMethod = Gdx.audio.getClass().getMethod("decodeToPCM", String.class);
                    short[] data = (short[]) decodeMethod.invoke(Gdx.audio, file.path());
                    if (data != null) {
                        channels = 2; // OboeAudio 强制转换为双声道
                        sampleRate = ((AbstractAudioDriver)driver).getSampleRate(); // 动态获取当前音频驱动采样率
                        if (sampleRate == 0) sampleRate = 48000; // 兜底
                        bitsPerSample = 16;
                        pcm = getDirectByteBuffer(data.length * 2);
                        pcm.asShortBuffer().put(data);
                        pcm.flip();
                    }
                } catch (Exception e) {
                    Logger.getGlobal().warning("Native decode failed for " + file.path() + ": " + e.getMessage());
                }
            }

            if(pcm == null) throw new IOException(file.path() + " : 转换失败");
        }
    }

    private static class WavInputStream extends FilterInputStream {
        int dataRemaining;
        int channels, sampleRate, bitsPerSample;
        WavInputStream(InputStream in) throws IOException {
            super(in);
            byte[] header = new byte[44];
            int read = 0;
            while (read < 44) {
                int r = in.read(header, read, 44 - read);
                if (r == -1) break;
                read += r;
            }
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                throw new IOException("Not a WAV file");
            }
            channels = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8);
            sampleRate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8) | ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
            bitsPerSample = (header[34] & 0xFF) | ((header[35] & 0xFF) << 8);
            dataRemaining = (header[40] & 0xFF) | ((header[41] & 0xFF) << 8) | ((header[42] & 0xFF) << 16) | ((header[43] & 0xFF) << 24);
        }
    }
}
