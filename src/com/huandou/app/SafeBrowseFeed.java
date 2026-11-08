package com.huandou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地威胁情报库的下载与更新。
 *
 * <p>这里刻意不采用「每次访问问一次服务器」的在线查毒：那种做法会把用户访问过的
 * 每一个域名都交给第三方，等于用隐私换安全。改为把公开库整包拉下来，之后所有比对
 * 都在手机上做——情报提供方只知道「有人下载了一份列表」，不知道你看了什么。</p>
 *
 * <p>三个源：</p>
 * <ul>
 *   <li>Phishing Army（聚合 PhishTank、OpenPhish、CERT.PL、urlscan.io 等 20 多家
 *       的钓鱼域名，十几万条）</li>
 *   <li>malware-filter 的 URLhaus 主机表（恶意软件分发域名，12 小时一更）</li>
 *   <li>OpenPhish 实时源（正在活跃的钓鱼页，条数少但很新）</li>
 * </ul>
 *
 * <p>如果设置了「隐私代理前缀」，所有下载都会走那个前缀（例如自建的
 * Cloudflare Worker），源站只能看到代理的 IP。</p>
 */
public final class SafeBrowseFeed {

    public interface Callback {
        /** @param total 更新后本地库总条数；error 为 null 表示成功 */
        void done(int total, String error);
    }

    public static final String KEY_TS = "safe_feed_ts";
    public static final String KEY_N_PHISH = "safe_feed_n_phish";
    public static final String KEY_N_MAL = "safe_feed_n_mal";
    public static final String KEY_TRY_TS = "safe_feed_try_ts";

    private static final String PREF = "huandou_pref";

    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private static final class Feed {
        final String name;
        final String[] urls;
        final boolean urlList;
        final boolean phishing;

        Feed(String name, boolean phishing, boolean urlList, String... urls) {
            this.name = name;
            this.phishing = phishing;
            this.urlList = urlList;
            this.urls = urls;
        }
    }

    private static final Feed[] FEEDS = {
            new Feed("Phishing Army", true, false,
                    "https://phishing.army/download/phishing_army_blocklist_extended.txt",
                    "https://phishing.army/download/phishing_army_blocklist.txt"),
            new Feed("URLhaus", false, false,
                    "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt",
                    "https://malware-filter.pages.dev/urlhaus-filter-hosts.txt"),
            new Feed("OpenPhish", true, true,
                    "https://openphish.com/feed.txt")
    };

    private static final int MAX_ENTRIES = 400000;

