package com.huandou.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects media URLs observed in WebView resource requests and in the page DOM.
 * Purely observational: it never intercepts or modifies a request.
 *
 * <p>按三类资源分开保存：视频、音频、图片。分类不只是为了界面好看——一个播放页
 * 上的缩略图和头像动辄上百个，如果和视频挤在同一个队列里，视频地址会在几十毫秒内
 * 被顶出上限。这里给每类单独设上限，淘汰时先扔图片，视频尽量留着。</p>
 */
public final class MediaSniffer {

    public static final int CAT_VIDEO = 0;
    public static final int CAT_AUDIO = 1;
    public static final int CAT_IMAGE = 2;

    public static final int MAX_ITEMS = 400;
    /** 分片不展示，但保留少量：极少数页面只挂了 ts，没有 m3u8。 */
    private static final int MAX_TS = 40;
    private static final int MAX_IMG = 120;
    private static final int MAX_AUDIO = 80;

    private static final LinkedHashMap<String, Item> MAP =
            new LinkedHashMap<String, Item>();

    public static final class Item {
        public final String url;
        public final String type;
        public final long time;
        /**
         * Page title at capture time, used to build a meaningful file name.
         * 非 final：页面是单页应用时，视频先于标题出现，稍后抓到真实剧名要能补上。
         */
        public String title;

        Item(String u, String t, String pageTitle) {
            url = u;
            type = t;
            time = System.currentTimeMillis();
            title = pageTitle;
        }
    }

    private MediaSniffer() {
    }

    /** Returns true when a new media URL was actually added. */
    public static synchronized boolean note(String url) {
        return note(url, null);
    }

    /** Remembers which page exposed the resource so later downloads can be named. */
    public static synchronized boolean note(String url, String pageTitle) {
        if (url == null) return false;
        String u = normalize(url);
        if (u == null) return false;
        String t = typeOf(u);
        if (t == null) return false;
        Item old = MAP.get(u);
        if (old != null) {
            // 已经记过这条地址：只在标题从「空」变成「有」时补齐，避免重复上报刷屏。
            if (pageTitle != null && pageTitle.trim().length() > 0
                    && (old.title == null || old.title.trim().length() == 0)) {
                old.title = pageTitle;
            }
            return false;
        }
        MAP.put(u, new Item(u, t, pageTitle));
        trim();
        return true;
    }

    /** 视频类资源（不含分片），用来决定要不要提示「检测到视频」。 */
    public static boolean isVideo(String url) {
        String t = typeOf(url == null ? "" : url);
        return "M3U8".equals(t) || "MP4".equals(t) || "MPD".equals(t)
                || "WEBM".equals(t) || "MOV".equals(t) || "MKV".equals(t)
                || "AVI".equals(t) || "FLV".equals(t) || "3GP".equals(t);
    }

    public static int catOf(String type) {
        if ("IMG".equals(type)) return CAT_IMAGE;
        if ("AUDIO".equals(type)) return CAT_AUDIO;
        return CAT_VIDEO;
    }

    private static void trim() {
        capType("IMG", MAX_IMG);
        capType("AUDIO", MAX_AUDIO);
        capType("TS", MAX_TS);
        while (MAP.size() > MAX_ITEMS) {
            String victim = null;
            // 先扔最旧的图片，其次音频，再次分片，最后才动成片地址。
            for (Map.Entry<String, Item> e : MAP.entrySet()) {
                if (dropRank(e.getValue().type) > 0) {
                    victim = e.getKey();
                    break;
                }
            }
            if (victim == null) victim = MAP.keySet().iterator().next();
            MAP.remove(victim);
        }
    }

    private static void capType(String type, int max) {
        int n = 0;
        for (Item it : MAP.values()) {
            if (type.equals(it.type)) n++;
        }
        if (n <= max) return;
        Iterator<Map.Entry<String, Item>> it = MAP.entrySet().iterator();
        while (it.hasNext() && n > max) {
            if (type.equals(it.next().getValue().type)) {
                it.remove();
                n--;
            }
        }
    }

    private static int dropRank(String type) {
        if ("IMG".equals(type)) return 3;
        if ("AUDIO".equals(type)) return 2;
        if ("TS".equals(type)) return 1;
        return 0;
    }

