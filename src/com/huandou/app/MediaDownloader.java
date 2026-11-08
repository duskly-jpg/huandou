package com.huandou.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Downloads a media URL into an already-open OutputStream.
 *
 * <p>For HLS it parses master/media playlists and concatenates the segments. Standard
 * HLS encryption (#EXT-X-KEY:METHOD=AES-128) is supported: the key is fetched from the
 * URI the playlist itself advertises, using the SAME session headers (UA / Cookie /
 * Referer) that the player already uses. This is exactly what any HLS player does while
 * playing, just persisted to a file instead of being decoded on the fly.</p>
 *
 * <p>Deliberately NOT supported: SAMPLE-AES, non-identity KEYFORMAT, Widevine /
 * PlayReady / FairPlay or any other real DRM, keys that require bypassing login or
 * identity tickets, and bulk crawling. In those cases the caller gets a clear error and
 * should use "copy link" instead. Nothing here attempts to defeat access control.</p>
 */
public final class MediaDownloader {

    public interface Progress {
        void onProgress(int done, int total, String label);

        boolean isCancelled();
    }

    /** Optional per-URL cookie lookup so key/variant/segment hosts each get their own cookies. */
    public interface CookieProvider {
        String cookieFor(String url);
    }

    /** Thrown for encryption this tool must not handle (real DRM / unavailable key). */
    public static final class EncryptedException extends Exception {
        public EncryptedException(String msg) {
            super(msg);
        }
    }

    private static final int MAX_SEGMENTS = 3000;

    private static final class Key {
        String method;
        String uri;
        String keyFormat;
        byte[] iv;       // explicit IV from the tag, null -> derive from media sequence
        long startSeq;   // sequence number when this key tag became effective
    }

    private static final class Seg {
        String url;
        String range;    // "start-end" for Range header, null for whole file
        Key key;         // effective EXT-X-KEY, null = clear
        long seq;
        boolean isMap;   // fMP4 init segment (#EXT-X-MAP)
    }

    private static final class Variant {
        final String url;
        final long bandwidth;

        Variant(String u, long b) {
            url = u;
            bandwidth = b;
        }
    }

    private static final class Playlist {
        final List<Variant> variants = new ArrayList<Variant>();
        final List<Seg> segments = new ArrayList<Seg>();
        boolean hasMap;
        boolean fmp4Hint;
        String mapUrl;
        String mapRange;
        Key mapKey;
        long mapSeq;
        /** #EXT-X-SESSION-KEY：只在主列表里声明，需带到子列表，否则会把密文当明文存下来。 */
        Key sessionKey;
    }

    private final String ua;
    private final String cookie;
    private final String referer;
    private final Progress cb;
    private final Map<String, byte[]> keyCache = new HashMap<String, byte[]>();
    private CookieProvider cookieProvider;
    /** 仅密钥请求用：临时把 Referer 换成播放列表地址本身（部分网关只认这个）。 */
    private String refererOverride;
    /** 最近一次 readBytes 真正落地的地址（跟随 302 之后）。相对路径必须基于它解析。 */
    private String lastFinalUrl;
    /** 主列表里的 #EXT-X-SESSION-KEY，递归进入子列表时沿用。 */
    private Key carriedSessionKey;

    /** Set by run(): recommended output extension / mime for the merged file. */
    public volatile String outputExt = ".ts";
    public volatile String outputMime = "video/mp2t";
    /** True when the playlist used AES-128 and at least one segment was decrypted. */
    public volatile boolean wasEncrypted = false;

    public MediaDownloader(String ua, String cookie, String referer, Progress cb) {
        this.ua = ua;
        this.cookie = cookie;
        this.referer = referer;
        this.cb = cb;
    }

    public void setCookieProvider(CookieProvider p) {
        this.cookieProvider = p;
    }

    /** Returns the number of bytes written. Throws on failure / cancellation. */
    public long run(String url, OutputStream out) throws Exception {
        outputExt = ".ts";
        outputMime = "video/mp2t";
        wasEncrypted = false;
        keyCache.clear();

        String low = url.toLowerCase(Locale.US);
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;
        if (path.endsWith(".mpd")) {
            throw new Exception("这是 DASH (.mpd) 流，本工具只处理 HLS，请用「复制链接」");
        }
        if (path.endsWith(".m3u8") || path.contains("/playback/hls/")) {
            return runHls(url, out, 0);
        }
        return runDirect(url, out);
    }

    private long runDirect(String url, OutputStream out) throws Exception {
        String low = url.toLowerCase(Locale.US);
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;

        // 先按 URL 后缀定类型；没有后缀的接口地址（?img=123 之类）等拿到
        // Content-Type 再定。旧代码会把认不出的都当 .ts，图片就存成横片了。
        String ext = null;
        String mime = null;
        if (path.endsWith(".mp4") || path.endsWith(".m4v")) {
            ext = ".mp4";
            mime = "video/mp4";
        } else if (path.endsWith(".webm")) {
            ext = ".webm";
            mime = "video/webm";
        } else if (path.endsWith(".mkv")) {
            ext = ".mkv";
            mime = "video/x-matroska";
        } else if (path.endsWith(".mov")) {
            ext = ".mov";
            mime = "video/quicktime";
        } else if (path.endsWith(".flv")) {
            ext = ".flv";
            mime = "video/x-flv";
        } else if (path.endsWith(".mp3")) {
            ext = ".mp3";
            mime = "audio/mpeg";
        } else if (path.endsWith(".m4a")) {
            ext = ".m4a";
            mime = "audio/mp4";
        } else if (path.endsWith(".aac")) {
            ext = ".aac";
            mime = "audio/aac";
        } else if (path.endsWith(".flac")) {
            ext = ".flac";
            mime = "audio/flac";
        } else if (path.endsWith(".ogg") || path.endsWith(".opus")) {
            ext = ".ogg";
            mime = "audio/ogg";
        } else if (path.endsWith(".wav")) {
            ext = ".wav";
            mime = "audio/wav";
        } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
            ext = ".jpg";
            mime = "image/jpeg";
        } else if (path.endsWith(".png")) {
            ext = ".png";
            mime = "image/png";
        } else if (path.endsWith(".gif")) {
            ext = ".gif";
            mime = "image/gif";
        } else if (path.endsWith(".webp")) {
            ext = ".webp";
            mime = "image/webp";
        } else if (path.endsWith(".bmp")) {
            ext = ".bmp";
            mime = "image/bmp";
        } else if (path.endsWith(".avif")) {
            ext = ".avif";
            mime = "image/avif";
        } else if (path.endsWith(".svg")) {
            ext = ".svg";
            mime = "image/svg+xml";
        }

        HttpURLConnection c = openRobust(url, null);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            String ct = c.getContentType();
            if (ext == null) {
                String guess = extFromMime(ct);
                if (guess == null) {
                    if (ct != null && ct.toLowerCase(Locale.US).startsWith("text/html")) {
                        throw new Exception("这个地址返回的是网页而不是文件，可能需要登录或已被防盗链");
                    }
                    guess = ".bin";
                }
                ext = guess;
            }
            if (mime == null) {
                mime = ct == null ? mimeFromExt(ext) : ct.split(";")[0].trim();
            }
            outputExt = ext;
            outputMime = mime;
            InputStream in = c.getInputStream();
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cb != null && cb.isCancelled()) throw new Exception("已取消");
                out.write(buf, 0, n);
                total += n;
                if (cb != null) cb.onProgress((int) (total / 1024), -1, total / 1024 + " KB");
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            return total;
        } finally {
            c.disconnect();
        }
    }

    /** Content-Type → 后缀。认不出返回 null。 */
    private static String extFromMime(String ct) {
        if (ct == null) return null;
        String t = ct.split(";")[0].trim().toLowerCase(Locale.US);
        if (t.equals("image/jpeg") || t.equals("image/jpg")) return ".jpg";
        if (t.equals("image/png")) return ".png";
        if (t.equals("image/gif")) return ".gif";
        if (t.equals("image/webp")) return ".webp";
        if (t.equals("image/bmp")) return ".bmp";
        if (t.equals("image/avif")) return ".avif";
        if (t.equals("image/svg+xml")) return ".svg";
        if (t.equals("audio/mpeg")) return ".mp3";
        if (t.equals("audio/mp4") || t.equals("audio/x-m4a")) return ".m4a";
        if (t.equals("audio/aac")) return ".aac";
        if (t.equals("audio/flac") || t.equals("audio/x-flac")) return ".flac";
        if (t.equals("audio/ogg")) return ".ogg";
        if (t.equals("audio/wav") || t.equals("audio/x-wav")) return ".wav";
        if (t.equals("video/mp4")) return ".mp4";
        if (t.equals("video/webm")) return ".webm";
        if (t.equals("video/quicktime")) return ".mov";
        if (t.equals("video/x-flv")) return ".flv";
        if (t.equals("video/x-matroska")) return ".mkv";
        if (t.equals("video/mp2t")) return ".ts";
        return null;
    }

    private static String mimeFromExt(String ext) {
        if (".jpg".equals(ext)) return "image/jpeg";
        if (".png".equals(ext)) return "image/png";
        if (".gif".equals(ext)) return "image/gif";
        if (".webp".equals(ext)) return "image/webp";
        if (".bmp".equals(ext)) return "image/bmp";
        if (".avif".equals(ext)) return "image/avif";
        if (".svg".equals(ext)) return "image/svg+xml";
        if (".mp3".equals(ext)) return "audio/mpeg";
        if (".m4a".equals(ext)) return "audio/mp4";
        if (".aac".equals(ext)) return "audio/aac";
        if (".flac".equals(ext)) return "audio/flac";
        if (".ogg".equals(ext)) return "audio/ogg";
        if (".wav".equals(ext)) return "audio/wav";
        if (".mp4".equals(ext)) return "video/mp4";
        if (".webm".equals(ext)) return "video/webm";
        if (".mov".equals(ext)) return "video/quicktime";
        if (".mkv".equals(ext)) return "video/x-matroska";
        if (".flv".equals(ext)) return "video/x-flv";
        if (".ts".equals(ext)) return "video/mp2t";
        return "application/octet-stream";
    }

    // ------------------------------ HLS ------------------------------

    private long runHls(String url, OutputStream out, int depth) throws Exception {
        if (depth > 5) throw new Exception("播放列表层级过深");
        String body = readText(url);
        if (body.indexOf("#EXTM3U") < 0) {
            throw new Exception("这个地址返回的不是 HLS 播放列表（可能是接口或鉴权失败），请用「复制链接」核对");
        }
        // 播放列表常被 302 到 CDN；相对路径（尤其密钥的 /api/v1/key/xxx）必须相对
        // 最终地址解析，否则会打到原域名上的同名路径，拿回 403。
        String base = lastFinalUrl == null || lastFinalUrl.length() == 0 ? url : lastFinalUrl;
        Playlist pl = parsePlaylist(base, body);

        if (!pl.variants.isEmpty()) {
            if (pl.sessionKey != null) carriedSessionKey = pl.sessionKey;
            Variant best = null;
            for (Variant v : pl.variants) {
                if (best == null || v.bandwidth > best.bandwidth) best = v;
            }
            return runHls(best.url, out, depth + 1);
        }
        if (pl.segments.isEmpty()) throw new Exception("播放列表里没有分片");

        // 子列表若自己没写 #EXT-X-KEY，但主列表声明了 SESSION-KEY，就沿用后者。
        if (carriedSessionKey != null) {
            for (Seg s : pl.segments) {
                if (s.key == null) s.key = carriedSessionKey;
            }
        }
        if (pl.segments.size() > MAX_SEGMENTS) {
            throw new Exception("分片过多（" + pl.segments.size() + "），请用「复制链接」交给专业下载器");
        }

        for (int i = 0; i < pl.segments.size(); i++) {
            Key k = pl.segments.get(i).key;
            if (k != null) {
                checkSupported(k);
                wasEncrypted = true;
            }
        }

        boolean fmp4 = pl.hasMap || pl.fmp4Hint;
        outputExt = fmp4 ? ".mp4" : ".ts";
        outputMime = fmp4 ? "video/mp4" : "video/mp2t";

        long total = 0;
        for (int i = 0; i < pl.segments.size(); i++) {
            if (cb != null && cb.isCancelled()) throw new Exception("已取消");
            Seg s = pl.segments.get(i);
            byte[] data;
            try {
                data = readBytes(s.url, s.range);
            } catch (Exception e) {
                throw new Exception("第 " + (i + 1) + "/" + pl.segments.size()
                        + " 个分片下载失败：" + e.getMessage());
            }
            if (s.key != null) {
                try {
                    data = decrypt(data, keyBytesFor(s.key, url), s, fmp4);
                } catch (Exception e) {
                    throw new Exception("第 " + (i + 1) + " 个分片解密失败：" + e.getMessage()
                            + "（若是密钥 403/404，说明站点要求会话票据）");
                }
            }
            out.write(data);
            total += data.length;
            if (cb != null) {
                String label = (i + 1) + " / " + pl.segments.size()
                        + (s.key != null ? "（已解密）" : "");
                cb.onProgress(i + 1, pl.segments.size(), label);
            }
        }
        return total;
    }

    private static void checkSupported(Key k) throws EncryptedException {
        String m = k.method == null ? "" : k.method.trim().toUpperCase(Locale.US);
        if (m.length() == 0 || "NONE".equals(m)) return;
        if (!"AES-128".equals(m)) {
            throw new EncryptedException("该流使用 " + m
                    + "（非标准 AES-128，可能属于 DRM），本工具不处理也不提供绕过方式，请用「复制链接」");
        }
        if (k.keyFormat != null) {
            String kf = k.keyFormat.replace("\"", "").trim();
            if (kf.length() > 0 && !"identity".equalsIgnoreCase(kf)) {
                throw new EncryptedException("该流使用非 identity 密钥格式（" + kf
                        + "），属于 DRM 范畴，本工具不处理");
            }
        }
        if (k.uri == null || k.uri.length() == 0) {
            throw new EncryptedException("播放列表未给出密钥地址（URI），无法解密");
        }
    }

    private Playlist parsePlaylist(String base, String body) throws Exception {
        Playlist pl = new Playlist();
        Key cur = null;
        long seq = 0;
        boolean variantNext = false;
        long variantBw = 0;
        String pendingRange = null;
        Map<String, Long> nextOffset = new HashMap<String, Long>();

        String[] lines = body.split("\\r?\\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li].trim();
            if (line.length() == 0) continue;
            if (line.charAt(0) == '#') {
                if (line.startsWith("#EXT-X-KEY:")) {
                    cur = parseKey(line, seq);
                } else if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                    Key sk = parseKey(line, seq);
                    if (sk != null) pl.sessionKey = sk;
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    seq = parseLong(afterColon(line), 0);
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    Map<String, String> a = parseAttrs(line);
                    String u = a.get("URI");
                    if (u != null && u.length() > 0) {
                        pl.hasMap = true;
                        pl.mapUrl = absUrl(base, u);
                        pl.mapRange = resolveRange(a.get("BYTERANGE"), pl.mapUrl, nextOffset);
                        pl.mapKey = cur;
                        pl.mapSeq = seq;
                    }
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    pendingRange = afterColon(line);
                } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                    variantNext = true;
                    variantBw = parseBandwidth(line);
                }
                continue;
            }
            String abs = absUrl(base, line);
            if (variantNext) {
                pl.variants.add(new Variant(abs, variantBw));
                variantNext = false;
                variantBw = 0;
            } else {
                Seg s = new Seg();
                s.url = abs;
                s.key = cur;
                s.seq = seq;
                s.range = resolveRange(pendingRange, abs, nextOffset);
                pl.segments.add(s);
                pendingRange = null;
                seq++;
                String l = abs.toLowerCase(Locale.US);
                if (l.contains(".m4s") || l.contains(".mp4") || l.contains(".m4v")) {
                    pl.fmp4Hint = true;
                }
            }
        }
        if (pl.mapUrl != null) {
            Seg m = new Seg();
            m.url = pl.mapUrl;
            m.key = pl.mapKey;
            m.seq = pl.mapSeq;
            m.range = pl.mapRange;
            m.isMap = true;
            pl.segments.add(0, m);
            pl.fmp4Hint = true;
        }
        return pl;
    }

    private static Key parseKey(String line, long seq) {
        Map<String, String> a = parseAttrs(line);
        String m = a.get("METHOD");
        if (m == null || "NONE".equalsIgnoreCase(m.trim())) return null;
        Key k = new Key();
        k.method = m.trim();
        k.uri = a.get("URI");
        k.keyFormat = a.get("KEYFORMAT");
        k.startSeq = seq;
        String iv = a.get("IV");
        if (iv != null) k.iv = parseIv(iv);
        return k;
    }

    /** Parses KEY=VALUE,KEY="VALUE,WITH,COMMAS" pairs. */
    private static Map<String, String> parseAttrs(String line) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        int colon = line.indexOf(':');
        String s = colon >= 0 ? line.substring(colon + 1) : "";
        StringBuilder k = new StringBuilder();
        StringBuilder v = new StringBuilder();
        boolean inKey = true;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inKey) {
                if (ch == '=') {
                    inKey = false;
                } else if (ch == ',') {
                    k.setLength(0);
                    v.setLength(0);
                } else if (ch != ' ') {
                    k.append(ch);
                }
            } else if (quoted) {
                if (ch == '"') quoted = false;
                else v.append(ch);
            } else {
                if (ch == '"') {
                    quoted = true;
                } else if (ch == ',') {
                    m.put(k.toString().trim().toUpperCase(Locale.US), v.toString().trim());
                    k.setLength(0);
                    v.setLength(0);
                    inKey = true;
                } else {
                    v.append(ch);
                }
            }
        }
        if (k.length() > 0) {
            m.put(k.toString().trim().toUpperCase(Locale.US), v.toString().trim());
        }
        return m;
    }

    // ------------------------- key + AES -------------------------

    private byte[] keyBytesFor(Key k, String baseUrl) throws EncryptedException {
        String id = k.uri.startsWith("data:") ? k.uri : absUrl(baseUrl, k.uri);
        byte[] cached = keyCache.get(id);
        if (cached != null) return cached;

        byte[] keyBytes;
        if (k.uri.startsWith("data:")) {
            try {
                keyBytes = decodeDataUri(k.uri);
            } catch (Exception e) {
                throw new EncryptedException("播放列表内嵌密钥无法解析：" + e.getMessage());
            }
        } else {
            try {
                keyBytes = readBytes(id, null);
            } catch (Exception e) {
                // 有些网关只认「Referer = 播放列表地址」，播放页地址一律 403。
                // 换一次 Referer 重发同一个请求，仍失败就把两次的原因一起报出来。
                String first = e.getMessage();
                refererOverride = baseUrl;
                try {
                    keyBytes = readBytes(id, null);
                } catch (Exception e2) {
                    throw new EncryptedException("密钥获取失败（" + e2.getMessage()
                            + "；首试 " + first
                            + "）：站点可能要求会话或票据，本工具不绕过鉴权，请用「复制链接」");
                } finally {
                    refererOverride = null;
                }
            }
        }
        if (keyBytes == null
                || (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32)) {
            throw new EncryptedException("密钥长度异常（"
                    + (keyBytes == null ? 0 : keyBytes.length)
                    + " 字节），不是可用的 AES 密钥，可能返回了错误页");
        }
        keyCache.put(id, keyBytes);
        return keyBytes;
    }

    private byte[] decrypt(byte[] data, byte[] key, Seg s, boolean fmp4) throws Exception {
        if (data.length == 0) return data;
        if (data.length % 16 != 0) {
            throw new Exception("加密分片长度不是 16 的倍数（" + data.length
                    + "），可能不是标准 HLS AES-128 流");
        }
        List<byte[]> ivs = ivCandidates(s);
        byte[] best = null;
        double bestScore = -2;
        String lastErr = null;
        for (int i = 0; i < ivs.size(); i++) {
            byte[] iv = ivs.get(i);
            // IV 必须正好 16 字节。长度不对时 native 层抛的是
            // "byte[] offset=-15 length=1 dst.length=16" —— 天书，得反推半天才知道
            // 是 IV 短了。这里先拦一道，把话说清楚。
            if (iv == null || iv.length != 16) {
                lastErr = "IV 长度异常（" + (iv == null ? 0 : iv.length) + " 字节，应为 16）";
                continue;
            }
            try {
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"), new IvParameterSpec(ivs.get(i)));
                byte[] d = c.doFinal(data);
                double score = mediaScore(d, fmp4);
                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            } catch (Exception e) {
                lastErr = e.getMessage();
            }
        }
        if (best != null) return best;
        throw new Exception("分片解密失败" + (lastErr == null ? "" : "：" + lastErr));
    }

    /**
     * RFC 8216: without an IV attribute the IV is the media sequence number.
     * Some packers write an explicit IV and then increment it per segment, so we try
     * that too and pick whichever result actually looks like a TS / MP4 payload.
     */
    private static List<byte[]> ivCandidates(Seg s) {
        List<byte[]> out = new ArrayList<byte[]>();
        // 播放列表里写的 IV 只有 16 字节才可信。短了不但解不开，ivAdd 里按下标
        // 从 15 倒着写还会越界，所以在入口就把不合规的丢掉，退回按序号推。
        byte[] declared = (s.key != null && s.key.iv != null && s.key.iv.length == 16)
                ? s.key.iv : null;
        if (declared != null) {
            out.add(declared);
            out.add(ivAdd(declared, s.seq - s.key.startSeq));
            out.add(ivFromSeq(s.seq));
        } else {
            out.add(ivFromSeq(s.seq));
        }
        return out;
    }

    private static byte[] ivFromSeq(long seq) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) {
            iv[15 - i] = (byte) (seq >>> (8 * i));
        }
        return iv;
    }

    private static byte[] ivAdd(byte[] iv, long delta) {
        byte[] r = iv.clone();
        if (delta == 0) return r;
        long carry = delta;
        for (int i = 15; i >= 0 && carry != 0; i--) {
            long v = (r[i] & 0xFFL) + (carry & 0xFFL);
            r[i] = (byte) v;
            carry = (carry >>> 8) + (v >>> 8);
        }
        return r;
    }

    /**
     * Wrong IVs are hard to spot: AES-CBC only corrupts the first plaintext block, and
     * most candidate IVs differ in just their last byte. TS sync bytes therefore still
     * line up. A valid PAT CRC is a much stronger signal, so we score every candidate and
     * keep the best instead of taking the first plausible one.
     */
    private static double mediaScore(byte[] d, boolean fmp4) {
        if (d == null || d.length < 8) return -1;
        if (fmp4) {
            String t = new String(d, 4, 4, java.nio.charset.Charset.forName("US-ASCII"));
            return ("ftyp".equals(t) || "styp".equals(t) || "moof".equals(t)
                    || "sidx".equals(t) || "moov".equals(t) || "emsg".equals(t)) ? 100 : 0;
        }
        if (d.length < 188 || (d[0] & 0xFF) != 0x47) return -1;
        int packets = Math.min(d.length / 188, 8);
        int sync = 0;
        for (int i = 0; i < packets; i++) {
            if ((d[i * 188] & 0xFF) == 0x47) sync++;
        }
        double score = sync * 10.0 / packets;
        if (patCrcOk(d)) score += 100;
        return score;
    }

    private static boolean patCrcOk(byte[] d) {
        if (d.length < 188) return false;
        int b1 = d[1] & 0xFF;
        int b2 = d[2] & 0xFF;
        int b3 = d[3] & 0xFF;
        int pid = ((b1 & 0x1F) << 8) | b2;
        if ((d[0] & 0xFF) != 0x47 || pid != 0 || (b1 & 0x40) == 0) return false;
        int afc = (b3 >> 4) & 0x03;
        if (afc != 1 && afc != 3) return false;
        int start = 4;
        if (afc == 3) {
            start = 5 + (d[4] & 0xFF);
        }
        if (start >= 188) return false;
        int sec = start + 1 + (d[start] & 0xFF);
        if (sec + 3 >= 188 || (d[sec] & 0xFF) != 0x00) return false;
        int secLen = ((d[sec + 1] & 0x0F) << 8) | (d[sec + 2] & 0xFF);
        int total = 3 + secLen;
        if (total < 8 || sec + total > 188) return false;
        long crc = 0xFFFFFFFFL;
        for (int i = sec; i < sec + total - 4; i++) {
            crc = mpegCrc(crc, d[i] & 0xFF);
        }
        long want = ((long) (d[sec + total - 4] & 0xFF) << 24)
                | ((long) (d[sec + total - 3] & 0xFF) << 16)
                | ((long) (d[sec + total - 2] & 0xFF) << 8)
                | (long) (d[sec + total - 1] & 0xFF);
        return crc == want;
    }

    /** MPEG-2 CRC32 (MSB-first, poly 0x04C11DB7, no reflection / final xor). */
    private static long mpegCrc(long crc, int b) {
        crc ^= ((long) b) << 24;
        crc &= 0xFFFFFFFFL;
        for (int i = 0; i < 8; i++) {
            crc = ((crc & 0x80000000L) != 0)
                    ? (((crc << 1) ^ 0x04C11DB7L) & 0xFFFFFFFFL)
                    : ((crc << 1) & 0xFFFFFFFFL);
        }
        return crc;
    }

    private static byte[] decodeDataUri(String uri) throws Exception {
        int comma = uri.indexOf(',');
        if (comma < 0) throw new Exception("data URI 缺逗号");
        String meta = uri.substring(5, comma).toLowerCase(Locale.US);
        String payload = uri.substring(comma + 1).trim();
        if (meta.contains("base64")) return base64Decode(payload);
        if (payload.startsWith("0x")) payload = payload.substring(2);
        if (payload.length() % 2 != 0) payload = "0" + payload;
        byte[] r = new byte[payload.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(payload.substring(i * 2, i * 2 + 2), 16);
        }
        return r;
    }

    private static byte[] base64Decode(String s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v;
            if (ch >= 'A' && ch <= 'Z') v = ch - 'A';
            else if (ch >= 'a' && ch <= 'z') v = ch - 'a' + 26;
            else if (ch >= '0' && ch <= '9') v = ch - '0' + 52;
            else if (ch == '+' || ch == '-') v = 62;
            else if (ch == '/' || ch == '_') v = 63;
            else if (ch == '=') break;
            else continue;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bos.write((acc >> bits) & 0xFF);
            }
        }
        return bos.toByteArray();
    }

    // ------------------------- plumbing -------------------------

    private static String afterColon(String line) {
        int i = line.indexOf(':');
        return i >= 0 ? line.substring(i + 1).trim() : "";
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseBandwidth(String line) {
        Map<String, String> a = parseAttrs("X:" + afterColon(line));
        return parseLong(a.get("BANDWIDTH"), 0);
    }

    private static String resolveRange(String spec, String url, Map<String, Long> nextOffset) {
        if (spec == null) return null;
        String s = spec.replace("\"", "").trim();
        long len;
        long off;
        try {
            int at = s.indexOf('@');
            if (at >= 0) {
                len = Long.parseLong(s.substring(0, at).trim());
                off = Long.parseLong(s.substring(at + 1).trim());
            } else {
                len = Long.parseLong(s);
                Long prev = nextOffset.get(url);
                off = prev == null ? 0L : prev.longValue();
            }
        } catch (Exception e) {
            return null;
        }
        nextOffset.put(url, off + len);
        return off + "-" + (off + len - 1);
    }

    private static byte[] parseIv(String raw) {
        try {
            String s = raw.replace("\"", "").trim();
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
            if (s.length() > 32) s = s.substring(s.length() - 32);
            while (s.length() < 32) s = "0" + s;
            byte[] b = new byte[16];
            for (int i = 0; i < 16; i++) {
                b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static String absUrl(String base, String rel) {
        if (rel == null) return base;
        if (rel.startsWith("data:")) return rel;
        try {
            return new URL(new URL(base), rel).toString();
        } catch (Exception e) {
            return rel;
        }
    }

    private String readText(String url) throws Exception {
        return new String(readBytes(url, null), "UTF-8");
    }

    private byte[] readBytes(String url, String range) throws Exception {
        HttpURLConnection c = openRobust(url, range);
        try {
            int code = c.getResponseCode();
            // 一部分 WAF 不握手失败，而是按 ClientHello 直接判 403。遇到 401/403 时
            // 换系统 TLS 再要一次——这不是绕过鉴权，只是换一条正常的 TLS 实现重发同一个请求。
            if ((code == 401 || code == 403) && spoofable(url)) {
                try { c.disconnect(); } catch (Throwable ignored) {}
                c = open(url, range, false);
                c.connect();
                code = c.getResponseCode();
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + bodyHint(c) + " @" + hostOf(url));
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cb != null && cb.isCancelled()) throw new Exception("已取消");
                if (n > 0) bos.write(buf, 0, n);
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                URL fin = c.getURL();
                lastFinalUrl = fin == null ? url : fin.toString();
            } catch (Throwable ignored) {
                lastFinalUrl = url;
            }
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url, String range, boolean allowSpoof) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "*/*");
        if (ua != null && ua.length() > 0) c.setRequestProperty("User-Agent", ua);
        String ck = null;
        if (cookieProvider != null) {
            try {
                ck = cookieProvider.cookieFor(url);
            } catch (Throwable ignored) {
            }
        }
        if ((ck == null || ck.length() == 0) && cookie != null && cookie.length() > 0
                && sameHost(url, referer)) {
            // 只在同站时才回退到入口页那份 cookie。把 A 站的 cookie 发给 B 站
            // 既泄漏登录态，也常被对方 WAF 当成伪造请求直接拒掉。
            ck = cookie;
        }
        if (ck != null && ck.length() > 0) c.setRequestProperty("Cookie", ck);
        String ref = refererOverride != null ? refererOverride : referer;
        if (ref != null && ref.length() > 0) {
            c.setRequestProperty("Referer", ref);
            // 密钥网关常按 Origin + Referer 一起判来源，缺一个就 403。
            String origin = originOf(ref);
            if (origin != null) c.setRequestProperty("Origin", origin);
        }
        if (range != null && range.length() > 0) {
            c.setRequestProperty("Range", "bytes=" + range);
        }
        // 应用层 TLS 指纹伪装：把自搓 TLS 插进 HttpsURLConnection（无需 MITM / VPNService）。
        if (allowSpoof && TlsSpoofConfig.get().isEnabled()
                && url.toLowerCase(Locale.US).startsWith("https")) {
            ((HttpsURLConnection) c).setSSLSocketFactory(SpoofSSLSocketFactory.getInstance());
        }
        return c;
    }

    /**
     * 打开连接并在需要时强制完成握手。若伪装握手失败（例如服务器仅支持 TLS1.3），
     * 自动回退到系统原生 TLS 再试一次，保证不丢流。
     */
    private HttpURLConnection openRobust(String url, String range) throws Exception {
        boolean canSpoof = TlsSpoofConfig.get().isEnabled()
                && url.toLowerCase(Locale.US).startsWith("https");
        HttpURLConnection c = open(url, range, canSpoof);
        if (canSpoof) {
            try {
                c.connect();
            } catch (IOException e) {
                try { c.disconnect(); } catch (Throwable ignored) {}
                c = open(url, range, false);
                c.connect();   // 仍失败则向上抛
            }
        }
        return c;
    }

    // ------------------------- 小工具（诊断 / 同源判断） -------------------------

    private static boolean spoofable(String url) {
        return url != null
                && TlsSpoofConfig.get().isEnabled()
                && url.toLowerCase(Locale.US).startsWith("https");
    }

    /** 取错误响应体的开头一小段，用来区分「WAF 拦截页」和「缺票据」两种 403。 */
    private static String bodyHint(HttpURLConnection c) {
        try {
            InputStream es = c.getErrorStream();
            if (es == null) return "";
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n = es.read(buf);
            try { es.close(); } catch (Throwable ignored) {}
            if (n <= 0) return "";
            b.write(buf, 0, n);
            String s = new String(b.toByteArray(), "UTF-8").replaceAll("\\s+", " ").trim();
            if (s.length() > 110) s = s.substring(0, 110) + "…";
            return s.isEmpty() ? "" : "（" + s + "）";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "?" : h;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String originOf(String ref) {
        try {
            URL u = new URL(ref);
            int p = u.getPort();
            String port = (p == -1 || p == u.getDefaultPort()) ? "" : ":" + p;
            return u.getProtocol() + "://" + u.getHost() + port;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 两个地址是否同主机；解析失败时按「同站」处理，保持原有回退行为。 */
    private static boolean sameHost(String a, String b) {
        if (a == null || b == null) return true;
        try {
            String ha = new URL(a).getHost();
            String hb = new URL(b).getHost();
            if (ha == null || hb == null) return true;
            return ha.equalsIgnoreCase(hb);
        } catch (Throwable t) {
            return true;
        }
    }
}