    private SafeBrowseFeed() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "safe");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    public static File phishFile(Context c) {
        return new File(dir(c), "phish.bin");
    }

    public static File malwareFile(Context c) {
        return new File(dir(c), "malware.bin");
    }

    /**
     * 后台拉取并重建本地库。可重复调用，正在跑时直接返回。
     */
    public static void refresh(final Context ctx, final String proxyPrefix, final Callback cb) {
        if (!running.compareAndSet(false, true)) {
            if (cb != null) cb.done(storeTotal(ctx), null);
            return;
        }
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                int total = 0;
                String err = null;
                try {
                    LongSink phish = new LongSink();
                    LongSink malware = new LongSink();
                    StringBuilder fails = new StringBuilder();
                    int ok = 0;

                    for (Feed f : FEEDS) {
                        boolean got = false;
                        String last = null;
                        for (String u : f.urls) {
                            try {
                                byte[] body = httpGet(u, proxyPrefix);
                                if (body == null || body.length < 64) throw new IOException("内容为空");
                                LongSink sink = f.phishing ? phish : malware;
                                int before = sink.n;
                                parse(body, sink);
                                int added = sink.n - before;
                                last = null;
                                got = added > 0;
                                if (got) ok++;
                                break;
                            } catch (Throwable t) {
                                last = t.getClass().getSimpleName()
                                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
                            }
                        }
                        if (!got) {
                            if (fails.length() > 0) fails.append("；");
                            fails.append(f.name).append(' ').append(last == null ? "无数据" : last);
                        }
                    }

                    long[] ph = phish.sortedUnique();
                    long[] mal = malware.sortedUnique();
                    // 只写回「拿到足够数据」的那一类，避免某次半失败把好库冲掉。
                    if (ph.length >= 200) SafeBrowse.save(phishFile(ctx), ph);
                    if (mal.length >= 50) SafeBrowse.save(malwareFile(ctx), mal);

                    SafeBrowse.Store ps = SafeBrowse.load(phishFile(ctx), "钓鱼站点库");
                    SafeBrowse.Store ms = SafeBrowse.load(malwareFile(ctx), "恶意软件库");
                    total = ps.size() + ms.size();

                    SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                    p.edit()
                            .putLong(KEY_TS, System.currentTimeMillis())
                            .putInt(KEY_N_PHISH, ps.size())
                            .putInt(KEY_N_MAL, ms.size())
                            .apply();

                    if (ok == 0) {
                        err = "情报源都连不上（" + fails + "）。本地旧库还在，规则判定照常。";
                    } else if (fails.length() > 0) {
                        err = "部分源没更新：" + fails;
                    }
                } catch (Throwable t) {
                    err = "更新失败：" + t.getClass().getSimpleName();
                } finally {
                    running.set(false);
                }
                final int t2 = total;
                final String e2 = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.done(t2, e2);
                    }
                });
            }
        }, "huandou-safe-feed").start();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static boolean isRunning() {
        return running.get();
    }

    public static int storeTotal(Context c) {
        return SafeBrowse.load(phishFile(c), "钓鱼站点库").size()
                + SafeBrowse.load(malwareFile(c), "恶意软件库").size();
    }

    // ------------------------------------------------------------------

    private static void parse(byte[] body, LongSink out) throws IOException {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(body),
                        java.nio.charset.Charset.forName("UTF-8")), 1 << 16);
        String line;
        int guard = 0;
        while ((line = r.readLine()) != null) {
            if (out.n >= MAX_ENTRIES) break;
            if (++guard > 900000) break;
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            String h = SafeBrowse.hostFromLine(line);
            if (h != null) out.add(SafeBrowse.hashOf(h));
        }
        r.close();
    }

    /**
     * 可增长的长整型数组。用 HashSet&lt;Long&gt; 存十几万条会产生同样多的装箱对象，
     * 在低端机上容易触发 GC；这里直接存原始 long，最后统一排序去重。
     */
    private static final class LongSink {
        long[] a = new long[1 << 16];
        int n = 0;

        void add(long v) {
            if (n == a.length) {
                a = java.util.Arrays.copyOf(a, Math.min(a.length * 2, MAX_ENTRIES + 16));
            }
            if (n < a.length) a[n++] = v;
        }

        long[] sortedUnique() {
            java.util.Arrays.sort(a, 0, n);
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (i == 0 || a[i] != a[i - 1]) a[m++] = a[i];
            }
            return java.util.Arrays.copyOf(a, m);
        }
    }

    private static byte[] httpGet(String url, String proxyPrefix) throws IOException {
        String target = url;
        if (proxyPrefix != null && proxyPrefix.trim().length() > 6) {
            String p = proxyPrefix.trim();
            target = p + URLEncoder.encode(url, "UTF-8");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(40000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/plain,*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 15];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 24 * 1024 * 1024) break;
            }
            in.close();
            return bos.toByteArray();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 给界面用的「上次更新时间」文案。 */
    public static String describe(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int np = p.getInt(KEY_N_PHISH, -1);
        int nm = p.getInt(KEY_N_MAL, -1);
        long ts = p.getLong(KEY_TS, 0);
        if (ts == 0 && np <= 0) return "还没更新过 · 点一下从公开源拉取";
        StringBuilder b = new StringBuilder();
        b.append("钓鱼 ").append(np < 0 ? 0 : np).append(" 条 · 恶意 ")
                .append(nm < 0 ? 0 : nm).append(" 条");
        if (ts > 0) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US);
            b.append(" · ").append(f.format(new java.util.Date(ts))).append(" 更新");
        }
        return b.toString();
    }

    /** 供诊断 / 设置页显示：哪些源、什么协议。 */
    public static List<String> sourceNames() {
        List<String> l = new ArrayList<String>();
        for (Feed f : FEEDS) l.add(f.name);
        return l;
    }
}
