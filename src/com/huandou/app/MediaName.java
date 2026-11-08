package com.huandou.app;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a human readable file name for a captured media URL.
 *
 * <p>Short-drama sites normally expose HLS as something like
 * {@code /2024/1130/8f3a2b1c/1000k/hls/index.m3u8}.  Android's
 * URLUtil.guessFileName() only sees "index", which makes every download look
 * the same in the file manager.  The drama name and the episode number are
 * usually available in the page title, in query parameters, or in the path
 * segments, so we combine all of those hints here.</p>
 *
 * <p>Returns a base name WITHOUT an extension (the caller appends the real
 * output extension).  Dots are removed so DownloadService.stripExt() can never
 * cut into the name.  This class has no Android dependency, which makes it
 * unit-testable on a plain JVM.</p>
 */
public final class MediaName {

    private static final int MAX_LEN = 60;

    private static final Set<String> GENERIC_SEG = new HashSet<String>(Arrays.asList(
            "index", "master", "playlist", "main", "hls", "m3u8", "video", "videos",
            "vod", "media", "stream", "streams", "chunk", "chunks", "segment",
            "segments", "out", "output", "play", "player", "playback", "data", "api",
            "file", "files", "static", "cdn", "public", "upload", "uploads",
            "download", "downloads", "720p", "1080p", "480p", "360p", "240p",
            "hd", "sd", "fhd", "4k", "h264", "avc", "mp4", "ts", "m4s", "live",
            "record", "movie", "movies", "tv", "default", "list", "src", "proxy",
            "get", "getvideo", "v", "u", "s", "p", "www", "com", "net", "cn",
            "https", "http", "null", "undefined", "huandou", "drama", "episode",
            "m3u", "key", "encrypt", "drm", "token", "m3u8", "tslist", "hls64"
    ));

    private static final Set<String> GENERIC_TITLE = new HashSet<String>(Arrays.asList(
            "幻豆", "首页", "主页", "登录", "注册", "播放", "视频", "在线播放", "在线观看",
            "短剧", "影视", "影院", "全集", "home", "index", "login", "register",
            "play", "video", "undefined", "null", "about:blank", "huandou", "app",
            "download", "file", "list", "详情", "详情页面", "我的", "个人中心"
    ));

    /** 播放页上的按钮 / 标签文字，绝不能当剧名。 */
    private static final Set<String> UI_WORDS = new HashSet<String>(Arrays.asList(
            "线路", "切换线路", "分享", "收藏", "点赞", "评论", "弹幕", "倍速", "全屏",
            "下载", "投屏", "设置", "举报", "关注", "已关注", "选集", "简介", "详情",
            "返回", "搜索", "首页", "我的", "更多", "播放", "暂停", "重播", "下一集",
            "上一集", "下一集", "清晰度", "画质", "字幕", "音量", "跳过", "登录", "注册",
            "免费", "会员", "开通", "广告", "加载中", "请稍候", "刷新", "重试"
    ));

    private static final String[] TITLE_KEYS = {
            "title", "name", "video_name", "videoname", "vod_name", "drama_name",
            "drama", "series_name", "series", "film_name", "movie_name", "filename",
            "file", "vname", "playname", "play_name", "n", "f", "剧名", "名称", "片名",
            "视频名", "标题"
    };

    private static final String[] EP_KEYS = {
            "ep", "episode", "episodes", "episode_no", "ep_no", "e", "ji", "j",
            "part", "chapter", "sort", "集", "集数", "index", "num"
    };

