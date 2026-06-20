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
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] buf = new byte[12];
            if (raf.read(buf) < 12) return 0;
            if (buf[0] != 'R' || buf[1] != 'I' || buf[2] != 'F' || buf[3] != 'F' ||
                buf[8] != 'W' || buf[9] != 'A' || buf[10] != 'V' || buf[11] != 'E') return 0;

            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            long dataSize = 0;

            while (raf.getFilePointer() < raf.length() - 8) {
                String chunkId = "" + (char)raf.read() + (char)raf.read() + (char)raf.read() + (char)raf.read();
                long chunkSize = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;

                if (chunkId.equals("fmt ")) {
                    raf.readShort(); // format tag
                    channels = Short.reverseBytes(raf.readShort()) & 0xFFFF;
                    sampleRate = Integer.reverseBytes(raf.readInt());
                    raf.readInt(); // avgBytesPerSec
                    raf.readShort(); // blockAlign
                    bitsPerSample = Short.reverseBytes(raf.readShort()) & 0xFFFF;
                    if (chunkSize > 16) raf.seek(raf.getFilePointer() + (chunkSize - 16));
                } else if (chunkId.equals("data")) {
                    dataSize = chunkSize;
                    long remaining = raf.length() - raf.getFilePointer();
                    if (dataSize <= 0 || dataSize > remaining) dataSize = remaining;
                    break;
                } else {
                    raf.seek(raf.getFilePointer() + chunkSize);
                }
                if (chunkSize % 2 != 0) raf.seek(raf.getFilePointer() + 1);
            }

            if (sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0 || dataSize <= 0) return 0;
            long totalSamples = dataSize / (bitsPerSample / 8) / channels;
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

    private static final ThreadLocal<byte[]> OGG_TAIL_BUFFER = ThreadLocal.withInitial(() -> new byte[100000]);

    /**
     * OGG Vorbis 时长解析。读取开头获取 sampleRate，读取末尾获取 granule position。
     */
    private static int tryOggDuration(File file) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            long fileLen = raf.length();
            if (fileLen < 100) return 0;

            // 读取开头 4KB 获取 Vorbis ID header 中的 sample rate
            byte[] head = new byte[4096];
            int read = raf.read(head);
            if (read < 100) return 0;

            // 验证 OggS 标记
            if (head[0] != 'O' || head[1] != 'g' || head[2] != 'g' || head[3] != 'S') {
                // 如果不是在开头，找第一个 "OggS"
                int oggs = -1;
                for (int i = 1; i < read - 4; i++) {
                    if (head[i] == 'O' && head[i+1] == 'g' && head[i+2] == 'g' && head[i+3] == 'S') {
                        oggs = i; break;
                    }
                }
                if (oggs < 0) return 0;
            }

            // 找第一个 "OggS" (如果是从 0 开始则 oggs = 0)
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
            // Indices relative to 'v' (vorbisPos):
            // v:0, o:1, r:2, b:3, i:4, s:5, version:6-9, channels:10, sample_rate:11-14
            int srOffset = vorbisPos + 11;
            if (srOffset + 4 >= read) return 0;
            int sampleRate = (head[srOffset] & 0xFF) | ((head[srOffset+1] & 0xFF) << 8)
                | ((head[srOffset+2] & 0xFF) << 16) | ((head[srOffset+3] & 0xFF) << 24);
            if (sampleRate <= 0) return 0;

            // 读取末尾找最后一个 OggS page 的 granule position
            int tailSize = (int) Math.min(100000, fileLen);
            byte[] tail = OGG_TAIL_BUFFER.get();
            raf.seek(fileLen - tailSize);
            raf.readFully(tail, 0, tailSize);

            long granule = -1;
            // 从后往前找最后一个合法的 OggS 页面
            for (int i = tailSize - 26; i >= 0; i--) {
                if (tail[i] == 'O' && tail[i+1] == 'g' && tail[i+2] == 'g' && tail[i+3] == 'S') {
                    // 验证是否为合法 Ogg 页面 (version == 0)
                    if (tail[i+4] == 0) {
                        long g = (tail[i+6] & 0xFFL)
                            | ((tail[i+7] & 0xFFL) << 8)
                            | ((tail[i+8] & 0xFFL) << 16)
                            | ((tail[i+9] & 0xFFL) << 24)
                            | ((tail[i+10] & 0xFFL) << 32)
                            | ((tail[i+11] & 0xFFL) << 40)
                            | ((tail[i+12] & 0xFFL) << 48)
                            | ((tail[i+13] & 0xFFL) << 56);
                        // granule 为 -1 表示该页没有结束任何 packet，继续往前找
                        if (g != -1 && g > 0) {
                            granule = g;
                            break;
                        }
                    }
                }
            }
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
                        sampleRate = 44100; // Native decoder outputs at 44100Hz
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

            // 加载完成后立即就地转换到 driver 目标格式,避免 typed PCM 再分配一次 sample[]
            convertToDriverFormat();
        }

        private void convertToDriverFormat() {
            if (!(driver instanceof AbstractAudioDriver<?> audioDriver)) {
                return;
            }
            int preferredChannels = audioDriver.channels;
            if (preferredChannels != 0 && preferredChannels != channels) {
                pcm = changeChannels(pcm, channels, preferredChannels, bitsPerSample);
                channels = preferredChannels;
            }
            int preferredSampleRate = audioDriver.getSampleRate();
            if (preferredSampleRate != 0 && preferredSampleRate != sampleRate) {
                pcm = changeSampleRate(pcm, channels, sampleRate, preferredSampleRate, bitsPerSample);
                sampleRate = preferredSampleRate;
            }
        }

        private static ByteBuffer changeChannels(ByteBuffer source, int sourceChannels, int targetChannels, int bitsPerSample) {
            int bytesPerSample = bitsPerSample / 8;
            int sourceFrames = source.limit() / (sourceChannels * bytesPerSample);
            ByteBuffer output = allocatePCMBuffer(sourceFrames * targetChannels * bytesPerSample, bitsPerSample);

            for (int frame = 0; frame < sourceFrames; frame++) {
                double sample = readSample(source, frame * sourceChannels, bitsPerSample);
                for (int channel = 0; channel < targetChannels; channel++) {
                    writeSample(output, frame * targetChannels + channel, bitsPerSample, sample);
                }
            }
            return output;
        }

        private static ByteBuffer changeSampleRate(ByteBuffer source, int channels, int sourceSampleRate, int targetSampleRate, int bitsPerSample) {
            int bytesPerSample = bitsPerSample / 8;
            int sourceFrames = source.limit() / (channels * bytesPerSample);
            int targetFrames = (int) (((long) sourceFrames) * targetSampleRate / sourceSampleRate);
            ByteBuffer output = allocatePCMBuffer(targetFrames * channels * bytesPerSample, bitsPerSample);

            for (long frame = 0; frame < targetFrames; frame++) {
                long position = frame * sourceSampleRate / targetSampleRate;
                long mod = (frame * sourceSampleRate) % targetSampleRate;
                for (int channel = 0; channel < channels; channel++) {
                    double sample = readSample(source, (int) (position * channels + channel), bitsPerSample);
                    if (mod != 0 && (int) ((position + 1) * channels + channel) < sourceFrames * channels) {
                        double nextSample = readSample(source, (int) ((position + 1) * channels + channel), bitsPerSample);
                        sample = (sample * (targetSampleRate - mod) + nextSample * mod) / targetSampleRate;
                    }
                    writeSample(output, (int) (frame * channels + channel), bitsPerSample, sample);
                }
            }
            return output;
        }

        private static ByteBuffer allocatePCMBuffer(int capacity, int bitsPerSample) {
            return bitsPerSample == 16
                    ? getDirectByteBuffer(capacity)
                    : ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
        }

        private static double readSample(ByteBuffer source, int sampleIndex, int bitsPerSample) {
            int offset = sampleIndex * bitsPerSample / 8;
            return switch (bitsPerSample) {
                case 8 -> (source.get(offset) & 0xff) - 128;
                case 16 -> source.getShort(offset);
                case 24 -> read24BitSample(source, offset);
                case 32 -> source.getFloat(offset);
                default -> 0;
            };
        }

        private static int read24BitSample(ByteBuffer source, int offset) {
            int sample = (source.get(offset) & 0xff) | ((source.get(offset + 1) & 0xff) << 8) | ((source.get(offset + 2) & 0xff) << 16);
            return (sample & 0x800000) != 0 ? sample | 0xff000000 : sample;
        }

        private static void writeSample(ByteBuffer output, int sampleIndex, int bitsPerSample, double sample) {
            int offset = sampleIndex * bitsPerSample / 8;
            switch (bitsPerSample) {
                case 8 -> output.put(offset, (byte) (clamp((int) sample + 128, 0, 255) & 0xff));
                case 16 -> output.putShort(offset, (short) clamp((int) sample, Short.MIN_VALUE, Short.MAX_VALUE));
                case 24 -> write24BitSample(output, offset, clamp((int) sample, -0x800000, 0x7fffff));
                case 32 -> output.putFloat(offset, (float) sample);
                default -> { }
            }
        }

        private static void write24BitSample(ByteBuffer output, int offset, int sample) {
            output.put(offset, (byte) (sample & 0xff));
            output.put(offset + 1, (byte) ((sample >> 8) & 0xff));
            output.put(offset + 2, (byte) ((sample >> 16) & 0xff));
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
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
