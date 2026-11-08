package com.huandou.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 设备凭据的本地存档。
 *
 * <p>凭据是什么：服务端在验完整条证书链之后签发的一串不透明 token，7 天有效。
 * 它证明的是「这台设备在签发的那一刻，跑的是官方包、系统未解锁」，不是「这个用户是谁」。</p>
 *
 * <p>为什么不加密存：token 的 payload 是明文可读的（规格里就要求不放敏感信息），
 * 加密只会让「解不开就重新 bind」这条恢复路径变复杂，收益为零。真正需要防的是
 * token 被别的 App 读走——这靠的是 Android 的沙箱，不是这里再包一层 AES。</p>
 *
 * <p>失败冷却：后端没上线、断网、被墙，任何一种都会让 bind 失败。不做冷却的话
 * 每次冷启动都要白跑一趟 TEE 密钥生成（1–2 秒 + 一次网络），既费电又没意义。</p>
 */
public final class DeviceToken {

    private static final String P = "hd_device";
    private static final String K_TOK = "tok";
    private static final String K_EXP = "exp";
    private static final String K_LVL = "lvl";
    private static final String K_NEXT = "next_try";
    private static final String K_LAST = "last_err";

    /** 失败后多久再试。6 小时：够躲过「后端还没部署」这种整天的窗口，又不至于永远不试。 */
    private static final long RETRY_MS = 6L * 3600 * 1000;

    /** 提前 1 小时视为「该续期了」，避免卡在边界上请求失败。 */
    private static final long SKEW_S = 3600;

    private DeviceToken() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /**
     * 严格可用：只要求 token 尚未过期，不要求还有一小时余量。
     *
     * <p>这个区别是 fail-closed 下的关键。用 {@link #valid} 做准入的话，
     * 有效期最后那一小时会被当成「没凭据」，于是断网的正版用户在这一小时里
     * 打不开 App——白白丢掉六十分之一的离线窗口，换不到任何安全性。</p>
     */
    public static String usable(Context c) {
        SharedPreferences sp = p(c);
        String t = sp.getString(K_TOK, "");
        long exp = sp.getLong(K_EXP, 0);
        long now = System.currentTimeMillis() / 1000;
        if (t == null || t.length() == 0 || exp <= now) return null;
        return t;
    }

    /** 还够用、但该准备续期的 token；没有或快过期返回 null。 */
    public static String valid(Context c) {
        SharedPreferences sp = p(c);
        String t = sp.getString(K_TOK, "");
        long exp = sp.getLong(K_EXP, 0);
        long now = System.currentTimeMillis() / 1000;
        if (t == null || t.length() == 0 || exp <= now + SKEW_S) return null;
        return t;
    }

    public static long exp(Context c) {
        return p(c).getLong(K_EXP, 0);
    }

    public static int level(Context c) {
        return p(c).getInt(K_LVL, -1);
    }

    public static String lastError(Context c) {
        return p(c).getString(K_LAST, "");
    }

    /** 冷却中就别再试了。 */
    public static boolean cooling(Context c) {
        return System.currentTimeMillis() < p(c).getLong(K_NEXT, 0);
    }

    public static void clear(Context c) {
        // 连冷却一起清。否则「清掉凭据重新校验」会被上一次的失败冷却挡住，
        // 表现成点了没反应——是最难查的那种。
        p(c).edit().remove(K_TOK).remove(K_EXP).remove(K_LVL).remove(K_NEXT).apply();
    }

    /**
     * 一次完整校验的结果，比 boolean 多带了「为什么失败」。
     *
     * <p>fail-closed 的门禁必须区分失败类型：服务端明确说「签名不对」和
     * 「连不上网」在安全上都是不放行，但对用户是两回事——前者不用重试，
     * 后者连上网就行。混成一个 false 只能给出含糊的提示。</p>
     */
    public static final class Verify {
        public boolean ok;
        /** 服务端的 reason，或本地的 no_tee / net / bad_response。 */
        public String reason = "";
        public String detail = "";
        public boolean netErr;
        /** 本地那张证书的结论，诊断页用。 */
        public String attSummary = "";
        public String token = "";
        /** 服务端建议的等待秒数（429 时可能有），-1 表示没说。 */
        public int retryAfter = -1;
        public long exp;
        public int level = -1;
        public int versionCode = -1;