    private static final Pattern EP_CN = Pattern.compile(
            "第\\s*0*(\\d{1,4})\\s*[集話话期部回篇]");
    private static final Pattern EP_EN = Pattern.compile(
            "(?:^|[^A-Za-z0-9])(?:episode|ep|e|ji|part)\\s*[-_]?\\s*0*(\\d{1,4})"
                    + "(?=$|[^A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern EP_SE = Pattern.compile(
            "S\\d{1,2}\\s*E\\s*0*(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL = Pattern.compile(
            "(在线观看|在线播放|免费观看|免费|高清完整版|高清版|高清|全集|完整版|未删减|"
                    + "迅雷下载|百度云|播放|观看|hd|1080p|720p|4k|vip)+$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_LONG = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern RESOLUTION = Pattern.compile("^(240|360|480|540|720)$");

    /**
     * 版本号式的路径段 / 参数值，例如 v1、v2、V10、v1.2、ver3。
     *
     * <p>短剧站的播放接口大量使用 {@code /v1/index.m3u8}、{@code ?name=v1、 ?v=2}
     * 这种写法，旧逻辑会把 "v1" 当成片名，于是每一集都存成 v1.mp4、v2.mp4，
     * 完全看不出是哪部剧的第几集。这类纯版本号必须直接判为无意义。</p>
     */
    private static final Pattern VERSION_SEG = Pattern.compile(
            "^(v|ver|version|api)?\\d{1,3}(\\.\\d{1,3}){0,2}$", Pattern.CASE_INSENSITIVE);

    /** 只描述总集数的片段：「共6集」「全80集」，不是剧名。 */
    private static final Pattern TOTAL_EPS = Pattern.compile(
            "^[共全]\\s*0*\\d{1,4}\\s*[集话話期部回]$");

    /** 只由单个字母 + 数字组成、且语义上不是标题的短串：v1 / e3 / ep4 / hd2。 */
    private static final Pattern NON_TITLE_SHORT = Pattern.compile(
            "^(v|e|ep|hd|sd|fhd|q|s|p|f|n|u)\\d{1,4}$", Pattern.CASE_INSENSITIVE);

    private MediaName() {
    }

    /** Main entry point. Returns a safe base name without extension, or "" on failure. */
    public static String guess(String url, String pageTitle) {
        if (url == null || url.length() == 0) return "";
        List<String> segs = segments(path(url));
        Map<String, String> qp = pairs(query(url));
        String rawTitle = pageTitle == null ? "" : pageTitle;

        // 英文站用 S02E05 这种写法，原样保留比拆成「第5集」清楚得多。
        if (!hasCjk(rawTitle)) {
            Matcher se = EP_SE.matcher(rawTitle);
            if (se.find()) {
                String t = cleanTitle(rawTitle);
                if (t.length() > 0 && validTitle(stripEpisode(t))) return sanitize(t);
            }
        }

        String clean = cleanTitle(rawTitle);

        // Use the raw title for the episode lookup: cleanTitle() deliberately
        // drops pure "第N集" fragments so they do not become the drama name.
        String ep = pickEpisode(qp, rawTitle, segs);
        String title = pickTitle(qp, clean, segs, host(url));
        if (title.length() == 0) return "";

        String out = title;
        if (ep != null && !out.contains("第" + ep + "集")) out = out + " 第" + ep + "集";
        return sanitize(out);
    }

    /**
     * 从页面抓到的若干候选文本（document.title、og:title、h1、播放器标题…）
     * 里挑出最像「剧名」的一条。返回原样文本（保留第N集），由 guess() 再清洗。
     */
    public static String bestPageTitle(String... candidates) {
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String c : candidates) {
            if (c == null) continue;
            String t = c.replace('\u00a0', ' ').trim();
            if (t.length() == 0 || t.length() > 120) continue;
            if (t.contains("://") || t.startsWith("www.")) continue;
            String clean = cleanTitle(t);
            if (clean.length() == 0) continue;
            if (!validTitle(stripEpisode(clean))) continue;
            if (UI_WORDS.contains(clean) || UI_WORDS.contains(stripEpisode(clean))) continue;
            int sc = score(clean);
            // 同一部剧在多个元素里出现时，带「第N集」的那条更能代表当前这一集。
            if (episodeFrom(t) != null) sc += 6;
            if (sc > bestScore) {
                bestScore = sc;
                best = t;
            }
        }
        return best;
    }

