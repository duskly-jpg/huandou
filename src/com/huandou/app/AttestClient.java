package com.huandou.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 和服务端 Key Attestation 接口说话的那一层。
 *
 * <p>只做三件事：取挑战码、交证书链、把结论翻译成一句人话。所有判断都在服务端，
 * 这里<b>刻意不做任何本地放行/拦截</b>——本地能改的结论没有意义，写在这里只会让
 * 人误以为「过了就是真的」。</p>
 *
 * <p>安全上的几条硬约束，改代码时别动：</p>
 * <ul>
 *   <li>协议必须是 https。宁可连不上，也不能降级到明文——证书链里带着设备型号和
 *       包名，明文传输等于把它们广播出去。</li>
 *   <li>不跟随重定向。301/302 是把自己交到别人域名手里最省事的方式。</li>
 *   <li>不自报「我是官方包」。请求里带的 {@code package}/{@code version_code}
 *       只用于服务端比对<a>客户端与证书是否一致</a>，服务端必须以证书为准；
 *       这里的值可以被任何人改，不能作为任何判断依据。</li>
 * </ul>
 *
 * <p>没有引入 OkHttp/Gson：包里已经有 {@code HttpURLConnection}，{@code org.json}
 * 是 Android 平台自带的。多一个依赖就多几 MB 和一份要跟版本的安全公告。</p>
 */
public final class AttestClient {

    /** 后端地址。换域名要发版——这是刻意的：远程配置本身就是新的攻击面。 */
    public static final String BASE = "https://attest.flaros.xyz";

    private static final String P_CHALLENGE = "/v1/attest/challenge";
    private static final String P_VERIFY = "/v1/attest/verify";
    private static final String P_BIND = "/v1/attest/bind";
    private static final String P_CONFIG = "/v1/config";

    private static final int CONNECT_MS = 12000;
    private static final int READ_MS = 20000;

    /**
     * 启动准入用的短超时。
     *
     * <p>准入是在启动路径上跑的，用户盯着一个不能操作的页面。用默认的 12s+20s，
     * 弱网下要等三十多秒才告诉他「连不上」——那时候他已经在想是不是卡死了。
     * 正常到 Cloudflare 的往返是几百毫秒，6+10 秒给得足够宽。</p>
     */
    private static final int GATE_CONNECT_MS = 6000;
    private static final int GATE_READ_MS = 10000;

    /** Cloudflare 会拦掉像脚本的 UA，写个明确的客户端标识。 */
    private static final String UA = "Huandou-Android/1.9.0";

    private AttestClient() {
    }

    // ── 数据结构 ────────────────────────────────────────────────

    /** 服务端下发的一次性挑战码。 */
    public static final class Challenge {
        public String id = "";
        /** 32 字节原文，直接塞进 KeyGenParameterSpec。 */
        public byte[] bytes;
        public String hex = "";
        public int expiresIn = 0;
        public long gotAt = System.currentTimeMillis();

        /** 日志里用的短 id，别把 26 位全打出来占一行。 */
        public String shortId() {
            return id.length() > 10 ? id.substring(0, 10) + "…" : id;
        }

        public int ageSec() {
            return (int) ((System.currentTimeMillis() - gotAt) / 1000);
        }
    }

    /** 服务端的结论。所有字段都是「服务端说的」，不是本地推断的。 */
    public static final class Verdict {
        public int http;
        /** 服务端是否认可这条链。false 不代表设备有问题，也可能是链过期/网络抖动。 */
        public boolean ok;
        /** L3 / L2 / L1 / 空。等级含义见规格文档 §6。 */
        public String verdict = "";
        public int level = -1;
        public int versionCode = -1;
        /** 失败码：chain_broken / not_attested / sig_mismatch / ... */
        public String reason = "";
        public String detail = "";
        public List<String> warnings = new ArrayList<>();
        /** 原始响应，排障时贴给后端用。 */
        public String raw = "";
        /** 往返耗时，毫秒。 */
        public long ms;
        /** 连网本身失败了（DNS/超时/证书），和「验不过」是两回事。 */
        public boolean netErr;

