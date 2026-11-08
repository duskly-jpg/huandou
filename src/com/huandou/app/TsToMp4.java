package com.huandou.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remuxes an MPEG-TS (H.264 + AAC) stream into a standard MP4 file WITHOUT re-encoding.
 *
 * <p>Only the container changes: PES payloads are split into H.264 access units and AAC
 * ADTS frames, Annex-B start codes are replaced by AVCC length prefixes, Timestamps are
 * taken from PES PTS, and a minimal ISO-BMFF (ftyp/moov/mdat) file is written. Video and
 * audio bitstreams are copied byte-for-byte, so quality and size stay essentially the same.</p>
 *
 * <p>Pure Java on purpose: no Android framework classes, so the exact same code that runs
 * on the phone can be (and is) verified on a desktop JVM against ffmpeg-generated streams.</p>
 *
 * <p>Deliberately unsupported: HEVC/H.265, AC-3, MP3, MPEG-2 video, SAMPLE-AES or any
 * DRM-protected elementary stream. Those throw {@link UnsupportedException} and the caller
 * should keep the original .ts instead of producing a broken .mp4.</p>
 */
public final class TsToMp4 {

    public static final class UnsupportedException extends Exception {
        public UnsupportedException(String m) {
            super(m);
        }
    }

    public interface Progress {
        void onProgress(int percent, String label);

        boolean isCancelled();
    }

    public static final class Result {
        public int videoFrames;
        public int audioFrames;
        public long durationMs;
        public int width;
        public int height;
        public int sampleRate;
        public int channels;
    }

    private static final int TS_PACKET = 188;
    /**
     * 只是挡一眼明显异常的文件，不是功能上限。
     *
     * <p>解析改成按块流式之后，内存占用与文件大小无关（常量 192 KB），
     * 所以这里不再按「能不能放进堆」来卡。真正的成本是磁盘：临时目录里会
     * 同时存在一份 TS、若干帧缓冲和一份 MP4。
     */
    private static final long MAX_INPUT = 2048L * 1024 * 1024;
    private static final int VIDEO_TIMESCALE = 90000;

    /** 解析窗口。188 的整数倍，保证任何一块里都不会出现被切断的 TS 包。 */
    private static final int WINDOW = TS_PACKET * 1024;

    private static final int[] AAC_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private TsToMp4() {
    }

    /** Quick check used by the downloader before deciding to remux. */
    public static boolean isTsFile(File f) {
        byte[] h = new byte[TS_PACKET * 2];
        try {
            FileInputStream in = new FileInputStream(f);
            int n = in.read(h);
            in.close();
            if (n < TS_PACKET) return false;
            return (h[0] & 0xFF) == 0x47
                    && (n < TS_PACKET * 2 || (h[TS_PACKET] & 0xFF) == 0x47);
        } catch (Throwable t) {
            return false;
        }
    }

    public static Result convert(File in, File out, Progress cb) throws Exception {
        if (in == null || !in.exists() || in.length() < TS_PACKET) {
            throw new UnsupportedException("视频数据为空或太短，无法封装");
        }
        if (in.length() > MAX_INPUT) {
            throw new UnsupportedException("视频超过 2GB，已保留原始 TS");
        }
        if (!isTsFile(in)) throw new UnsupportedException("数据不是 MPEG-TS（首字节非 0x47）");
        // 转换期间目录里要同时放下：原始 TS（已在）+ 音视频临时流 + 输出 MP4，
        // 大约是源文件的 2 倍。空间不够就直接说清楚，别让人对着一个 IOException 猜。
        File dir = out.getParentFile();
        if (dir != null) {
            long need = in.length() * 2;
            long free = dir.getUsableSpace();
            if (free > 0 && free < need) {
                throw new UnsupportedException("缓存空间不足（还需要约 "
                        + (need - free) / (1024 * 1024) + " MB），已保留原始 TS");
            }
        }
        check(cb, 5, "解析 TS…");

        Demux d = new Demux(out);
        try {
            demux(in, d, cb);
            check(cb, 70, "整理音视频帧…");
            if (d.video == null && d.audio == null) {
                throw new UnsupportedException("TS 里没有可识别的音视频轨道");
            }
            if (d.video != null && d.video.sizes.isEmpty()) {
                throw new UnsupportedException("未识别到 H.264 视频帧（可能是 HEVC/H.265 或其它编码）");
            }
            if (d.video != null && (d.video.sps == null || d.video.pps == null)) {
                throw new UnsupportedException("视频流缺少 SPS/PPS，无法生成 MP4 解码配置");
            }
            d.finishTracks();
            if (d.audio == null && d.audioPes >= 2 && d.adtsPes == 0) {
                throw new UnsupportedException("音频不是 AAC-ADTS（可能是 AC-3/MP3/LATM），已保留 TS");
            }
            Result r = new Result();
            r.videoFrames = d.video == null ? 0 : d.video.sizes.size();
            r.audioFrames = d.audio == null ? 0 : d.audio.sizes.size();
            r.durationMs = d.movieDurationMs();
            r.width = d.video == null ? 0 : d.video.width;
            r.height = d.video == null ? 0 : d.video.height;
            r.sampleRate = d.audio == null ? 0 : d.audio.timescale;
            r.channels = d.audio == null ? 0 : d.audio.channels;
            writeMp4(out, d, cb);
            check(cb, 100, "封装完成");
            return r;
        } finally {
            d.cleanup();
        }
    }

