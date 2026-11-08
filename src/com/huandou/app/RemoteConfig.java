package com.huandou.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 服务端下发的配置。
 *
 * <p>这是 attest 真正落地的地方。`app.huandoutv.com` 是第三方站，拦不住它的播放接口；
 * 能拦的只有我们自己的接口。所以必须让 App 依赖一个「只有官方包拿得到」的东西——
 * 就是这份配置。</p>
 *
 * <p>拿不到时一切照旧：所有 getter 都有内置默认值，网络失败、后端没部署、
 * token 过期，任何一种情况都不会让 App 少一个功能。配置是增益，不是依赖。</p>
 *
 * <p><b>设计上的一条硬规矩：开关不是数据。</b>
 * 一个 {@code features.download=false} 对重打包的包毫无意义——它拿不到配置，
 * 走默认值，功能照开。所以「靠配置让改包版掉队」只能靠<b>数据</b>：
 * 公告（时效信息）、黑名单（持续更新的域名表）、以后可能的下发规则。
 * 开关只用来做灰度，不指望它形成差异。</p>
 */
public final class RemoteConfig {

    private static final String P = "hd_config";
    private static final String K_JSON = "json";
    private static final String K_AT = "at";

    /**
     * 黑名单的内存快照。
     *
     * <p>{@code shouldInterceptRequest} 对每个子资源都会调一次，一次页面就是几十上百次。
     * 在那条路径上读 SharedPreferences + 解 JSON 会直接把滚动拖卡，所以这里缓存成
     * 一个已排序的数组，命中判断就是一次线性扫。写入时整体替换引用（volatile 保证可见）。</p>
     */
    private static volatile String[] sHosts = new String[0];

    private static final String[] EMPTY = new String[0];

    /** 上次读取的配置时间戳，用来判断内存快照是否过期。0 表示还没同步过。 */
    private static volatile long sLoadedAt = -1;

    private RemoteConfig() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /**
     * 拉一次并缓存。必须在子线程调用。
     *
     * @return 是否更新成功
     */
    public static boolean sync(Context ctx) {
        String tok = DeviceToken.valid(ctx);
        if (tok == null) return false;
        String json = AttestClient.fetchConfig(tok);
        if (json == null || json.length() == 0) return false;

        // 只做最低限度的结构检查：能解析、有 config 对象。
        // 字段缺失不是错误——以后加字段时老客户端应该继续工作，而不是整份丢弃。
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("config")) return false;
        } catch (Throwable t) {
            return false;
        }

        p(ctx).edit()
                .putString(K_JSON, json)
                .putLong(K_AT, System.currentTimeMillis())
                .apply();
        // 换了新配置，让内存快照失效，下次判断时重建。
        sLoadedAt = -1;
        return true;
    }

    private static JSONObject obj(Context c) {
        try {
            String s = p(c).getString(K_JSON, "");
            if (s.length() == 0) return null;
            return new JSONObject(s).optJSONObject("config");
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 公告 ────────────────────────────────────────────────────

    /** 公告文本，没有就空串。 */
    public static String notice(Context c) {
        JSONObject o = obj(c);
        return o == null ? "" : o.optString("notice", "");
    }

    /**
     * 公告的标识：内容变了就是一个新公告，用户该重新看到一次。
     *
     * <p>用内容哈希而不是时间戳，是因为后端可能因为别的原因重新下发同一份配置，
     * 那样会让用户把同一条公告再看一遍。</p>
     */
    public static String noticeId(Context c) {
        String n = notice(c);
        if (n.length() == 0) return "";
        int h = 0;
        for (int i = 0; i < n.length(); i++) h = h * 31 + n.charAt(i);
        return Integer.toHexString(h);
    }

    // ── 黑名单 ──────────────────────────────────────────────────

    /** 黑名单增量，按行。没有就空数组（不是 null，调用方不用判空）。 */
    public static String[] blocklist(Context c) {
        JSONObject o = obj(c);
        if (o == null) return new String[0];
        JSONArray a = o.optJSONArray("blocklist");
        if (a == null) return new String[0];
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optString(i, "");
        return out;
    }

    /**
     * 这个域名该不该拦。
     *
     * <p>匹配规则是「完全相同，或者是它的子域」——{@code ads.example.com} 命中
     * {@code example.com}，但 {@code notexample.com} 不会命中 {@code example.com}。
     * 不用 {@code endsWith(rule)} 就是这个原因，那样会误伤一堆域名。</p>
     *
     * <p>从调用频率看，这个方法在 {@code shouldInterceptRequest} 里每请求一次，
     * 所以它只做数组扫描，不碰存储。</p>
     */
    public static boolean blocked(Context c, String host) {
        return HostRules.match(hosts(c), host);
    }

    private static String[] hosts(Context c) {
        long at = p(c).getLong(K_AT, 0);
        // 从没同步过 = 一定没有黑名单。直接返回常量空数组，
        // 别在这条高频路径上为了「确认没有」而去解一遍 JSON。
        if (at == 0) return EMPTY;
        // 配置没变就复用快照。at 变了说明 sync 过，重建一次。
        if (sLoadedAt == at) return sHosts;
        sHosts = HostRules.normalize(blocklist(c));
        sLoadedAt = at;
        return sHosts;
    }

    // ── 功能开关 ────────────────────────────────────────────────

    /**
     * 功能开关。
     *
     * <p>调用方必须想清楚 {@code def} 传什么：</p>
     * <ul>
     *   <li>已经发布过的功能 → 传 {@code true}。配置拿不到时用户不该丢功能。</li>
     *   <li>新功能 → 传 {@code false}。这样它天然只对能拿到配置的官方包开放，
     *       重打包的包永远停在「没有这个功能」。</li>
     * </ul>
     *
     * <p>后者才是这个开关真正的用途。前者只是一个紧急刹车。</p>
     */
    public static boolean feature(Context c, String key, boolean def) {
        JSONObject o = obj(c);
        if (o == null) return def;
        JSONObject f = o.optJSONObject("features");
        if (f == null || !f.has(key)) return def;
        return f.optBoolean(key, def);
    }

    /** 上次成功拉取的时间，0 表示从没拉到过。 */
    public static long fetchedAt(Context c) {
        return p(c).getLong(K_AT, 0);
    }

    /** 清掉本地配置，回落到全部默认值。给诊断页的逃生门用。 */
    public static void clear(Context c) {
        p(c).edit().remove(K_JSON).remove(K_AT).apply();
        sHosts = new String[0];
        sLoadedAt = -1;
    }
}