        /** 一行结论，诊断页和 Toast 都用这个。 */
        public String oneLine() {
            if (netErr) return "连不上服务端：" + detail;
            if (ok) {
                StringBuilder sb = new StringBuilder();
                sb.append(verdict.length() > 0 ? verdict : "通过");
                sb.append(" · ");
                sb.append(level == 2 ? "StrongBox" : level == 1 ? "TEE" : level == 0 ? "软件" : "等级?");
                if (versionCode > 0) sb.append(" · v").append(versionCode);
                if (!warnings.isEmpty()) sb.append(" · ").append(warnings.size()).append(" 条提示");
                return sb.toString();
            }
            String r = reason.length() > 0 ? reason : ("HTTP " + http);
            return detail.length() > 0 ? r + " · " + detail : r;
        }

        /** 给日志用的多行细节，只在「技术细节」展开时出现。 */
        public String detailBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("  HTTP ").append(http).append(" · ").append(ms).append("ms\n");
            sb.append("  ok=").append(ok)
                    .append(" verdict=").append(verdict)
                    .append(" level=").append(level)
                    .append(" version_code=").append(versionCode).append('\n');
            if (reason.length() > 0) sb.append("  reason=").append(reason).append('\n');
            if (detail.length() > 0) sb.append("  detail=").append(detail).append('\n');
            for (int i = 0; i < warnings.size(); i++) {
                sb.append("  warn: ").append(warnings.get(i)).append('\n');
            }
            return sb.toString();
        }
    }

    // ── 对外三步 ────────────────────────────────────────────────

    /**
     * 取一个挑战码。120 秒内必须用掉，且只能用一次。
     *
     * @throws IOException 网络/协议层失败
     */
    public static Challenge fetchChallenge() throws IOException {
        return fetchChallenge(CONNECT_MS, READ_MS);
    }

    /**
     * 准入专用的短超时版本。
     *
     * <p>区别只在超时长度：启动路径上用户不能做别的事，等三十秒和等十六秒
     * 是两种体验。功能上完全一样。</p>
     */
    public static Challenge fetchChallengeQuick() throws IOException {
        return fetchChallenge(GATE_CONNECT_MS, GATE_READ_MS);
    }

    /** 自定义超时的版本，启动准入走短超时，别让用户对着不能操作的页面干等。 */
    public static Challenge fetchChallenge(int connMs, int readMs) throws IOException {
        JSONObject req = new JSONObject();
        try {
            req.put("client", "android");
        } catch (Throwable ignored) {
        }

        Resp r = post(P_CHALLENGE, req.toString(), connMs, readMs);
        if (r.code != 200) {
            throw new HttpError(r.code, r.retryAfter,
                    "取挑战码失败 HTTP " + r.code + " " + clip(r.text, 120));
        }
        try {
            JSONObject o = new JSONObject(r.text);
            Challenge c = new Challenge();
            c.id = o.optString("challenge_id", "");
            c.hex = o.optString("challenge_hex", "");
            c.expiresIn = o.optInt("expires_in", 120);
            if (c.hex.length() == 64) {
                c.bytes = hexToBytes(c.hex);
            } else {
                // 只给了 base64url 的情况，兜一手。
                String b = o.optString("challenge", "");
                if (b.length() > 0) {
                    c.bytes = android.util.Base64.decode(b,
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP
                                    | android.util.Base64.NO_PADDING);
                    c.hex = bytesToHex(c.bytes);
                }
            }
            if (c.id.length() == 0 || c.bytes == null || c.bytes.length != 32) {
                throw new IOException("挑战码响应格式不对：" + clip(r.text, 160));
            }
            return c;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("挑战码解析失败：" + t, t);
        }
    }

    /**
     * 交链。
     *
     * @param c       刚取到的挑战码
     * @param pem     整条链的 PEM（多段）
     * @param claimed 可以为 null；非空时会在请求里带上 package / version_code，
     *                用于验证「服务端是否以证书为准」。正常流程传 null。
     */
    public static Verdict verify(Challenge c, String pem, JSONObject claimed) {
        long t0 = System.currentTimeMillis();
        Verdict v = new Verdict();
        try {
            JSONObject body = new JSONObject();
            body.put("challenge_id", c.id);
            JSONArray arr = new JSONArray();
            for (String one : splitPem(pem)) arr.put(one);
            body.put("chain", arr);
            if (claimed != null) {
                // 这两个字段服务端不该信。带上它们是为了做一次「自报字段冲突」测试：
                // 如果返回值跟着这两个值变，说明服务端读了它们——那就是 bug。
                if (claimed.has("package")) body.put("package", claimed.getString("package"));
                if (claimed.has("version_code")) {
                    body.put("version_code", claimed.getInt("version_code"));
                }
            }

            Resp r = post(P_VERIFY, body.toString());
            v.http = r.code;
            v.raw = r.text;
            v.ms = System.currentTimeMillis() - t0;

            JSONObject o = null;
            try {
                o = new JSONObject(r.text);
            } catch (Throwable ignored) {
            }
            if (o == null) {
                v.reason = "bad_response";
                v.detail = clip(r.text, 120);
                return v;
            }

            v.ok = o.optBoolean("ok", false);
            v.verdict = o.optString("verdict", "");
            v.level = o.optInt("level", -1);
            v.versionCode = o.optInt("version_code", -1);
            v.reason = o.optString("reason", "");
            v.detail = o.optString("detail", "");
            JSONArray w = o.optJSONArray("warnings");
            if (w != null) {
                for (int i = 0; i < w.length(); i++) {
                    String s = w.optString(i, "");
                    if (s.length() > 0) v.warnings.add(s);
                }
            }
            return v;
        } catch (Throwable t) {
            v.netErr = true;
            v.ms = System.currentTimeMillis() - t0;
            v.detail = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
            return v;
        }
    }

    // ── 设备凭据 ────────────────────────────────────────────────

    /**
     * 一次 bind 的结果。
     *
     * <p>bind 和 verify 的请求体完全一样，区别只在服务端多做一步：签发一张
     * 7 天有效的 device_token。有了它，后面拉配置就不用每次传整条证书链。</p>
     */
    public static final class Bind {
        public boolean ok;
        public String token = "";
        /** 过期时间，unix 秒。 */
        public long exp;
        public int level = -1;
        public int versionCode = -1;
        public String reason = "";
        public String detail = "";
        public boolean netErr;
        /** 服务端建议的重试等待（秒），429 时可能有。 */
        public int retryAfter = -1;

        public String oneLine() {
            if (netErr) return "连不上：" + detail;
            if (ok) return "凭据有效至 " + dayOf(exp) + " · " + (level == 1 ? "TEE" : "L" + level);
            return (reason.length() > 0 ? reason : "未签发")
                    + (detail.length() > 0 ? " · " + detail : "");
        }
    }

    /**
     * 用一条刚生成的链换设备凭据。
     *
     * <p>注意这里<b>不校验本地结论</b>：链有没有问题由服务端说，客户端不预判。
     * 否则一个被改过的包可以跳过 bind，直接宣称「我已验证」。</p>
     */
    public static Bind bind(Challenge c, String pem) {
        return bind(c, pem, CONNECT_MS, READ_MS);
    }

    /** 准入专用的短超时版本。 */
    public static Bind bindQuick(Challenge c, String pem) {
        return bind(c, pem, GATE_CONNECT_MS, GATE_READ_MS);
    }

    /** 自定义超时的版本。 */
    public static Bind bind(Challenge c, String pem, int connMs, int readMs) {
        Bind b = new Bind();
        try {
            JSONObject body = new JSONObject();
            body.put("challenge_id", c.id);
            JSONArray arr = new JSONArray();
            for (String one : splitPem(pem)) arr.put(one);
            body.put("chain", arr);

            Resp r = post(P_BIND, body.toString(), connMs, readMs);
            if (r.code == 429) {
                // CDN 层限流，请求根本没到 Worker。这不是「设备有问题」，
                // 也不是「网络不通」，是「现在排不上队」，必须分开报。
                b.reason = "busy";
                b.retryAfter = r.retryAfter;
                b.detail = "HTTP 429" + (r.retryAfter > 0 ? " · 建议 " + r.retryAfter + " 秒后" : "");
                return b;
            }
            JSONObject o = null;
            try {
                o = new JSONObject(r.text);
            } catch (Throwable ignored) {
            }
            if (o == null) {
                b.reason = "bad_response";
                b.detail = clip(r.text, 120);
                return b;
            }
            b.ok = o.optBoolean("ok", false);
            b.token = o.optString("device_token", "");
            b.exp = o.optLong("exp", 0);
            b.level = o.optInt("level", -1);
            b.versionCode = o.optInt("version_code", -1);
            b.reason = o.optString("reason", "");
            b.detail = o.optString("detail", "");
            return b;
        } catch (Throwable t) {
            b.netErr = true;
            b.detail = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
            return b;
        }
    }

    /**
     * 拉受保护配置。没有可用凭据时返回 null（调用方用内置默认值）。
     *
     * <p>这里用 GET + Bearer 头。put 到 URL 里会被日志和 Referer 带走。</p>
     */
    public static String fetchConfig(String token) {
        if (token == null || token.length() == 0) return null;
        try {
            URL u = new URL(BASE + P_CONFIG);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setRequestMethod("GET");
                c.setConnectTimeout(CONNECT_MS);
                c.setReadTimeout(READ_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", UA);
                if (c.getResponseCode() != 200) return null;
                return readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static String dayOf(long unixSec) {
        if (unixSec <= 0) return "?";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(unixSec * 1000L));
    }

    // ── HTTP ───────────────────────────────────────────────────

    private static final class Resp {
        int code;
        String text;
        /** Retry-After 头，秒；没有则为 -1。 */
        int retryAfter = -1;
    }

    /**
     * 服务端明确返回了非 200 的响应。
     *
     * <p>和网络异常必须分开：网络异常是「请求没送到」，这个是「送到了、它说不行」。
     * 混成一个 IOException 的话，CDN 限流会显示成「换个网络再试」——用户换十次也没用。</p>
     */
    public static final class HttpError extends IOException {
        public final int code;
        /** 服务端建议等多久再试，秒；-1 表示它没说。 */
        public final int retryAfter;

        HttpError(int code, int retryAfter, String msg) {
            super(msg);
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }

    private static Resp post(String path, String body) throws IOException {
        return post(path, body, CONNECT_MS, READ_MS);
    }

    private static Resp post(String path, String body, int connMs, int readMs)
            throws IOException {
        URL u = new URL(BASE + path);
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new IOException("拒绝非 https 地址");
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(connMs);
            c.setReadTimeout(readMs);
            // 301/302 不跟。跟随等于把证书链交给重定向目标。
            c.setInstanceFollowRedirects(false);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", UA);

            byte[] payload = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(payload);
            } finally {
                os.flush();
                os.close();
            }

            Resp r = new Resp();
            r.code = c.getResponseCode();
            try {
                r.retryAfter = c.getHeaderFieldInt("Retry-After", -1);
            } catch (Throwable ignored) {
            }
            InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.text = readAll(is);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream(2048);
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            try {
                is.close();
            } catch (Throwable ignored) {
            }
        }
        return new String(bo.toByteArray(), "UTF-8");
    }

    // ── 小工具 ──────────────────────────────────────────────────

    /** 把多段 PEM 拆成一段一段。服务端要的是数组，不是一整坨。 */
    static List<String> splitPem(String pem) {
        List<String> out = new ArrayList<>();
        if (pem == null) return out;
        final String B = "-----BEGIN CERTIFICATE-----";
        final String E = "-----END CERTIFICATE-----";
        int i = 0;
        while (true) {
            int b = pem.indexOf(B, i);
            if (b < 0) break;
            int e = pem.indexOf(E, b);
            if (e < 0) break;
            e += E.length();
            out.add(pem.substring(b, e));
            i = e;
        }
        return out;
    }

    private static byte[] hexToBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (int i = 0; i < d.length; i++) {
            int x = d[i] & 0xff;
            if (x < 16) sb.append('0');
            sb.append(Integer.toHexString(x));
        }
        return sb.toString();
    }

    /** 日志/异常里截断长文本，避免一行几百字把界面撑爆。 */
    static String clip(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