    /**
     * 分片序号：HLS 的 ts 地址常以 {@code /1000k/hls/xxxx/00012.ts} 结尾。
     * 同名条目并列时（同一集被切成几十段），用它把每一段区分开。
     */
    public static String segmentNo(String url) {
        if (url == null || url.length() == 0) return "";
        List<String> segs = segments(path(url));
        int from = Math.max(0, segs.size() - 3);
        for (int i = segs.size() - 1; i >= from; i--) {
            String t = stripExt(segs.get(i));
            if (!t.matches("^0*\\d{1,5}$")) continue;
            try {
                int n = Integer.parseInt(t.replaceFirst("^0+(?=\\d)", ""));
                if (n > 0 && n <= 99999) return String.valueOf(n);
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    /**
     * URL 最后一段去掉扩展名后的名字，用于图片 / 音频这类「附属资源」。
     *
     * <p>一张封面叫 cover.jpg，比拿剧名给二十张图都起同一个名字有用；
     * 但 9f8a7b6c...jpg、12345.jpg 这种机器名不能用，直接返回空串，
     * 由调用方退化成「剧名 图片」。</p>
     */
    public static String fileBase(String url) {
        if (url == null || url.length() == 0) return "";
        List<String> segs = segments(path(url));
        if (segs.isEmpty()) return "";
        String name = stripExt(segs.get(segs.size() - 1));
        name = name.replace('-', ' ').replace('_', ' ').trim();
        if (name.length() < 2 || name.length() > 40) return "";
        if (name.matches("^[0-9]+$")) return "";
        if (HEX_LONG.matcher(name).matches() || UUID.matcher(name).matches()) return "";
        if (VERSION_SEG.matcher(name.toLowerCase(Locale.US)).matches()) return "";
        String clean = sanitize(name);
        return validTitle(clean) ? clean : "";
    }

    /** 去掉「第N集」后剩下的剧名；解析不出返回 ""。 */
    public static String titleOnly(String name) {
        if (name == null) return "";
        String t = stripEpisode(cleanTitle(name));
        return validTitle(t) ? t : "";
    }

    /** 从任意文本里取集数，拿不到返回 null。UI 也能用。 */
    public static String episodeOf(String text) {
        return episodeFrom(text == null ? "" : text);
    }

    /** Public helper: makes arbitrary user input safe for use as a file name. */
    public static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        boolean lastSep = true; // suppress leading separators
        for (char c : s.toCharArray()) {
            boolean bad = Character.isISOControl(c) || c == '.'
                    || "\\/:*?\"<>|".indexOf(c) >= 0;
            boolean sep = c == ' ' || c == '-' || c == '–' || c == '—'
                    || c == '_' || c == '\u00a0';
            if (bad || sep) {
                if (!lastSep && b.length() > 0) {
                    b.append(' ');
                    lastSep = true;
                }
            } else {
                b.append(c);
                lastSep = false;
            }
        }
        String out = b.toString().trim();
        if (out.length() > MAX_LEN) out = out.substring(0, MAX_LEN).trim();
        return out;
    }

    // ---------------------------------------------------------------- episode

    private static String pickEpisode(Map<String, String> qp, String clean,
                                     List<String> segs) {
        // 1. explicit query keys such as ?ep=12 / ?集=7
        for (String k : EP_KEYS) {
            String v = qp.get(k);
            if (v != null) {
                String n = normNum(v.trim());
                if (n != null) return n;
            }
        }
        // 1b. any episode-looking key with a numeric value (episodeNo=3, 集数=7, ...)
        for (Map.Entry<String, String> en : qp.entrySet()) {
            String k = en.getKey();
            if (k.startsWith("ep") || k.startsWith("ji") || k.contains("集")
                    || k.contains("sort") || k.equals("num") || k.equals("index")) {
                String n = normNum(en.getValue().trim());
                if (n != null) return n;
            }
        }
        // 2. page title ("某某剧 第12集 - 幻豆短剧")
        String e = episodeFrom(clean);
        if (e != null) return e;
        // 3. any query value carrying 第N集 / EPxx
        for (String v : qp.values()) {
            e = episodeFrom(v);
            if (e != null) return e;
        }
        // 4. path segments, last one first ("/12/index.m3u8", "EP07.mp4", ...)
        for (int i = segs.size() - 1; i >= 0; i--) {
            String s = segs.get(i);
            e = episodeFrom(s);
            if (e == null && s.matches("^0*\\d{1,3}$")
                    && !RESOLUTION.matcher(s.replaceFirst("^0+", "")).matches()) {
                e = normNum(s);
            }
            if (e != null) return e;
        }
        return null;
    }

    private static String episodeFrom(String s) {
        if (s == null) return null;
        Matcher m = EP_CN.matcher(s);
        if (m.find()) return normNum(m.group(1));
        m = EP_SE.matcher(s);
        if (m.find()) return normNum(m.group(1));
        m = EP_EN.matcher(s);
        if (m.find()) return normNum(m.group(1));
        return null;
    }

    private static String normNum(String s) {
        if (s == null || !s.matches("^\\d{1,4}$")) return null;
        try {
            int n = Integer.parseInt(s);
            if (n <= 0 || n > 999) return null;
            return String.valueOf(n);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- title

    private static String pickTitle(Map<String, String> qp, String clean,
                                   List<String> segs, String host) {
        // 1. explicit query title keys
        for (String k : TITLE_KEYS) {
            String v = qp.get(k);
            if (v != null) {
                String c = asTitle(v);
                if (c.length() > 0) return c;
            }
        }
        // 2. page title
        String c = asTitle(clean);
        if (c.length() > 0) return c;
        // 3. any query value that contains Chinese characters
        for (String v : qp.values()) {
            c = asTitle(v);
            if (c.length() > 0 && hasCjk(c)) return c;
        }
        // 4. path segments containing Chinese, last one first
        for (int i = segs.size() - 1; i >= 0; i--) {
            String s = stripExt(segs.get(i));
            if (!genericSeg(s) && hasCjk(s)) {
                c = asTitle(s);
                if (c.length() > 0) return c;
            }
        }
        // 5. path segments with readable latin names (skip hashes / ids)
        for (int i = segs.size() - 1; i >= 0; i--) {
            String s = stripExt(segs.get(i));
            if (!genericSeg(s) && !looksLikeHash(s)) {
                c = asTitle(s);
                if (c.length() > 0) return c;
            }
        }
        // 6. last resort: the most readable label of the host, plus a short id
        //    taken from the URL so two videos from the same host stay distinct.
        String[] hp = host.split("\\.");
        String pick = "";
        for (String p : hp) {
            if (p.length() > pick.length() && !GENERIC_SEG.contains(p)
                    && !p.matches("^\\d+$")) {
                pick = p;
            }
        }
        String base = asTitle(pick);
        if (base.length() == 0) return "";
        String id = "";
        for (String s : segs) {
            String t = stripExt(s);
            if (looksLikeHash(t) && t.length() >= 8) {
                id = t.substring(0, Math.min(8, t.length()));
                break;
            }
        }
        return id.length() > 0 ? base + " " + id : base;
    }

    private static String asTitle(String raw) {
        String s = stripExt(stripEpisode(cleanTitle(raw)));
        return validTitle(s) ? s : "";
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\u00a0', ' ').trim();
        if (s.length() == 0) return "";
        String[] parts = s.split("[|｜_]+|\\s+[-–—]\\s+|\\s*·\\s*|\\s{2,}");
        String best = "";
        for (String p : parts) {
            String c = stripTail(p.trim());
            c = c.replaceAll("[（(][^）)]*[）)]", "").trim();
            // A fragment that is only an episode marker ("第12集") must not win.
            if (stripEpisode(c).length() < 2) continue;
            if (TOTAL_EPS.matcher(c.replace(" ", "")).matches()) continue;
            if (score(c) > score(best)) best = c;
        }
        return best;
    }

    private static String stripTail(String s) {
        String prev;
        do {
            prev = s;
            s = TAIL.matcher(s).replaceAll("").trim();
        } while (!s.equals(prev) && s.length() > 1);
        return s;
    }

    private static int score(String s) {
        if (s == null || s.length() < 2) return -100;
        int cjk = 0;
        for (char c : s.toCharArray()) if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
        int sc = cjk * 3;
        if (s.length() <= 40) sc += 4;
        else sc -= 3;
        if (EP_CN.matcher(s).find() || EP_EN.matcher(s).find()) sc += 3;
        String low = s.toLowerCase(Locale.US);
        String[] bad = {"在线", "免费", "全集", "高清", "影院", "影视", "短剧", "官网",
                "下载", "huandou", "幻豆", "www.", ".com", "http",
                // 站点标语 / 导流文案，出现在首页 banner 上，不是剧名
                "随心看", "随便看", "免费看", "在线看", "热门", "推荐", "海量", "尽在",
                "观看", "播放", "全集网", "影视网", "短剧网", "app"};
        for (String b : bad) if (low.contains(b)) sc -= 4;
        if (GENERIC_TITLE.contains(low)) sc -= 10;
        if (cjk == 0 && s.length() < 4) sc -= 3;
        // 剧名通常是 2~10 个纯中文，且不带数字；按钮文字、用户名往往带数字。
        if (cjk >= 2 && cjk <= 10 && cjk == s.length()) sc += 3;
        if (s.matches(".*\\d.*")) sc -= 3;
        if (s.length() > 20) sc -= 5;
        return sc;
    }

    private static String stripEpisode(String s) {
        if (s == null) return "";
        String out = EP_CN.matcher(s).replaceAll("");
        out = EP_EN.matcher(out).replaceAll("");
        out = EP_SE.matcher(out).replaceAll("");
        out = out.replaceAll("[\\s\\-_–—]{2,}", " ").trim();
        return stripTail(out).trim();
    }

    private static boolean validTitle(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2 || s.length() > 50) return false;
        String low = s.toLowerCase(Locale.US);
        if (GENERIC_TITLE.contains(low) || GENERIC_SEG.contains(low)) return false;
        if (VERSION_SEG.matcher(low).matches() || NON_TITLE_SHORT.matcher(low).matches()) {
            return false;
        }
        if (TOTAL_EPS.matcher(s.replace(" ", "")).matches()) return false;
        if (HEX_LONG.matcher(s).matches() || UUID.matcher(s).matches()) return false;
        if (s.matches("^\\d+$")) return false;
        for (char c : s.toCharArray()) if (Character.isLetter(c)) return true;
        return false;
    }

    private static boolean genericSeg(String s) {
        if (s == null || s.length() == 0) return true;
        String low = s.toLowerCase(Locale.US);
        return GENERIC_SEG.contains(low) || GENERIC_SEG.contains(stripExt(low))
                || VERSION_SEG.matcher(stripExt(low)).matches()
                || NON_TITLE_SHORT.matcher(stripExt(low)).matches()
                || low.matches("^\\d{4,}$")
                || low.matches("^(19|20)\\d{2}[-/]?\\d{0,4}$")
                || low.matches("^\\d+(k|kb|m|mb|p|kbps|fps|b)$")
                || HEX_LONG.matcher(low).matches() || UUID.matcher(low).matches();
    }

    private static boolean looksLikeHash(String s) {
        String low = s.toLowerCase(Locale.US);
        if (low.length() >= 12 && low.matches("^[a-z0-9]+$")
                && (low.matches(".*\\d.*") || low.length() >= 20)) return true;
        return HEX_LONG.matcher(low).matches() || UUID.matcher(low).matches();
    }

    private static boolean hasCjk(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) if (c >= 0x4E00 && c <= 0x9FFF) return true;
        return false;
    }

    // ---------------------------------------------------------------- url parts

    private static String host(String url) {
        try {
            int s = url.indexOf("://");
            int start = s >= 0 ? s + 3 : 0;
            int end = url.length();
            for (int i = start; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            String h = url.substring(start, end);
            int at = h.indexOf('@');
            if (at >= 0) h = h.substring(at + 1);
            int colon = h.indexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
            return h.toLowerCase(Locale.US);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String path(String url) {
        int s = url.indexOf("://");
        int start = s >= 0 ? url.indexOf('/', s + 3) : url.indexOf('/');
        if (start < 0) return "";
        int end = url.length();
        int q = url.indexOf('?', start);
        int h = url.indexOf('#', start);
        if (q >= 0) end = Math.min(end, q);
        if (h >= 0) end = Math.min(end, h);
        return url.substring(start, end);
    }

    private static String query(String url) {
        int q = url.indexOf('?');
        if (q < 0) return "";
        int h = url.indexOf('#', q);
        return h < 0 ? url.substring(q + 1) : url.substring(q + 1, h);
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<String>();
        for (String p : path.split("/")) {
            String d = decode(p).trim();
            if (d.length() > 0) out.add(d);
        }
        return out;
    }

    private static Map<String, String> pairs(String q) {
        LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
        if (q == null || q.length() == 0) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            String k = decode(e >= 0 ? kv.substring(0, e) : kv)
                    .toLowerCase(Locale.US);
            String v = decode(e >= 0 ? kv.substring(e + 1) : "");
            m.put(k, v);
            // Also index a punctuation-free alias so "dramaName" and
            // "drama_name" both match the same TITLE_KEY.
            String alias = k.replaceAll("[^a-z0-9\u4e00-\u9fff]", "");
            if (alias.length() > 0 && !alias.equals(k)) m.put(alias, v);
        }
        return m;
    }

    private static String decode(String s) {
        if (s == null) return "";
        try {
            // Keep literal '+' (URLDecoder treats it as a space).
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (Throwable t) {
            return s;
        }
    }

    private static String stripExt(String s) {
        if (s == null) return "";
        int d = s.lastIndexOf('.');
        if (d > 0 && s.length() - d <= 7) return s.substring(0, d);
        return s;
    }
}