    // ------------------------------------------------------------------ demux

    private static final class PidState {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean active;
        int streamId = -1;
        long pts = -1;
    }

    /**
     * 按块流式解析 TS。
     *
     * <p>之前是把整个文件 {@code readAll} 进一个 byte[]，再由 d8 之前的老版本直接扫。
     * 那样两个问题一起出现：超过 200MB 直接被拒（所以大视频永远是 .ts），
     * 而 100～200MB 的文件在低端机上会因为在 ByteArrayOutputStream 扩容时翻倍拷贝而 OOM，
     * 被 catch 住之后同样退回 .ts。改成流式之后这两条都不存在了。
     *
     * <p>TS 是 188 字节定长包，窗口取 188 的整数倍，包不会跨块被切断；
     * 每个包的 payload 仍然累加到 {@link PidState#buf}，一个 PES 的最大长度由编码器决定，
     * 与文件总大小无关，所以内存是常量级的。
     */
    private static void demux(File src, Demux d, Progress cb) throws Exception {
        long total = src.length();
        long start = findSync(src);
        if (start < 0) {
            throw new UnsupportedException("找不到 MPEG-TS 同步头（首字节不是 0x47）");
        }
        Map<Integer, PidState> states = new HashMap<Integer, PidState>();
        InputStream in = new BufferedInputStream(new FileInputStream(src), 256 * 1024);
        try {
            skipFully(in, start);
            byte[] buf = new byte[WINDOW];
            long pos = start;
            int seen = 0;
            while (true) {
                int n = readFully(in, buf, WINDOW);
                if (n <= 0) break;
                int usable = n - (n % TS_PACKET);
                for (int p = 0; p < usable; p += TS_PACKET) {
                    if ((seen++ & 1023) == 0) {
                        check(cb, 5 + (int) (60L * (pos + p) / Math.max(1L, total)), "解析 TS…");
                    }
                    if ((buf[p] & 0xFF) != 0x47) {
                        int q = resync(buf, p, usable);
                        if (q < 0) {
                            p = usable; // 这块剩下的都别要了，下一块重新对齐
                            break;
                        }
                        p = q - TS_PACKET; // 循环末尾会再 +TS_PACKET，正好落在 q
                        continue;
                    }
                    packet(buf, p, states, d);
                }
                pos += usable;
                if (n < WINDOW) break;
            }
            for (Map.Entry<Integer, PidState> e : states.entrySet()) {
                PidState st = e.getValue();
                if (st.active) flushPes(st, e.getKey(), d);
            }
        } finally {
            in.close();
        }
    }

    /** 失步后在本块里往前找下一个同步点，要求后面连着两包也是 0x47。 */
    private static int resync(byte[] buf, int from, int end) {
        for (int i = from + 1; i + TS_PACKET * 2 < end; i++) {
            if ((buf[i] & 0xFF) == 0x47
                    && (buf[i + TS_PACKET] & 0xFF) == 0x47
                    && (buf[i + TS_PACKET * 2] & 0xFF) == 0x47) return i;
        }
        return -1;
    }