        public String oneLine() {
            if (ok) return "凭据有效至 " + AttestClient.dayOf(exp);
            String r = reason.length() == 0 ? "未签发" : reason;
            return r + (detail.length() > 0 ? " · " + detail : "");
        }
    }

    /** 兼容旧调用点的布尔版本。 */
    public static boolean refresh(Context ctx) {
        return refreshDetail(ctx).ok;
    }

    /**
     * 完整跑一次：取挑战码 → 生成证书 → 换凭据 → 存盘。
     *
     * <p><b>必须在子线程调用</b>，里面有一次 TEE 密钥生成和两次网络往返。</p>
     */
    public static Verify refreshDetail(Context ctx) {
        Verify v = new Verify();
        try {
            // 取挑战码失败 = 网络问题，这一段的异常归 net 是对的。
            AttestClient.Challenge c = AttestClient.fetchChallengeQuick();

            // 生成证书失败是设备问题，不能跟着归成 net。
            // 混在一起的后果很具体：设备不支持的用户会看到「换个网络再试」，
            // 然后一直换网络。所以这里单独兜住。
            KeyAttest.Result r;
            try {
                r = KeyAttest.probe(ctx, c.bytes);
            } catch (Throwable t) {
                r = null;
                v.detail = "生成证书时异常：" + t.getClass().getSimpleName();
            }
            v.attSummary = r == null ? "" : r.summary();
            if (r == null || !r.ok) {
                // 设备连自签证书都拿不到：要么系统太老/厂商没实现，要么系统被改过。
                // 按「以鲁棒性换安全」处理，一律不放行，但文案要区分，不能冤枉人。
                v.reason = "no_tee";
                if (v.detail.length() == 0) v.detail = r == null ? "" : r.err;
            } else {
                AttestClient.Bind b = AttestClient.bindQuick(c, r.pem);
                v.netErr = b.netErr;
                v.reason = b.reason;
                v.detail = b.detail;
                v.retryAfter = b.retryAfter;
                v.level = b.level;
                v.versionCode = b.versionCode;
                v.ok = b.ok && b.token != null && b.token.length() > 0;
                if (v.ok) {
                    v.token = b.token;
                    v.exp = b.exp;
                    p(ctx).edit()
                            .putString(K_TOK, b.token)
                            .putLong(K_EXP, b.exp)
                            .putInt(K_LVL, b.level)
                            .putString(K_LAST, "")
                            .remove(K_NEXT)
                            .apply();
                    return v;
                }
                if (b.ok) v.reason = "no_token";
            }
        } catch (AttestClient.HttpError he) {
            // 请求送到了、服务端说不行。这类不是网络问题，不能让人去换网络。
            v.reason = he.code == 429 ? "busy" : "http_" + he.code;
            v.detail = he.getMessage() == null ? "" : he.getMessage();
            v.retryAfter = he.retryAfter;
        } catch (Throwable t) {
            v.netErr = true;
            v.reason = "net";
            v.detail = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
        p(ctx).edit()
                .putString(K_LAST, v.oneLine())
                .putLong(K_NEXT, System.currentTimeMillis() + retryDelayMs(v))
                .apply();
        return v;
    }

    /**
     * 失败后多久再自动试。
     *
     * <p>网络失败给 30 秒：可能是刚进电梯、刚开飞行模式，恢复得很快，
     * 用户重开一次 App 就该成功。明确拒绝给 6 小时，因为重试一百次也是同一句话，
     * 只是白跑一次 TEE 密钥生成。</p>
     */
    private static long retryDelayMs(Verify v) {
        return v.netErr ? 30L * 1000 : RETRY_MS;
    }
}
