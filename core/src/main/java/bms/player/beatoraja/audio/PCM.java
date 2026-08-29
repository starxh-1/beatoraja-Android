package bms.player.beatoraja.audio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.BufferUtils;
import java.lang.reflect.Method;

import com.jcraft.jogg.Page;
import com.jcraft.jogg.Packet;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;


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
        dur = tryMp3Duration(file);
        if (dur > 0) return dur;

        return 0;
    }

    /**
     * 谱面目录下的音频文件索引（惰性递归）。
     *
     * 性能考量：绝大多数谱面的音频都放在谱面同级目录，因此构造时只做一次平铺
     * listFiles()，开销与改造前完全相同；只有当谱面确实引用了子目录（形如
     * "audio/bgm.ogg"）时，才惰性触发一次有界深度的递归扫描，把子目录音频补进索引。
     * 这样既避免了逐文件 File.exists() 带来的数千次系统调用，又能命中子目录里的音频。
     *
     * 查找语义与播放侧 AbstractAudioDriver 保持一致：只按"相对谱面目录的路径"查找，
     * 裸文件名不去子目录里翻（播放侧用 new File(dpath, wav) 同样找不到）。
     */
    public static final class AudioFileIndex {

        /** 递归深度上限，避免异常目录结构导致扫描失控 */
        private static final int MAX_DEPTH = 3;

        private static final String[] AUDIO_EXTS = {".wav", ".ogg", ".flac", ".mp3"};

        private final File baseDir;
        private final Map<String, File> map = new HashMap<>();
        private boolean recursiveScanned = false;

        public AudioFileIndex(File baseDir) {
            this.baseDir = baseDir;
            final File[] dirFiles = baseDir.listFiles();
            if (dirFiles != null) {
                for (File f : dirFiles) {
                    if (f.isFile()) map.put(f.getName().toLowerCase(), f);
                }
            }
        }

        /**
         * 解析 wavlist 声明（已由 BMSDecoder 归一化为 '/' 分隔），返回对应文件，找不到返回 null。
         */
        public File resolve(String wavName) {
            final String name = wavName.toLowerCase();
            File f = map.get(name);
            if (f != null) return f;

            // 首次遇到带子目录的声明时补一次递归扫描，之后仍是 O(1) 查表
            if (name.indexOf('/') >= 0 && !recursiveScanned) {
                collectSubdirectories(baseDir, "", 1);
                recursiveScanned = true;
                f = map.get(name);
                if (f != null) return f;
            }
            return resolveByExtension(name);
        }

        /** 换扩展名重试：只认最后一个 '/' 之后的 '.'，避免 "audio.v2/bgm.ogg" 被截错 */
        private File resolveByExtension(String name) {
            final int slash = name.lastIndexOf('/');
            int dot = name.lastIndexOf('.');
            if (dot <= slash + 1) dot = -1;
            final String base = dot < 0 ? name : name.substring(0, dot);
            for (String ext : AUDIO_EXTS) {
                File f = map.get(base + ext);
                if (f != null) return f;
            }
            return null;
        }

        /** 只索引音频后缀，避免无关的大目录（如视频文件夹）撑大索引 */
        private void collectSubdirectories(File dir, String prefix, int depth) {
            if (depth > MAX_DEPTH) return;
            final File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                final String rel = prefix + f.getName().toLowerCase();
                if (f.isDirectory()) {
                    collectSubdirectories(f, rel + "/", depth + 1);
                } else if (isAudio(rel)) {
                    map.put(rel, f);
                }
            }
        }

        private static boolean isAudio(String lowerName) {
            for (String ext : AUDIO_EXTS) {
                if (lowerName.endsWith(ext)) return true;
            }
            return false;
        }
    }

    private static int tryWavDuration(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] buf = new byte[12];
            if (raf.read(buf) < 12) return 0;
            if (buf[0] != 'R' || buf[1] != 'I' || buf[2] != 'F' || buf[3] != 'F' ||
                buf[8] != 'W' || buf[9] != 'A' || buf[10] != 'V' || buf[11] != 'E') return 0;

            long avgBytesPerSec = 0;
            long dataSize = 0;

            while (raf.getFilePointer() < raf.length() - 8) {
                byte[] idBuf = new byte[4];
                raf.readFully(idBuf);
                String chunkId = new String(idBuf, StandardCharsets.US_ASCII);
                long chunkSize = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;

                if (chunkId.equals("fmt ")) {
                    raf.readShort(); // format tag
                    raf.readShort(); // channels
                    raf.readInt();   // sampleRate
                    avgBytesPerSec = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;
                    raf.readShort(); // blockAlign
                    raf.readShort(); // bitsPerSample
                    if (chunkSize > 16) raf.seek(raf.getFilePointer() + (chunkSize - 16));
                } else if (chunkId.equals("data")) {
                    dataSize = chunkSize;
                    long remaining = raf.length() - raf.getFilePointer();
                    if (dataSize <= 0 || dataSize > remaining) dataSize = remaining;
                    if (avgBytesPerSec > 0) break;
                    raf.seek(raf.getFilePointer() + dataSize);
                } else {
                    raf.seek(raf.getFilePointer() + chunkSize);
                }
                if (chunkSize % 2 != 0) raf.seek(raf.getFilePointer() + 1);
            }

            if (avgBytesPerSec <= 0 || dataSize <= 0) return 0;
            return (int) (dataSize * 1000L / avgBytesPerSec);
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
            byte[] buf = new byte[10];
            if (in.read(buf) < 10) return 0;

            long offset = 0;
            // 处理 ID3v2 标签头 (ID3...)
            if (buf[0] == 'I' && buf[1] == 'D' && buf[2] == '3') {
                int size = ((buf[6] & 0x7F) << 21) | ((buf[7] & 0x7F) << 14) | ((buf[8] & 0x7F) << 7) | (buf[9] & 0x7F);
                offset = 10 + size;
                in.skip(size); // 跳过 ID3 标签体
            } else {
                // 如果不是 ID3，重置流或重新读取（对于 FileInputStream 来说最稳妥是重新打开或记录位置）
                // 这里我们前面只读了 10 字节，如果是 fLaC 开头，这 10 字节里已经包含了 fLaC(4)
                if (buf[0] == 'f' && buf[1] == 'L' && buf[2] == 'a' && buf[3] == 'C') {
                    offset = 0;
                } else {
                    return 0;
                }
            }

            // 此时流应该在 fLaC 标志处（如果是 offset=0 则 buf 里已经有数据，这里需要补读或统一处理）
            byte[] header = new byte[42];
            if (offset == 0) {
                System.arraycopy(buf, 0, header, 0, 10);
                if (in.read(header, 10, 32) < 32) return 0;
            } else {
                if (in.read(header) < 42) return 0;
            }

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

    /**
     * MP3 时长解析。支持 Xing/VBRI 头部读取（VBR），若无则按 CBR 估算。
     */
    private static int tryMp3Duration(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLen = raf.length();
            if (fileLen < 100) return 0;

            byte[] buf = new byte[16384];
            int read = raf.read(buf);
            if (read < 10) return 0;

            int offset = 0;
            // 跳过 ID3v2 标签以定位音频起始位置
            if (buf[0] == 'I' && buf[1] == 'D' && buf[2] == '3') {
                int size = ((buf[6] & 0x7F) << 21) | ((buf[7] & 0x7F) << 14) | ((buf[8] & 0x7F) << 7) | (buf[9] & 0x7F);
                offset = size + 10;
                if (offset >= fileLen) return 0;
                raf.seek(offset);
                read = raf.read(buf);
                if (read < 10) return 0;
            }

            // 查找第一个 MPEG 帧同步字 (0xFFE/0xFFF)
            int syncPos = -1;
            for (int i = 0; i < read - 10; i++) {
                if ((buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xE0) == 0xE0) {
                    syncPos = i;
                    break;
                }
            }
            if (syncPos < 0) return 0;

            // 解析基本参数：版本、层、码率索引、采样率索引
            int b1 = buf[syncPos + 1] & 0xFF;
            int b2 = buf[syncPos + 2] & 0xFF;
            int b3 = buf[syncPos + 3] & 0xFF;

            int version = (b1 >> 3) & 3; // 3=MPEG V1, 2=V2, 0=V2.5
            int layer = (b1 >> 1) & 3;   // 1=L3, 2=L2, 3=L1
            int bitrateIdx = (b2 >> 4) & 0xF;
            int sampleRateIdx = (b2 >> 2) & 3;
            int channelMode = (b3 >> 6) & 3; // 3=Mono

            if (bitrateIdx == 0 || bitrateIdx == 15 || sampleRateIdx == 3) return 0;

            // 采样率表
            int sampleRate = 0;
            int[][] srTable = {{11025, 12000, 8000}, null, {22050, 24000, 16000}, {44100, 48000, 32000}};
            if (version >= 0 && version < srTable.length && srTable[version] != null) {
                sampleRate = srTable[version][sampleRateIdx];
            }
            if (sampleRate == 0) return 0;

            // 每帧采样数
            int samplesPerFrame;
            if (layer == 3) samplesPerFrame = 384; // L1
            else if (layer == 2) samplesPerFrame = 1152; // L2
            else samplesPerFrame = (version == 3) ? 1152 : 576; // L3: V1=1152, V2=576

            // 1. 检查 Xing/Info 头部 (VBR)
            int xingOffset = (version == 3) ? (channelMode == 3 ? 21 : 36) : (channelMode == 3 ? 13 : 21);
            xingOffset += syncPos;
            if (xingOffset + 12 < read) {
                if ((buf[xingOffset] == 'X' && buf[xingOffset+1] == 'i' && buf[xingOffset+2] == 'n' && buf[xingOffset+3] == 'g') ||
                    (buf[xingOffset] == 'I' && buf[xingOffset+1] == 'n' && buf[xingOffset+2] == 'f' && buf[xingOffset+3] == 'o')) {
                    int flags = ((buf[xingOffset+4] & 0xFF) << 24) | ((buf[xingOffset+5] & 0xFF) << 16) | ((buf[xingOffset+6] & 0xFF) << 8) | (buf[xingOffset+7] & 0xFF);
                    if ((flags & 1) != 0) { // Frames 字段存在
                        int frames = ((buf[xingOffset+8] & 0xFF) << 24) | ((buf[xingOffset+9] & 0xFF) << 16) | ((buf[xingOffset+10] & 0xFF) << 8) | (buf[xingOffset+11] & 0xFF);
                        return (int) ((long) frames * samplesPerFrame * 1000 / sampleRate);
                    }
                }
            }

            // 2. 检查 VBRI 头部 (VBR)
            int vbriOffset = syncPos + 36;
            if (vbriOffset + 18 < read && buf[vbriOffset] == 'V' && buf[vbriOffset+1] == 'B' && buf[vbriOffset+2] == 'R' && buf[vbriOffset+3] == 'I') {
                int frames = ((buf[vbriOffset+14] & 0xFF) << 24) | ((buf[vbriOffset+15] & 0xFF) << 16) | ((buf[vbriOffset+16] & 0xFF) << 8) | (buf[vbriOffset+17] & 0xFF);
                return (int) ((long) frames * samplesPerFrame * 1000 / sampleRate);
            }

            // 3. 兜底方案：CBR 估算
            int[][] bitrates = {
                {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}, // V1, L1
                {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},    // V1, L2
                {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},    // V1, L3
                {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},    // V2, L1
                {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}          // V2, L2/3
            };
            int brTable = (version == 3) ? (3 - layer) : (layer == 3 ? 3 : 4);
            int bitrate = bitrates[brTable][bitrateIdx];
            if (bitrate <= 0) return 0;
            return (int) ((fileLen - offset) * 8 / bitrate);
        } catch (Exception e) {
            return 0;
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

        /**
         * OGG Vorbis 解码结果（16-bit 交错 PCM）
         */
        private static class OggDecodeResult {
            short[] data;
            int channels;
            int sampleRate;
        }

        /**
         * 使用纯 Java 的 jorbis 解码器把 OGG Vorbis 解为 16-bit PCM。
         * 不使用 Android MediaCodec：MediaCodec 对 ogg/vorbis 支持不可靠，
         * 在部分 ROM 上解码会永久阻塞（该调用在主线程同步执行，曾导致 select 界面卡死）。
         *
         * @return 解码结果，失败返回 null
         */
        private static OggDecodeResult decodeOgg(FileHandle file) throws IOException {
            SyncState oy = new SyncState();
            StreamState os = new StreamState();
            Page og = new Page();
            Packet op = new Packet();
            Info vi = new Info();
            Comment vc = new Comment();
            DspState vd = new DspState();
            Block vb = new Block(vd);

            oy.init();

            List<short[]> chunks = new ArrayList<>();
            int totalSamples = 0;
            int channels = 0;
            int sampleRate = 0;
            // Vorbis 的 3 个 header 包（ID/Comment/Setup）常分布在不同的 Ogg 页中，
            // 必须跨多轮文件读取保留状态：os/vi/vc 只初始化一次，已解析的 header 包数持续累加。
            // 若在等待下一页时重复 init，会丢掉已解析的 header，导致解码失败。
            boolean streamInitialized = false;
            int headerPackets = 0;
            boolean headerParsed = false;

            try (InputStream input = new BufferedInputStream(file.read())) {
                boolean eof = false;
                while (!eof) {
                    int index = oy.buffer(4096);
                    int bytes = input.read(oy.data, index, 4096);
                    if (bytes == -1) {
                        eof = true;
                        oy.wrote(0);
                    } else if (bytes == 0) {
                        continue;
                    } else {
                        oy.wrote(bytes);
                    }

                    while (oy.pageout(og) == 1) {
                        if (!streamInitialized) {
                            // 只能初始化一次，且必须用首页的 serial
                            os.init(og.serialno());
                            vi.init();
                            vc.init();
                            streamInitialized = true;
                        }
                        os.pagein(og);

                        if (!headerParsed) {
                            // 解析 header 包。若本页包数不足，packetout 返回 0，
                            // while 自然结束并回到外层继续读文件取下一页，header 状态保留。
                            while (os.packetout(op) == 1) {
                                if (vi.synthesis_headerin(vc, op) < 0) {
                                    throw new IOException(file.path() + " : 不是有效的 OGG Vorbis 文件");
                                }
                                headerPackets++;
                                if (headerPackets == 3) {
                                    headerParsed = true;
                                    channels = vi.channels;
                                    sampleRate = vi.rate;
                                    vd.synthesis_init(vi);
                                    vb.init(vd);
                                    break;
                                }
                            }
                            continue;
                        }

                        while (os.packetout(op) == 1) {
                            if (vb.synthesis(op) != 0) {
                                continue;
                            }
                            vd.synthesis_blockin(vb);
                            float[][][] pcmOut = new float[1][channels][];
                            int[] pcmIndex = new int[channels];
                            int samples;
                            while ((samples = vd.synthesis_pcmout(pcmOut, pcmIndex)) > 0) {
                                short[] chunk = new short[samples * channels];
                                for (int i = 0; i < channels; i++) {
                                    float[] pcmChannel = pcmOut[0][i];
                                    int offset = pcmIndex[i];
                                    for (int j = 0; j < samples; j++) {
                                        float v = pcmChannel[offset + j];
                                        if (v > 1.0f) {
                                            v = 1.0f;
                                        } else if (v < -1.0f) {
                                            v = -1.0f;
                                        }
                                        chunk[j * channels + i] = (short) (v * 32767f);
                                    }
                                }
                                chunks.add(chunk);
                                totalSamples += samples;
                                vd.synthesis_read(samples);
                            }
                        }
                    }
                }
            }

            if (!headerParsed || totalSamples <= 0) {
                return null;
            }

            short[] data = new short[totalSamples * channels];
            int pos = 0;
            for (short[] chunk : chunks) {
                System.arraycopy(chunk, 0, data, pos, chunk.length);
                pos += chunk.length;
            }

            OggDecodeResult result = new OggDecodeResult();
            result.data = data;
            result.channels = channels;
            result.sampleRate = sampleRate;
            return result;
        }

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
            } else if (ext.equals("ogg")) {
                // OGG Vorbis 改用纯 Java 的 jorbis 解码：
                // Android MediaCodec 对 ogg/vorbis 支持不可靠，在部分 ROM 上解码会永久阻塞，
                // 而该调用在主（渲染）线程同步执行，曾导致 select 界面加载 select.ogg 时卡死。
                try {
                    OggDecodeResult decoded = decodeOgg(file);
                    if (decoded != null) {
                        channels = decoded.channels;
                        sampleRate = decoded.sampleRate;
                        bitsPerSample = 16;
                        pcm = getDirectByteBuffer(decoded.data.length * 2);
                        pcm.asShortBuffer().put(decoded.data);
                        pcm.flip();
                    }
                } catch (Exception e) {
                    Logger.getGlobal().warning("OGG decode failed for " + file.path() + ": " + e.getMessage());
                }
            } else if (ext.equals("mp3") || ext.equals("flac")) {
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