    /** 处理一个 TS 包。p 指向包头，buf[p] 已确认是 0x47。 */
    private static void packet(byte[] buf, int p, Map<Integer, PidState> states, Demux d)
            throws Exception {
        int pid = ((buf[p + 1] & 0x1F) << 8) | (buf[p + 2] & 0xFF);
        boolean pusi = (buf[p + 1] & 0x40) != 0;
        int afc = (buf[p + 3] >> 4) & 0x03;
        if (afc == 1 || afc == 3) {
            int off = 4;
            if (afc == 3) off = 5 + (buf[p + 4] & 0xFF);
            if (off < TS_PACKET) {
                PidState st = states.get(pid);
                if (st == null) {
                    st = new PidState();
                    states.put(pid, st);
                }
                if (pusi) {
                    if (st.active) flushPes(st, pid, d);
                    st.buf.reset();
                    st.active = false;
                    st.streamId = -1;
                    st.pts = -1;
                    if (off + 9 <= TS_PACKET && (buf[p + off] & 0xFF) == 0
                            && (buf[p + off + 1] & 0xFF) == 0
                            && (buf[p + off + 2] & 0xFF) == 1) {
                        int sid = buf[p + off + 3] & 0xFF;
                        int flags = buf[p + off + 7] & 0xFF;
                        int hdrLen = buf[p + off + 8] & 0xFF;
                        int payloadStart = off + 9 + hdrLen;
                        long pts = -1;
                        if ((flags & 0x80) != 0 && hdrLen >= 5 && off + 14 <= TS_PACKET) {
                            pts = readPts(buf, p + off + 9);
                        }
                        if (payloadStart < TS_PACKET) {
                            st.active = true;
                            st.streamId = sid;
                            st.pts = pts;
                            st.buf.write(buf, p + payloadStart, TS_PACKET - payloadStart);
                        }
                    }
                } else if (st.active) {
                    st.buf.write(buf, p + off, TS_PACKET - off);
                }
            }
        }
    }

    private static void flushPes(PidState st, int pid, Demux d) throws Exception {
        byte[] payload = st.buf.toByteArray();
        st.active = false;
        if (payload.length < 4) return;
        int sid = st.streamId;
        if (sid >= 0xE0 && sid <= 0xEF) {
            d.addVideo(pid, payload, st.pts);
        } else if ((sid >= 0xC0 && sid <= 0xDF) || sid == 0xBD) {
            d.addAudio(pid, payload);
        }
    }

    private static long readPts(byte[] b, int q) {
        long b0 = b[q] & 0xFFL;
        long b1 = b[q + 1] & 0xFFL;
        long b2 = b[q + 2] & 0xFFL;
        long b3 = b[q + 3] & 0xFFL;
        long b4 = b[q + 4] & 0xFFL;
        return (((b0 >> 1) & 7L) << 30) | (b1 << 22)
                | (((b2 >> 1) & 0x7FL) << 15) | (b3 << 7) | ((b4 >> 1) & 0x7FL);
    }

