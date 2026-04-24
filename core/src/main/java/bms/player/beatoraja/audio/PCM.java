package bms.player.beatoraja.audio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Logger;

import org.jflac.FLACDecoder;
import org.jflac.metadata.StreamInfo;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.StreamUtils;
import com.badlogic.gdx.utils.StreamUtils.OptimizedByteArrayOutputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

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
            } else if (ext.equals("ogg")) {
                handleOgg(file);
            } else if (ext.equals("mp3")) {
                handleMP3(file);
            } else if (ext.equals("flac")) {
                handleFlac(file);
            }

            if(pcm == null) throw new IOException(file.path() + " : 转换失败");
        }

        private void handleMP3(FileHandle file) {
            try (InputStream is = file.read()) {
                Bitstream bitstream = new Bitstream(is);
                Decoder decoder = new Decoder();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                Header h;
                while ((h = bitstream.readFrame()) != null) {
                    SampleBuffer sb = (SampleBuffer) decoder.decodeFrame(h, bitstream);
                    short[] pcmBuf = sb.getBuffer();
                    for (int i = 0; i < sb.getBufferLength(); i++) {
                        bos.write(pcmBuf[i] & 0xff);
                        bos.write((pcmBuf[i] >> 8) & 0xff);
                    }
                    bitstream.closeFrame();
                    if (this.sampleRate == 0) {
                        this.channels = h.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                        this.sampleRate = h.sample_frequency();
                    }
                }
                byte[] bytes = bos.toByteArray();
                pcm = getDirectByteBuffer(bytes.length).put(bytes);
                pcm.flip();
                bitsPerSample = 16;
            } catch (Exception e) { e.printStackTrace(); }
        }

        private void handleOgg(FileHandle file) {
            // Android 暂不支持 ogg 解码，建议使用 libGDX 默认处理
        }

        private void handleFlac(FileHandle file) {
            try (InputStream is = file.read()) {
                FLACDecoder decoder = new FLACDecoder(is);
                decoder.readMetadata();
                StreamInfo info = decoder.getStreamInfo();
                this.channels = info.getChannels();
                this.sampleRate = info.getSampleRate();
                this.bitsPerSample = info.getBitsPerSample();

                OptimizedByteArrayOutputStream output = new OptimizedByteArrayOutputStream((int)info.getTotalSamples() * 2);
                decoder.addPCMProcessor(new FlacProcessor(output));
                decoder.decodeFrames();

                byte[] bytes = output.getBuffer();
                pcm = getDirectByteBuffer(output.size()).put(bytes, 0, output.size());
                pcm.flip();
            } catch (Exception e) { e.printStackTrace(); }
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