    /** 某一类的条目；分片默认不返回（列表里一列几十条同名，没有意义）。 */
    public static synchronized List<Item> items(int cat) {
        List<Item> out = new ArrayList<Item>();
        for (Item it : MAP.values()) {
            if (catOf(it.type) != cat) continue;
            if ("TS".equals(it.type)) continue;
            out.add(it);
        }
        if (cat == CAT_VIDEO) {
            // 播放列表 / 整集排前面，原始分片沉底。
            Collections.sort(out, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return rank(a.type) - rank(b.type);
                }
            });
        } else {
            // 图片、音频按抓取时间倒序，最新的在最上面。
            Collections.sort(out, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Long.compare(b.time, a.time);
                }
            });
        }
        return out;
    }

    public static synchronized List<Item> items() {
        return items(CAT_VIDEO);
    }

    public static synchronized int count() {
        return MAP.size();
    }

    public static synchronized int count(int cat) {
        int n = 0;
        for (Item it : MAP.values()) {
            if (catOf(it.type) == cat && !"TS".equals(it.type)) n++;
        }
        return n;
    }

    public static synchronized void clear() {
        MAP.clear();
    }

    public static synchronized void clear(int cat) {
        Iterator<Map.Entry<String, Item>> it = MAP.entrySet().iterator();
        while (it.hasNext()) {
            if (catOf(it.next().getValue().type) == cat) it.remove();
        }
    }

    public static synchronized void remove(String url) {
        MAP.remove(url);
    }

    /**
     * 给「抓到地址时还没拿到标题」的条目补上剧名。
     *
     * <p>视频请求通常比页面渲染剧名更早，条目先以空标题入表。只补最近两分钟内
     * 捕获的条目，避免把新页面的剧名盖到上一个页面的记录上。</p>
     */
    public static synchronized void fillMissingTitles(String pageTitle) {
        if (pageTitle == null) return;
        String t = pageTitle.trim();
        if (t.length() == 0) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Item> e : MAP.entrySet()) {
            Item it = e.getValue();
            if (now - it.time > 120000L) continue;
            if (it.title == null || it.title.trim().length() == 0) it.title = t;
        }
    }

    private static int rank(String t) {
        if ("M3U8".equals(t) || "MPD".equals(t)) return 0;
        if ("TS".equals(t)) return 3;
        if ("MAYBE".equals(t)) return 2;
        return 1;
    }

    /** Returns a short type label, or null when the URL is not media-like. */
    public static String typeOf(String url) {
        if (url == null) return null;
        String low = url.toLowerCase(Locale.US);
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;
        int h = path.indexOf('#');
        if (h > 0) path = path.substring(0, h);

        if (path.endsWith(".m3u8")) return "M3U8";
        if (path.endsWith(".mpd")) return "MPD";
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".m4s")) return "MP4";
        if (path.endsWith(".ts")) return "TS";
        if (path.endsWith(".webm")) return "WEBM";
        if (path.endsWith(".mov")) return "MOV";
        if (path.endsWith(".mkv")) return "MKV";
        if (path.endsWith(".avi")) return "AVI";
        if (path.endsWith(".flv")) return "FLV";
        if (path.endsWith(".3gp")) return "3GP";

        if (path.endsWith(".m4a") || path.endsWith(".mp3") || path.endsWith(".aac")
                || path.endsWith(".flac") || path.endsWith(".ogg")
                || path.endsWith(".opus") || path.endsWith(".wav")) return "AUDIO";

        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".gif") || path.endsWith(".webp")
                || path.endsWith(".bmp") || path.endsWith(".avif")
                || path.endsWith(".svg")) return "IMG";

        // 图片 / 音频常走代理接口：/img?src=/2024/a.jpg、/audio?f=x.mp3。
        // 后缀在查询串里，只看 path 会漏掉，这里再按查询串判一次。
        if (q > 0) {
            String qs = low.substring(q);
            if (qs.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|avif|svg)($|&).*")) {
                return "IMG";
            }
            if (qs.matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav)($|&).*")) {
                return "AUDIO";
            }
        }

        // Sites often serve HLS from extension-less API routes.
        if (path.contains("/playback/hls/") || path.contains("/hls/")
                || path.contains("/playback/files/") || path.endsWith("/master.m3u8")) {
            return "MAYBE";
        }
        return null;
    }

    private static String normalize(String url) {
        String u = url.trim().replace("\\/", "/");
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null;
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        return u;
    }
}