    /**
     * 定位文件里第一个同步点。要求连续三个包都以 0x47 开头——
     * 只看一个字节会被正文里的 0x47 骗过去，之后整条流就全错位了。
     */
    private static long findSync(File f) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(f), 64 * 1024);
        try {
            byte[] b = new byte[TS_PACKET * 16];
            int n = readFully(in, b, b.length);
            for (int i = 0; i + TS_PACKET * 3 <= n; i++) {
                if ((b[i] & 0xFF) == 0x47
                        && (b[i + TS_PACKET] & 0xFF) == 0x47
                        && (b[i + TS_PACKET * 2] & 0xFF) == 0x47) return i;
            }
            // 极短的文件（不到三个包）放宽到两个。
            if (n >= TS_PACKET * 2 && (b[0] & 0xFF) == 0x47
                    && (b[TS_PACKET] & 0xFF) == 0x47) return 0;
            return -1;
        } finally {
            in.close();
        }
    }

    /** 读满 len 字节或读到 EOF，返回实际字节数。 */
    private static int readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n <= 0) break;
            off += n;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            n -= s;
        }
    }

    /** Split Annex-B payload into NAL units (start code removed). */
    private static List<byte[]> nalUnits(byte[] p) {
        List<Integer> starts = new ArrayList<Integer>();
        List<Integer> ends = new ArrayList<Integer>();
        int i = 0;
        while (i + 3 < p.length) {
            int sc = 0;
            if ((p[i] & 0xFF) == 0 && (p[i + 1] & 0xFF) == 0) {
                if ((p[i + 2] & 0xFF) == 1) sc = 3;
                else if (i + 3 < p.length && (p[i + 2] & 0xFF) == 0
                        && (p[i + 3] & 0xFF) == 1) sc = 4;
            }
            if (sc > 0) {
                if (!starts.isEmpty()) ends.add(i);
                starts.add(i + sc);
                i += sc;
            } else {
                i++;
            }
        }
        List<byte[]> out = new ArrayList<byte[]>();
        for (int k = 0; k < starts.size(); k++) {
            int a = starts.get(k);
            int b = k < ends.size() ? ends.get(k) : p.length;
            if (b > a) out.add(Arrays.copyOfRange(p, a, b));
        }
        return out;
    }

    private static byte[] avcc(List<byte[]> nals) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bos);
        for (byte[] nal : nals) {
            d.writeInt(nal.length);
            d.write(nal);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ tracks

    private static final class Track {
        final int id;
        final boolean video;
        int timescale;
        final List<Integer> sizes = new ArrayList<Integer>();
        final List<Long> pts = new ArrayList<Long>();
        final List<Integer> keys = new ArrayList<Integer>();
        File tmp;
        OutputStream tmpOut;
        long dataSize;

        // video config
        byte[] sps;
        byte[] pps;
        int width;
        int height;

        // audio config
        int channels;
        int freqIdx;
        int aot;
        long audioTick;

        // timestamp continuity across concatenated HLS segments
        long ptsBase = -1;
        long offset;
        long prevPts = -1;
        long lastDelta = 3600;
        boolean sawNal;

        Track(int id, boolean video, File parent, String tag) throws IOException {
            this.id = id;
            this.video = video;
            this.tmp = File.createTempFile("hd" + tag, ".bin", parent);
            this.tmpOut = new BufferedOutputStream(new FileOutputStream(tmp));
        }

        void add(byte[] sample, long ptsVal, boolean key) throws IOException {
            tmpOut.write(sample);
            sizes.add(sample.length);
            pts.add(ptsVal);
            dataSize += sample.length;
            if (video && key) keys.add(sizes.size()); // 1-based sample number
        }

        long durationTicks() {
            if (pts.isEmpty()) return 0;
            if (video) {
                long[] sorted = new long[pts.size()];
                for (int i = 0; i < sorted.length; i++) sorted[i] = pts.get(i);
                Arrays.sort(sorted);
                return sorted[sorted.length - 1] + lastDelta;
            }
            return (long) sizes.size() * 1024L;
        }

        void closeOut() throws IOException {
            tmpOut.flush();
            tmpOut.close();
            tmpOut = null;
        }
    }

    private static final class Demux {
        final File parent;
        Track video;
        Track audio;
        int videoPid = -1;
        int audioPid = -1;
        int audioPes;
        int adtsPes;

        Demux(File out) {
            parent = out.getParentFile();
        }

        void addVideo(int pid, byte[] payload, long rawPts) throws Exception {
            if (video != null && videoPid != pid) return;
            List<byte[]> nals = nalUnits(payload);
            if (nals.isEmpty()) return;
            Track t = video;
            boolean h264 = false;
            List<byte[]> keep = new ArrayList<byte[]>();
            boolean idr = false;
            for (byte[] nal : nals) {
                int type = nal[0] & 0x1F;
                if (type == 7) {
                    if (t == null || t.sps == null) {
                        ensureVideo(pid).sps = nal;
                    }
                    continue;
                }
                if (type == 8) {
                    if (t == null || t.pps == null) {
                        ensureVideo(pid).pps = nal;
                    }
                    continue;
                }
                if (type == 9 || type == 12 || type == 10 || type == 11) continue;
                if (type == 5) {
                    idr = true;
                    h264 = true;
                    keep.add(nal);
                } else if (type == 1) {
                    h264 = true;
                    keep.add(nal);
                } else if (type == 6) {
                    keep.add(nal);
                } else if (type == 0 && ((nal[0] >> 1) & 0x3F) >= 32) {
                    // HEVC/H.265 NAL header is two bytes; its types live at 32..40.
                    throw new UnsupportedException("检测到 HEVC/H.265 视频，本工具只封装 H.264，已保留 TS");
                } else {
                    keep.add(nal);
                }
            }
            if (t == null) t = ensureVideo(pid);
            t.sawNal = true;
            if (!h264 || keep.isEmpty()) return; // parameter-set-only PES: no sample
            byte[] sample = avcc(keep);
            long pts = adjustVideoPts(t, rawPts);
            t.add(sample, pts, idr);
        }

        private Track ensureVideo(int pid) throws IOException {
            if (video == null) {
                video = new Track(1, true, parent, "v");
                video.timescale = VIDEO_TIMESCALE;
                videoPid = pid;
            }
            return video;
        }

        private static long adjustVideoPts(Track t, long raw) {
            long adj;
            if (raw < 0) {
                adj = t.prevPts < 0 ? 0 : t.prevPts + (t.lastDelta > 0 ? t.lastDelta : 3600);
            } else {
                adj = raw + t.offset;
                // A new HLS segment may restart its PTS near zero. Bridge the gap.
                if (t.prevPts >= 0 && adj < t.prevPts - 5L * VIDEO_TIMESCALE) {
                    t.offset = t.prevPts + (t.lastDelta > 0 ? t.lastDelta : 3600) - raw;
                    adj = raw + t.offset;
                }
            }
            if (t.ptsBase < 0) t.ptsBase = adj;
            long v = adj - t.ptsBase;
            if (v < 0) v = 0;
            if (t.prevPts >= 0) {
                long d = adj - t.prevPts;
                if (d > 0 && d < 10L * VIDEO_TIMESCALE) t.lastDelta = d;
            }
            t.prevPts = adj;
            return v;
        }

        void addAudio(int pid, byte[] payload) throws Exception {
            if (audio != null && audioPid != pid) return;
            audioPes++;
            int i = 0;
            boolean any = false;
            while (i + 7 <= payload.length) {
                if ((payload[i] & 0xFF) != 0xFF || (payload[i + 1] & 0xF0) != 0xF0) {
                    i++;
                    continue;
                }
                int profile = (payload[i + 2] >> 6) & 0x03;
                int freqIdx = (payload[i + 2] >> 2) & 0x0F;
                int chan = ((payload[i + 2] & 0x01) << 2) | ((payload[i + 3] >> 6) & 0x03);
                int frameLen = ((payload[i + 3] & 0x03) << 11)
                        | ((payload[i + 4] & 0xFF) << 3) | ((payload[i + 5] >> 5) & 0x07);
                int headerLen = (payload[i + 1] & 0x01) != 0 ? 7 : 9;
                if (freqIdx >= AAC_RATES.length || chan == 0 || frameLen < headerLen
                        || i + frameLen > payload.length) {
                    break;
                }
                Track t = audio;
                if (t == null) {
                    t = new Track(2, false, parent, "a");
                    t.timescale = AAC_RATES[freqIdx];
                    t.channels = chan;
                    t.freqIdx = freqIdx;
                    t.aot = profile + 1;
                    audio = t;
                    audioPid = pid;
                }
                byte[] frame = Arrays.copyOfRange(payload, i + headerLen, i + frameLen);
                t.add(frame, t.audioTick, false);
                t.audioTick += 1024;
                i += frameLen;
                any = true;
            }
            if (any) adtsPes++;
        }

        void finishTracks() throws Exception {
            if (video != null) {
                video.closeOut();
                int[] wh = parseSpsSize(video.sps);
                video.width = wh[0];
                video.height = wh[1];
            }
            if (audio != null) audio.closeOut();
        }

        long movieDurationMs() {
            long ms = 0;
            if (video != null) {
                ms = Math.max(ms, video.durationTicks() * 1000L / VIDEO_TIMESCALE);
            }
            if (audio != null) {
                ms = Math.max(ms, audio.durationTicks() * 1000L / audio.timescale);
            }
            return ms;
        }

        int trackCount() {
            return (video == null ? 0 : 1) + (audio == null ? 0 : 1);
        }

        void cleanup() {
            if (video != null) {
                try {
                    if (video.tmpOut != null) video.tmpOut.close();
                } catch (Throwable ignored) {
                }
                delete(video.tmp);
            }
            if (audio != null) {
                try {
                    if (audio.tmpOut != null) audio.tmpOut.close();
                } catch (Throwable ignored) {
                }
                delete(audio.tmp);
            }
        }
    }

    // ------------------------------------------------------------------ SPS

    private static final class BitReader {
        private final byte[] b;
        private int pos;
        private final int endBits;

        BitReader(byte[] b) {
            this.b = b;
            this.endBits = b.length * 8;
        }

        int bit() throws UnsupportedException {
            if (pos >= endBits) throw new UnsupportedException("SPS 数据不完整");
            int v = (b[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return v;
        }

        int u(int n) throws UnsupportedException {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | bit();
            return v;
        }

        int ue() throws UnsupportedException {
            int z = 0;
            while (bit() == 0) {
                if (++z > 32) throw new UnsupportedException("SPS Exp-Golomb 异常");
            }
            return (int) ((1L << z) - 1) + (z > 0 ? u(z) : 0);
        }

        int se() throws UnsupportedException {
            int k = ue();
            int m = (k + 1) >> 1;
            return (k & 1) == 1 ? m : -m;
        }
    }

    private static byte[] removeEmulationPrevention(byte[] nal) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 1; i < nal.length; ) {
            if (i + 2 < nal.length && (nal[i] & 0xFF) == 0
                    && (nal[i + 1] & 0xFF) == 0 && (nal[i + 2] & 0xFF) == 3) {
                o.write(0);
                o.write(0);
                i += 3;
            } else {
                o.write(nal[i]);
                i++;
            }
        }
        return o.toByteArray();
    }

    private static void skipScalingList(BitReader br, int size) throws UnsupportedException {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) {
                int delta = br.se();
                next = (last + delta + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    /** Returns {width, height}, or {0,0} when the SPS cannot be parsed. */
    static int[] parseSpsSize(byte[] spsNal) {
        try {
            BitReader br = new BitReader(removeEmulationPrevention(spsNal));
            int profile = br.u(8);
            br.u(8); // constraint flags
            br.u(8); // level_idc
            br.ue(); // seq_parameter_set_id
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244
                    || profile == 44 || profile == 83 || profile == 86 || profile == 118
                    || profile == 128 || profile == 138 || profile == 139
                    || profile == 134 || profile == 135) {
                chroma = br.ue();
                if (chroma == 3) br.u(1);
                br.ue(); // bit_depth_luma_minus8
                br.ue(); // bit_depth_chroma_minus8
                br.u(1); // qpprime_y_zero_transform_bypass
                if (br.u(1) != 0) { // seq_scaling_matrix_present_flag
                    int n = chroma != 3 ? 8 : 12;
                    for (int i = 0; i < n; i++) {
                        if (br.u(1) != 0) skipScalingList(br, i < 6 ? 16 : 64);
                    }
                }
            }
            br.ue(); // log2_max_frame_num_minus4
            int pocType = br.ue();
            if (pocType == 0) {
                br.ue();
            } else if (pocType == 1) {
                br.u(1);
                br.se();
                br.se();
                int n = br.ue();
                for (int i = 0; i < n; i++) br.se();
            }
            br.ue(); // max_num_ref_frames
            br.u(1); // gaps_in_frame_num_value_allowed
            int wMbs = br.ue() + 1;
            int hUnits = br.ue() + 1;
            int frameMbsOnly = br.u(1);
            if (frameMbsOnly == 0) br.u(1); // mb_adaptive_frame_field
            br.u(1); // direct_8x8_inference
            int cl = 0;
            int cr = 0;
            int ct = 0;
            int cb = 0;
            if (br.u(1) != 0) { // frame_cropping_flag
                cl = br.ue();
                cr = br.ue();
                ct = br.ue();
                cb = br.ue();
            }
            int chromaArray = frameMbsOnly == 1 ? chroma : 0;
            int subW = (chromaArray == 1 || chromaArray == 2) ? 2 : 1;
            int subH = chromaArray == 1 ? 2 : 1;
            int multH = 2 - frameMbsOnly;
            int w = wMbs * 16 - (cl + cr) * subW;
            int h = hUnits * 16 * multH - (ct + cb) * subH * multH;
            if (w <= 0 || h <= 0) return new int[]{0, 0};
            return new int[]{w, h};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    // ------------------------------------------------------------------ MP4 writer

    private static void writeMp4(File out, Demux d, Progress cb) throws Exception {
        byte[] ftyp = box("ftyp", cat(
                ascii("isom"), u32(0x200), ascii("isomiso2avc1mp41")));

        long dataSize = (d.video == null ? 0 : d.video.dataSize)
                + (d.audio == null ? 0 : d.audio.dataSize);
        boolean large = dataSize + 8 > 0xFFFFFFFFL;
        int mdatHeaderLen = large ? 16 : 8;
        byte[] mdatHeader = large
                ? cat(u32(1), ascii("mdat"), u64(dataSize + 16))
                : cat(u32(dataSize + 8), ascii("mdat"));

        long durationMs = Math.max(1, d.movieDurationMs());
        long movieOffset = ftyp.length + 8L + mdatHeaderLen; // placeholder pass
        byte[] moov = buildMoov(d, durationMs, movieOffset, mdatHeaderLen);
        movieOffset = ftyp.length + moov.length + mdatHeaderLen;
        moov = buildMoov(d, durationMs, movieOffset, mdatHeaderLen); // real offsets

        OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
        try {
            os.write(ftyp);
            os.write(moov);
            os.write(mdatHeader);
            if (d.video != null) copy(d.video.tmp, os, cb, 80, d.audio == null ? 100 : 92);
            if (d.audio != null) copy(d.audio.tmp, os, cb, 92, 100);
        } finally {
            os.close();
        }
    }

    private static byte[] buildMoov(Demux d, long durationMs, long firstChunkOffset,
                                    int mdatHeaderLen) throws Exception {
        long off = firstChunkOffset;
        List<byte[]> traks = new ArrayList<byte[]>();
        if (d.video != null) {
            traks.add(buildTrack(d.video, 1, durationMs, d.trackCount() + 1, off));
            off += d.video.dataSize;
        }
        if (d.audio != null) {
            traks.add(buildTrack(d.audio, d.video == null ? 1 : 2, durationMs,
                    d.trackCount() + 1, off));
        }
        byte[] mvhd = box("mvhd", cat(
                u32(0), u32(0), u32(0), u32(1000), u32(durationMs),
                u32(0x00010000), u16(0x0100), u16(0), new byte[8],
                matrix(), new byte[24], u32(d.trackCount() + 1)));
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(mvhd);
        for (byte[] t : traks) all.write(t);
        return box("moov", all.toByteArray());
    }

    private static byte[] buildTrack(Track t, int trackId, long durationMs,
                                     int nextTrackId, long chunkOffset) throws Exception {
        boolean video = t.video;
        int n = t.sizes.size();

        long[] dts = new long[n];
        long[] cts = new long[n];
        boolean needCtts = false;
        if (video) {
            long[] sorted = new long[n];
            for (int i = 0; i < n; i++) {
                dts[i] = t.pts.get(i);
                sorted[i] = dts[i];
            }
            Arrays.sort(sorted);
            for (int i = 0; i < n; i++) {
                cts[i] = dts[i] - sorted[i];
                if (cts[i] != 0) needCtts = true;
            }
            dts = sorted;
        } else {
            for (int i = 0; i < n; i++) dts[i] = (long) i * 1024L;
        }

        // stts from DTS deltas
        List<long[]> runs = new ArrayList<long[]>();
        long lastDelta = n > 1 ? dts[1] - dts[0] : (video ? t.lastDelta : 1024);
        if (lastDelta <= 0) lastDelta = video ? 3600 : 1024;
        for (int i = 0; i < n; i++) {
            long delta = i < n - 1 ? dts[i + 1] - dts[i] : lastDelta;
            if (delta < 0) delta = 0;
            if (!runs.isEmpty() && runs.get(runs.size() - 1)[1] == delta) {
                runs.get(runs.size() - 1)[0]++;
            } else {
                runs.add(new long[]{1, delta});
            }
        }
        ByteArrayOutputStream sttsBody = new ByteArrayOutputStream();
        DataOutputStream sd = new DataOutputStream(sttsBody);
        sd.writeInt(0);
        sd.writeInt(runs.size());
        for (long[] r : runs) {
            sd.writeInt((int) r[0]);
            sd.writeInt((int) r[1]);
        }
        byte[] stts = box("stts", sttsBody.toByteArray());

        byte[] stss = new byte[0];
        if (video && !t.keys.isEmpty() && t.keys.size() < n) {
            ByteArrayOutputStream kb = new ByteArrayOutputStream();
            DataOutputStream kd = new DataOutputStream(kb);
            kd.writeInt(0);
            kd.writeInt(t.keys.size());
            for (Integer k : t.keys) kd.writeInt(k);
            stss = box("stss", kb.toByteArray());
        }

        byte[] ctts = new byte[0];
        if (video && needCtts) {
            List<long[]> cruns = new ArrayList<long[]>();
            for (int i = 0; i < n; i++) {
                if (!cruns.isEmpty() && cruns.get(cruns.size() - 1)[1] == cts[i]) {
                    cruns.get(cruns.size() - 1)[0]++;
                } else {
                    cruns.add(new long[]{1, cts[i]});
                }
            }
            ByteArrayOutputStream cb = new ByteArrayOutputStream();
            DataOutputStream cd = new DataOutputStream(cb);
            cd.writeInt(0x01000000); // version 1 (signed offsets), flags 0
            cd.writeInt(cruns.size());
            for (long[] r : cruns) {
                cd.writeInt((int) r[0]);
                cd.writeInt((int) r[1]);
            }
            ctts = box("ctts", cb.toByteArray());
        }

        byte[] stsc = box("stsc", cat(u32(0), u32(1), u32(1), u32(n), u32(1)));

        ByteArrayOutputStream zb = new ByteArrayOutputStream();
        DataOutputStream zd = new DataOutputStream(zb);
        zd.writeInt(0);
        zd.writeInt(0); // sample_size = 0 -> use table
        zd.writeInt(n);
        for (Integer s : t.sizes) zd.writeInt(s);
        byte[] stsz = box("stsz", zb.toByteArray());

        byte[] stco;
        if (chunkOffset > 0xFFFFFFFFL) {
            stco = box("co64", cat(u32(0), u32(1), u64(chunkOffset)));
        } else {
            stco = box("stco", cat(u32(0), u32(1), u32(chunkOffset)));
        }

        byte[] sampleEntry = video ? avc1(t) : mp4a(t);
        byte[] stsd = box("stsd", cat(u32(0), u32(1), sampleEntry));
        byte[] stbl = box("stbl", cat(stsd, stts, stss, ctts, stsc, stsz, stco));

        byte[] mediaHeader = video
                ? box("vmhd", cat(u32(1), u16(0), new byte[6]))
                : box("smhd", cat(u32(0), u16(0), u16(0)));
        byte[] url = box("url ", u32(1));
        byte[] dref = box("dref", cat(u32(0), u32(1), url));
        byte[] dinf = box("dinf", dref);
        byte[] minf = box("minf", cat(mediaHeader, dinf, stbl));

        long trackDurationTicks = t.durationTicks();
        byte[] mdhd = box("mdhd", cat(u32(0), u32(0), u32(0), u32(t.timescale),
                u32(trackDurationTicks), u16(0x55C4), u16(0)));
        byte[] hdlr = box("hdlr", cat(u32(0), u32(0),
                ascii(video ? "vide" : "soun"), new byte[12],
                ascii(video ? "VideoHandler\0" : "SoundHandler\0")));
        byte[] mdia = box("mdia", cat(mdhd, hdlr, minf));

        byte[] tkhd = box("tkhd", cat(
                u32(7), u32(0), u32(0), u32(trackId), u32(0), u32(durationMs),
                new byte[8], u16(0), u16(0), u16(video ? 0 : 0x0100), u16(0),
                matrix(), u32((long) t.width << 16), u32((long) t.height << 16)));
        return box("trak", cat(tkhd, mdia));
    }

    private static byte[] avc1(Track t) throws Exception {
        byte[] avcC = box("avcC", cat(
                new byte[]{1, t.sps[1], t.sps[2], t.sps[3], (byte) 0xFF, (byte) 0xE1},
                u16(t.sps.length), t.sps,
                new byte[]{1}, u16(t.pps.length), t.pps));
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(e);
        d.write(new byte[6]); // reserved
        d.writeShort(1);      // data_reference_index
        d.writeShort(0);      // pre_defined
        d.writeShort(0);      // reserved
        d.write(new byte[12]); // pre_defined[3]
        d.writeShort(t.width);
        d.writeShort(t.height);
        d.writeInt(0x00480000); // 72 dpi
        d.writeInt(0x00480000);
        d.writeInt(0);
        d.writeShort(1);       // frame_count
        d.write(new byte[32]); // compressorname
        d.writeShort(0x0018);  // depth
        d.writeShort(-1);      // pre_defined
        d.write(avcC);
        return box("avc1", e.toByteArray());
    }

    private static byte[] mp4a(Track t) throws Exception {
        int aot = t.aot > 0 ? t.aot : 2;
        byte b0 = (byte) ((aot << 3) | (t.freqIdx >> 1));
        byte b1 = (byte) (((t.freqIdx & 1) << 7) | (t.channels << 3));
        byte[] dsi = new byte[]{0x05, 0x02, b0, b1};
        byte[] decCfgBody = cat(new byte[]{0x40, 0x15}, new byte[3],
                u32(0), u32(0), dsi);
        byte[] decCfg = cat(new byte[]{0x04, (byte) decCfgBody.length}, decCfgBody);
        byte[] sl = new byte[]{0x06, 0x01, 0x02};
        byte[] esBody = cat(u16(0), new byte[]{0}, decCfg, sl);
        byte[] esDesc = cat(new byte[]{0x03, (byte) esBody.length}, esBody);
        byte[] esds = box("esds", cat(u32(0), esDesc));

        ByteArrayOutputStream e = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(e);
        d.write(new byte[6]); // reserved
        d.writeShort(1);      // data_reference_index
        d.writeShort(0);      // version
        d.writeShort(0);      // revision
        d.writeInt(0);        // vendor
        d.writeShort(t.channels);
        d.writeShort(16);     // samplesize
        d.writeShort(0);      // compression id
        d.writeShort(0);      // packet size
        d.writeInt(t.timescale << 16);
        d.write(esds);
        return box("mp4a", e.toByteArray());
    }

    private static byte[] matrix() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeInt(0x00010000);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(0x00010000);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(0x40000000);
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    private static void copy(File f, OutputStream os, Progress cb, int from, int to)
            throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[256 * 1024];
        long total = f.length();
        long done = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                if (cb != null && cb.isCancelled()) throw new UnsupportedException("已取消");
                os.write(buf, 0, n);
                done += n;
                if (cb != null && total > 0) {
                    cb.onProgress(from + (int) ((to - from) * done / total), "写入 MP4…");
                }
            }
        } finally {
            in.close();
        }
    }

    private static void check(Progress cb, int pct, String label) throws UnsupportedException {
        if (cb != null) {
            if (cb.isCancelled()) throw new UnsupportedException("已取消");
            cb.onProgress(pct, label);
        }
    }

    private static byte[] box(String type, byte[]... parts) throws IOException {
        int len = 8;
        for (byte[] p : parts) len += p.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream(len);
        DataOutputStream d = new DataOutputStream(b);
        d.writeInt(len);
        d.writeBytes(type);
        for (byte[] p : parts) d.write(p);
        return b.toByteArray();
    }

    private static byte[] cat(byte[]... parts) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p);
        return b.toByteArray();
    }

    private static byte[] ascii(String s) {
        try {
            return s.getBytes("US-ASCII");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    private static byte[] u32(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16),
                (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u64(long v) {
        return new byte[]{(byte) (v >>> 56), (byte) (v >>> 48),
                (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16),
                (byte) (v >>> 8), (byte) v};
    }

    private static void delete(File f) {
        if (f == null) return;
        try {
            f.delete();
        } catch (Throwable ignored) {
        }
    }
}
