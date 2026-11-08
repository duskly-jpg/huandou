package com.huandou.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 安全浏览的判定内核：本地情报库 + 多维度评分。
 *
 * <p>设计取向来自两个开源方案，但都落到「手机本地、可离线、不追踪」这一条上：</p>
 *
 * <p><b>1. 情报聚合（Osprey 的思路）</b>：真正的威胁情报不是每次访问都去问服务器
 * 「这个网址坏不坏」——那等于把你的浏览足迹逐条交给第三方。这里的做法是把公开的
 * 钓鱼 / 恶意域名库整包下载到本地（十几万条），命中判断完全离线完成。任何一家情报
 * 提供方都看不到你访问了什么，因为它们根本收不到请求。</p>
 *
 * <p><b>2. 多维评分（VirusDetector 的思路）</b>：没有情报命中的时候，用一批可以在
 * 本地算出来的特征打分——域名是不是刚注册的、有没有用 punycode 伪装、是不是把品牌词
 * 塞进了别的域名、页面是不是在向另一个域名提交密码、有没有 ICP 备案号。分数分档，
 * 只有足够可疑才打断用户。</p>
 *
 * <p>这个类不依赖 Android，可以脱离设备单独跑测试。</p>
 */
public final class SafeBrowse {

    private SafeBrowse() {
    }

    // ------------------------------------------------------------------
    // 结论分级
    // ------------------------------------------------------------------

    /** 没意见。 */
    public static final int CLEAR = 0;
    /** 有点可疑：顶部提示条，不打断。 */
    public static final int NOTICE = 1;
    /** 高危：弹窗确认。 */
    public static final int WARN = 2;
    /** 已命中情报库：整页拦截。 */
    public static final int BLOCK = 3;

    public static final class Verdict {
        public int level = CLEAR;
        public int score = 0;
        public String host = "";
        public String domain = "";
        /** 命中的库名；没命中为空。 */
        public String feed = "";
        /** 域名注册天数；-1 表示没查或查不到。 */
        public int ageDays = -1;
        public final ArrayList<String> reasons = new ArrayList<String>();

        public void note(String s) {
            if (s == null || s.length() == 0) return;
            if (reasons.size() >= 6) return;
            if (reasons.contains(s)) return;
            reasons.add(s);
        }

        public boolean risky() {
            return level >= NOTICE;
        }

        /** 给通知 / 横幅用的一句话。 */
        public String headline() {
            if (level == BLOCK) return "已拦截：命中本地威胁情报";
            if (level == WARN) return "这个网站有几处不对劲";
            if (level == NOTICE) return "这个网站有点可疑";
            return "";
        }

        public String ageText() {
            if (ageDays < 0) return "";
            if (ageDays == 0) return "域名今天才注册";
            if (ageDays < 30) return "域名注册仅 " + ageDays + " 天";
            if (ageDays < 365) return "域名注册约 " + (ageDays / 30) + " 个月";
            return "域名已注册约 " + (ageDays / 365) + " 年";
        }
    }

    /** 页面侧的特征，由注入脚本采集后换算成这几个字段。 */
    public static final class PageSignals {
        public int passwords;
        public int crossForms;
        public int hiddenIframes;
        public int iframes;
        public boolean metaRefresh;
        public boolean obfuscated;
        public boolean downloadLure;
        public boolean blockCopy;
        public boolean icp;
        public boolean mixedContent;
        public boolean zh;
        public String icpNo = "";
    }

    // ------------------------------------------------------------------
    // 品牌 / 可疑词表
    // ------------------------------------------------------------------

    /** {品牌词, 官方域名}。只放「被仿冒最多」的那批，不追求全。 */
    private static final String[][] BRANDS = {
            {"icbc", "icbc.com.cn"},
            {"ccb", "ccb.com"},
            {"abchina", "abchina.com"},
            {"boc", "boc.cn"},
            {"bankofchina", "boc.cn"},
            {"cmbchina", "cmbchina.com"},
            {"alipay", "alipay.com"},
            {"taobao", "taobao.com"},
            {"tmall", "tmall.com"},
            {"jd", "jd.com"},
            {"weixin", "weixin.qq.com"},
            {"wechat", "weixin.qq.com"},
            {"tencent", "tencent.com"},
            {"unionpay", "unionpay.com"},
            {"12306", "12306.cn"},
            {"apple", "apple.com"},
            {"icloud", "icloud.com"},
            {"microsoft", "microsoft.com"},
            {"paypal", "paypal.com"},
            {"google", "google.com"},
            {"telegram", "telegram.org"},
            {"whatsapp", "whatsapp.com"},
            {"binance", "binance.com"},
            {"okx", "okx.com"}
    };

    private static final String[] BAD_TLD = {
            ".top", ".xyz", ".tk", ".cc", ".buzz", ".cyou", ".icu", ".work",
            ".click", ".shop", ".vip", ".ltd", ".fit", ".rest", ".monster",
            ".cfd", ".sbs", ".bond", ".beauty", ".quest", ".life"
    };

    private static final String[] LURE = {
            "verify", "login", "signin", "safe", "secure", "auth", "update",
            "unlock", "account", "service", "support", "help", "wallet", "claim"
    };

    private static final String[] SHORTENER = {
            "bit.ly", "t.cn", "dwz.cn", "tinyurl.com", "goo.gl", "is.gd",
            "rebrand.ly", "t.ly", "url.cn", "suo.im", "cutt.ly", "shorturl.at",
            "rb.gy", "ow.ly", "s.id", "v.gd", "tiny.cc", "u.nu"
    };

    /** 整包导入情报时跳过的「不可能被拿来钓鱼」的大站，降低误伤。 */
    private static final String[] NEVER_BLOCK = {
            "google.com", "youtube.com", "facebook.com", "instagram.com",
            "twitter.com", "x.com", "wikipedia.org", "microsoft.com",
            "apple.com", "amazon.com", "cloudflare.com", "github.com",
            "qq.com", "baidu.com", "taobao.com", "tmall.com", "jd.com",
            "bilibili.com", "douyin.com", "weibo.com", "zhihu.com",
            "alipay.com", "weixin.qq.com", "aliyun.com", "tencent.com"
    };

    // ------------------------------------------------------------------
    // 主判定
    // ------------------------------------------------------------------

    /**
     * 只看地址本身能得出什么结论。不联网、不读页面。
     *
     * @param blacklist 用户自定义黑名单，逗号 / 空白分隔的域名片段
     * @param whitelist 白名单，命中则直接放行
     * @param home      用户设置的首页，视为可信（否则自己天天逛的站会被拦）
     */
    public static Verdict evaluate(String url, String blacklist, String whitelist,
                                   String home, Store phishing, Store malware) {
        Verdict v = new Verdict();
        String host = hostFromUrl(url);
        if (host == null) return v;
        v.host = host;
        v.domain = registrableDomain(host);

        if (isTrusted(host, whitelist, home)) {
            v.note("站点在你的白名单或首页里");
            return v;
        }

        // 1) 情报库：命中即拦截，不再看别的。
        if (phishing != null && phishing.contains(host)) {
            v.score = 100;
            v.level = BLOCK;
            v.feed = phishing.name;
            v.note("命中「" + phishing.name + "」收录的钓鱼域名（本地比对 " + phishing.size() + " 条情报，未联网）");
            return v;
        }
        if (malware != null && malware.contains(host)) {
            v.score = 100;
            v.level = BLOCK;
            v.feed = malware.name;
            v.note("命中「" + malware.name + "」收录的恶意域名（本地比对 " + malware.size() + " 条情报，未联网）");
            return v;
        }

        String low = url.toLowerCase(Locale.US);

        // 2) 自定义黑名单
        if (blacklist != null) {
            for (String bad : blacklist.split("[,\\s]+")) {
                String b = bad.trim().toLowerCase(Locale.US);
                if (b.length() > 2 && host.contains(b)) {
                    v.score = 100;
                    v.level = BLOCK;
                    v.feed = "你的黑名单";
                    v.note("这个域名在你的自定义黑名单里（" + b + "）");
                    return v;
                }
            }
        }

        // 3) 官方域名：直接减分，避免把正常站点吓到用户。
        String official = officialFor(host);
        if (official != null) {
            v.score = 0;
            v.note("这是 " + official + " 的官方域名");
            return v;
        }

        // 4) 域名形态
        String latin = normalizeConfusables(host);
        boolean homograph = !latin.equals(host);
        if (homograph) {
            add(v, 32, "域名里混入了西里尔 / 希腊字母，肉眼几乎看不出和拉丁字母的区别");
            String fake = officialFor(latin);
            if (fake != null) {
                add(v, 26, "把字形换回拉丁字母后正好是 " + fake + "，这是典型的同形字钓鱼");
            }
        }
        if (host.contains("xn--")) {
            add(v, 28, "域名用 punycode 编码（xn--），常被用来伪装成相似的品牌域名");
        }

        String typo = typoOf(host);
        boolean lured = hasLurePath(low);
        if (typo != null) {
            add(v, 56, "域名和「" + typo + "」只差一两个字符，像是一字之差的仿冒站");
            if (lured) add(v, 8, "地址里还带着 login / verify 这类词，像是让你在此登录");
        } else {
            String brand = brandIn(host);
            if (brand != null) {
                add(v, 26, "域名里带着「" + brand + "」的品牌词，但注册域名不是官方那个");
                if (endsWithAny(host, BAD_TLD)) {
                    add(v, 18, "品牌词还配了高风险后缀（" + tldOf(host) + "）");
                }
                if (containsAny(host, LURE)) {
                    add(v, 14, "域名里同时有品牌词和「登录 / 验证」这类诱导词");
                }
                if (lured) add(v, 8, "页面地址也是 login / verify 这种登录入口");
            }
        }

        if (isIp(host)) {
            if (isPrivateIp(host)) {
                v.note("这是局域网地址，通常是路由器或 NAS 的管理页");
            } else {
                add(v, 20, "直接用 IP 地址访问，无法通过证书确认网站身份");
                if ("http".equalsIgnoreCase(schemeOf(url))) {
                    add(v, 30, "而且是明文 HTTP，传输内容可能被中途改写");
                }
                if (portOf(url) > 0 && portOf(url) != 80 && portOf(url) != 443) {
                    add(v, 14, "还用了非常规端口 " + portOf(url));
                }
            }
        } else {
            if (host.length() > 46) add(v, 8, "域名过长（" + host.length() + " 个字符），常用于堆关键词");
            int labels = host.split("\\.").length;
            if (labels >= 5) add(v, 8, "子域名层级过深（" + labels + " 层），常见于廉价钓鱼站");
            if (hyphens(host) >= 4) add(v, 10, "域名里有 " + hyphens(host) + " 个连字符，像是机器拼出来的");
        }

        if (isIp(host) && lured) {
            add(v, 8, "IP 地址后面直接跟着 login / verify，常见于批量搭的钓鱼站");
        }

        if (endsWithAny(host, SHORTENER)) {
            add(v, 10, "这是短链接服务，跳转前看不出最终去哪里");
        }
        if (low.endsWith(".apk") || low.endsWith(".exe") || low.endsWith(".msi")
                || low.contains(".apk?") || low.contains(".exe?")) {
            add(v, 26, "这个链接直接下载安装包或可执行文件，请先确认来源");
        }
        if (low.startsWith("http://") && low.contains("password")) {
            add(v, 16, "明文页面里出现 password 参数，账号可能被窃听");
        }
        if (url.indexOf('@') > 0 && url.indexOf('@') < url.indexOf('/', 8)) {
            add(v, 14, "地址里用了 user@host 的写法，真实域名容易被前面的假域名盖住");
        }

        v.level = levelFor(v.score);
        return v;
    }

    /** 把页面侧的特征叠上去。会改分数、等级与理由。 */
    public static void applyPage(Verdict v, PageSignals p) {
        if (v == null || p == null) return;
        if (v.level == BLOCK) return;

        if (p.passwords > 0 && p.crossForms > 0) {
            add(v, 34, "页面有密码框，但表单提交到另一个域名——这是典型的钓鱼做法");
        }
        if (p.passwords > 0 && p.zh && !p.icp) {
            add(v, 16, "中文页面要求输入密码，却找不到 ICP 备案号");
        } else if (p.passwords > 0 && !p.icp) {
            add(v, 8, "页面要求输入密码，但找不到备案信息");
        }
        if (p.hiddenIframes > 0) {
            add(v, 18, "页面藏了 " + p.hiddenIframes + " 个不可见的 iframe，可能在被暗中加载内容");
        }
        if (p.metaRefresh) {
            add(v, 12, "打开后自动跳转到另一个域名");
        }
        if (p.obfuscated && p.passwords > 0) {
            add(v, 14, "页面脚本经过混淆（eval / unescape / atob 组合），且正在收集密码");
        }
        if (p.downloadLure) {
            add(v, 20, "页面用「立即安装 / 手机中毒 / 清理加速」这类文案诱导下载");
        }
        if (p.mixedContent) {
            add(v, 6, "HTTPS 页面里混了 HTTP 资源，内容可能被中途替换");
        }
        if (p.blockCopy && p.passwords > 0) {
            add(v, 5, "页面禁止右键与复制，同时要求输入密码");
        }
        if (v.ageDays >= 0 && v.ageDays < 30) {
            add(v, 22, v.ageText() + "，正经站点很少在刚注册的域名上收密码");
        } else if (v.ageDays >= 0 && v.ageDays < 180 && p.passwords > 0) {
            add(v, 8, v.ageText() + "，注册时间偏新");
        }
        if (p.icp && v.score > 0) {
            v.score = Math.max(0, v.score - 10);
            v.note("页面底部有 ICP 备案号（" + p.icpNo + "），可信度加一点");
        }
        v.level = levelFor(v.score);
    }

    private static void add(Verdict v, int points, String why) {
        v.score += points;
        if (v.score > 98) v.score = 98;
        v.note(why);
    }

    private static int levelFor(int score) {
        if (score >= 85) return BLOCK;
        if (score >= 55) return WARN;
        if (score >= 26) return NOTICE;
        return CLEAR;
    }

    // ------------------------------------------------------------------
    // 域名工具
    // ------------------------------------------------------------------

    /** 取 scheme://host，容忍空格、中文、缺 scheme 的输入。 */
    public static String hostFromUrl(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.length() == 0) return null;
        if (s.startsWith("about:") || s.startsWith("data:") || s.startsWith("javascript:")
                || s.startsWith("blob:") || s.startsWith("file:")) {
            return null;
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
        String h = null;
        try {
            h = new URI(s.replace(" ", "%20")).getHost();
        } catch (Throwable ignored) {
        }
        if (h == null) {
            // java.net.URI 不接受未编码的非 ASCII 域名，这里退回手工拆解——
            // 恰恰是「西里尔字母伪装」这类地址最需要检查。
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^https?://([^/?#]+)").matcher(s);
            if (m.find()) {
                String auth = m.group(1);
                int at = auth.lastIndexOf('@');
                if (at >= 0) auth = auth.substring(at + 1);
                if (auth.startsWith("[")) {
                    int rb = auth.indexOf(']');
                    h = rb > 0 ? auth.substring(0, rb + 1) : auth;
                } else {
                    int colon = auth.lastIndexOf(':');
                    h = colon > 0 ? auth.substring(0, colon) : auth;
                }
            }
        }
        if (h == null) return null;
        h = h.toLowerCase(Locale.US);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.length() < 3 || h.indexOf('.') < 0 || h.indexOf(' ') >= 0) return null;
        return h;
    }

    /** 内网地址（路由器 / NAS / 打印机），单独对待，别当成恶意 IP。 */
    public static boolean isPrivateIp(String host) {
        if (host == null || !isIp(host)) return false;
        String[] p = host.split("\\.");
        int a, b;
        try {
            a = Integer.parseInt(p[0]);
            b = Integer.parseInt(p[1]);
        } catch (Throwable t) {
            return false;
        }
        if (a == 10 || a == 127) return true;
        if (a == 192 && b == 168) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 169 && b == 254) return true;
        if (a == 0) return true;
        return false;
    }

    public static int portOf(String url) {
        try {
            return new URI(url.replace(" ", "%20")).getPort();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public static boolean isIp(String host) {
        return host != null && host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    /** 二级后缀表：够覆盖国内与常见海外站点，不引入完整 PSL。 */
    private static final String[] MULTI_SUFFIX = {
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
            "com.hk", "com.tw", "com.mo", "co.uk", "org.uk", "ac.uk",
            "co.jp", "ne.jp", "or.jp", "co.kr", "or.kr", "com.au", "net.au",
            "co.nz", "com.sg", "com.my", "co.id", "com.ph", "com.vn",
            "co.th", "com.br", "com.mx", "com.ar", "co.in", "co.za",
            "com.tr", "com.ru", "com.ua", "com.pl", "com.es", "com.it"
    };

    /** 注册域名（eTLD+1 的近似）。用于「同不同站」的判断与缓存键。 */
    public static String registrableDomain(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.US);
        if (isIp(h)) return h;
        for (String suf : MULTI_SUFFIX) {
            if (h.endsWith("." + suf) || h.equals(suf)) {
                String rest = h.substring(0, Math.max(0, h.length() - suf.length() - 1));
                int dot = rest.lastIndexOf('.');
                String first = dot >= 0 ? rest.substring(dot + 1) : rest;
                return first.length() == 0 ? h : first + "." + suf;
            }
        }
        String[] parts = h.split("\\.");
        if (parts.length <= 2) return h;
        String last = parts[parts.length - 1];
        String prev = parts[parts.length - 2];
        if (last.length() == 2 && isGenericLabel(prev)) {
            return prev + "." + last;
        }
        return parts[parts.length - 2] + "." + last;
    }

    private static boolean isGenericLabel(String s) {
        return "com".equals(s) || "net".equals(s) || "org".equals(s) || "gov".equals(s)
                || "edu".equals(s) || "co".equals(s) || "ac".equals(s) || "or".equals(s)
                || "ne".equals(s) || "go".equals(s) || "nic".equals(s);
    }

    private static String tldOf(String host) {
        int dot = host.lastIndexOf('.');
        return dot < 0 ? host : host.substring(dot);
    }

    private static boolean endsWithAny(String host, String[] suffixes) {
        for (String s : suffixes) {
            if (host.endsWith(s)) return true;
        }
        return false;
    }

    private static boolean containsAny(String host, String[] words) {
        for (String w : words) {
            if (host.contains(w)) return true;
        }
        return false;
    }

    public static String schemeOf(String url) {
        int i = url == null ? -1 : url.indexOf("://");
        return i > 0 ? url.substring(0, i).toLowerCase(Locale.US) : "";
    }

    /** 路径里是否出现登录 / 验证类词。只看路径，不看参数值。 */
    private static boolean hasLurePath(String lowUrl) {
        return lowUrl.matches(".*/(login|signin|sign_in|verify|account|unlock|secure|update|password|auth)([/?#.].*)?");
    }

    private static int hyphens(String host) {
        int n = 0;
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) == '-') n++;
        }
        return n;
    }

    /** 西里尔 / 希腊字母 → 拉丁近似字形。 */
    private static String normalizeConfusables(String host) {
        StringBuilder b = new StringBuilder(host.length());
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            char r = c;
            switch (c) {
                case '\u0430': r = 'a'; break;   // а
                case '\u0435': r = 'e'; break;   // е
                case '\u043e': r = 'o'; break;   // о
                case '\u0440': r = 'p'; break;   // р
                case '\u0441': r = 'c'; break;   // с
                case '\u0443': r = 'y'; break;   // у
                case '\u0445': r = 'x'; break;   // х
                case '\u0456': r = 'i'; break;   // і
                case '\u0455': r = 's'; break;   // ѕ
                case '\u04bb': r = 'h'; break;   // һ
                case '\u0501': r = 'd'; break;   // ԁ
                case '\u03b1': r = 'a'; break;   // α
                case '\u03b5': r = 'e'; break;   // ε
                case '\u03b9': r = 'i'; break;   // ι
                case '\u03ba': r = 'k'; break;   // κ
                case '\u03bd': r = 'v'; break;   // ν
                case '\u03bf': r = 'o'; break;   // ο
                case '\u03c1': r = 'p'; break;   // ρ
                case '\u03c4': r = 't'; break;   // τ
                case '\u03c5': r = 'u'; break;   // υ
                case '\u03c7': r = 'x'; break;   // χ
                default: break;
            }
            b.append(r);
        }
        return b.toString();
    }

    /** 官方域名判断：相同或为其子域。 */
    private static String officialFor(String host) {
        for (String[] b : BRANDS) {
            if (host.equals(b[1]) || host.endsWith("." + b[1])) return b[1];
        }
        return null;
    }

    /** 域名里出现了哪个品牌词（不是官方域名时才有意义）。 */
    private static String brandIn(String host) {
        String[] labels = host.split("\\.");
        for (String[] b : BRANDS) {
            String token = b[0];
            if (host.equals(b[1]) || host.endsWith("." + b[1])) continue;
            for (String label : labels) {
                String[] parts = label.split("-");
                for (String p : parts) {
                    if (token.length() <= 3 ? p.equals(token) : p.contains(token)) {
                        return token;
                    }
                }
            }
        }
        return null;
    }

    /** 一字之差 / 数字替字母的仿冒。返回被仿冒的品牌词。 */
    private static String typoOf(String host) {
        String[] labels = host.split("\\.");
        for (String[] b : BRANDS) {
            String token = b[0];
            if (token.length() < 4) continue;
            if (host.equals(b[1]) || host.endsWith("." + b[1])) continue;
            for (String label : labels) {
                String[] parts = label.split("-");
                for (String p : parts) {
                    if (p.length() < 4) continue;
                    if (Math.abs(p.length() - token.length()) > 2) continue;
                    if (p.equals(token)) continue;
                    int limit = token.length() >= 7 ? 2 : 1;
                    if (distance(p, token) <= limit) return token;
                }
            }
        }
        return null;
    }

    /** 编辑距离，只算替换 / 增删。字符串很短，直接 DP。 */
    public static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    // ------------------------------------------------------------------
    // 白名单 / 首页
    // ------------------------------------------------------------------

    public static boolean isTrusted(String host, String whitelist, String home) {
        if (host == null || host.length() == 0) return false;
        String homeHost = hostFromUrl(home);
        if (homeHost != null && (host.equals(homeHost) || host.endsWith("." + homeHost))) {
            return true;
        }
        if (whitelist == null) return false;
        for (String raw : whitelist.split("[,\\s]+")) {
            String w = raw.trim().toLowerCase(Locale.US);
            if (w.length() < 3) continue;
            if (host.equals(w) || host.endsWith("." + w)) return true;
        }
        return false;
    }

    public static boolean isNeverBlock(String host) {
        if (host == null) return false;
        for (String s : NEVER_BLOCK) {
            if (host.equals(s) || host.endsWith("." + s)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 情报库：64 位指纹 + 排序数组
    // ------------------------------------------------------------------

    /** FNV-1a 64。只存指纹不存域名，一条 8 字节，十几万条也就 1MB 多。 */
    public static long hashOf(String host) {
        long h = 0xcbf29ce484222325L;
        byte[] bs = host.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        for (int i = 0; i < bs.length; i++) {
            h ^= (bs[i] & 0xffL);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** 一份本地情报库。 */
    public static final class Store {
        public final String name;
        private long[] hashes = new long[0];
        private final String path;

        Store(String name, String path) {
            this.name = name;
            this.path = path;
        }

        public int size() {
            return hashes.length;
        }

        public boolean empty() {
            return hashes.length == 0;
        }

        /** 自身及其各级父域都查一遍，这样 a.b.example.com 也能命中 example.com。 */
        public boolean contains(String host) {
            if (host == null || hashes.length == 0) return false;
            String h = host.toLowerCase(Locale.US);
            while (true) {
                if (Arrays.binarySearch(hashes, hashOf(h)) >= 0) return true;
                int dot = h.indexOf('.');
                if (dot < 0) break;
                h = h.substring(dot + 1);
                if (h.indexOf('.') < 0) break;
            }
            return false;
        }

        public long[] raw() {
            return hashes;
        }
    }

    /** 读库。文件不存在 / 损坏就返回空库，不抛异常。 */
    public static Store load(File f, String name) {
        Store s = new Store(name, f == null ? "" : f.getAbsolutePath());
        if (f == null || !f.isFile() || f.length() < 8) return s;
        DataInputStream in = null;
        try {
            in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16));
            int n = in.readInt();
            if (n <= 0 || n > 800000) return s;
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = in.readLong();
            s.hashes = a;
        } catch (Throwable ignored) {
            s.hashes = new long[0];
        } finally {
            close(in);
        }
        return s;
    }

    /** 原子写库：先写 .tmp 再改名，写到一半被杀掉不会留下半个文件。 */
    public static boolean save(File f, long[] hashes) {
        if (f == null || hashes == null || hashes.length == 0) return false;
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
            out.writeInt(hashes.length);
            for (int i = 0; i < hashes.length; i++) out.writeLong(hashes[i]);
            out.flush();
            out.close();
            out = null;
            if (f.exists() && !f.delete()) return false;
            return tmp.renameTo(f);
        } catch (Throwable t) {
            return false;
        } finally {
            close(out);
        }
    }

    public static long[] toSortedArray(Collection<Long> set) {
        if (set == null || set.isEmpty()) return new long[0];
        long[] a = new long[set.size()];
        int i = 0;
        for (Long v : set) a[i++] = v;
        Arrays.sort(a);
        int n = 0;
        for (int j = 0; j < a.length; j++) {
            if (j == 0 || a[j] != a[j - 1]) a[n++] = a[j];
        }
        return n == a.length ? a : Arrays.copyOf(a, n);
    }

    // ------------------------------------------------------------------
    // 情报源解析
    // ------------------------------------------------------------------

    /** 认 hosts 格式、纯域名、整条 URL 三种写法。 */
    public static String hostFromLine(String line) {
        if (line == null) return null;
        String s = line;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        s = s.trim();
        if (s.length() == 0) return null;

        int sp = s.indexOf(' ');
        if (sp > 0) {
            String first = s.substring(0, sp);
            if (isIp(first) || "0.0.0.0".equals(first) || "::".equals(first)) {
                s = s.substring(sp + 1).trim();
                int sp2 = s.indexOf(' ');
                if (sp2 > 0) s = s.substring(0, sp2);
            }
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            String h = hostFromUrl(s);
            return checkImportedHost(h) ? h : null;
        }
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon > 0 && s.indexOf('.') > 0) s = s.substring(0, colon);
        s = s.toLowerCase(Locale.US);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return checkImportedHost(s) ? s : null;
    }

    private static boolean checkImportedHost(String h) {
        if (h == null || h.length() < 4 || h.length() > 253) return false;
        if (h.indexOf('.') < 0) return false;
        if ("localhost".equals(h) || "localhost.localdomain".equals(h)) return false;
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".arpa")) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return !isNeverBlock(h);
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 页面侧采集脚本
    // ------------------------------------------------------------------

    /**
     * 在页面里跑一遍，把「结构特征」报给原生层。
     *
     * <p>刻意只看结构与文案，不碰用户输入的内容：不读表单值、不读 Cookie、
     * 不上报链接列表。脚本最多跑一次（同一 URL），结果只留在本机。</p>
     */
    public static final String PAGE_JS = "(function(){"
            + "try{if(window.__hdRiskUrl===location.href&&!window.__hdForce)return;"
            + "window.__hdRiskUrl=location.href;window.__hdForce=0;"
            + "var o={u:location.href,t:(document.title||'').slice(0,120),pw:0,cf:0,hi:0,ifr:0,"
            + "mr:0,ob:0,lure:0,icp:0,icpNo:'',mix:0,zh:0,blk:0};"
            + "try{o.pw=document.querySelectorAll('input[type=password]').length;}catch(e){}"
            + "try{var fs=document.querySelectorAll('form');"
            + "for(var i=0;i<fs.length&&i<40;i++){var a=fs[i].getAttribute('action');"
            + "if(!a)continue;try{var uu=new URL(a,location.href);"
            + "if(uu.host&&uu.host!==location.host)o.cf++;}catch(e){}}}catch(e){}"
            + "try{var ifs=document.querySelectorAll('iframe');o.ifr=ifs.length;"
            + "for(var j=0;j<ifs.length&&j<30;j++){var f=ifs[j];"
            + "var w=f.offsetWidth,h2=f.offsetHeight,st=f.getAttribute('style')||'';"
            + "if(w<=2||h2<=2||/display\\s*:\\s*none|visibility\\s*:\\s*hidden/i.test(st))o.hi++;}}catch(e){}"
            + "try{var m=document.querySelector('meta[http-equiv=refresh]');"
            + "if(m){var c=(m.getAttribute('content')||''),mm=c.match(/url\\s*=\\s*([^;]+)/i);"
            + "if(mm){try{var tu=new URL(mm[1].replace(/[\"']/g,''),location.href);"
            + "if(tu.host&&tu.host!==location.host)o.mr=1;}catch(e){}}}}catch(e){}"
            + "try{var html=(document.documentElement.innerHTML||'').slice(0,300000);"
            + "var ev=(html.match(/eval\\s*\\(/g)||[]).length,"
            + "un=(html.match(/unescape\\s*\\(/g)||[]).length,"
            + "at=(html.match(/atob\\s*\\(/g)||[]).length,"
            + "dw=(html.match(/document\\.write\\s*\\(/g)||[]).length;"
            + "if(ev+un>2||(at>3&&dw>2))o.ob=1;"
            + "if(location.protocol==='https:'&&/src\\s*=\\s*[\"']http:\\/\\//i.test(html))o.mix=1;"
            + "}catch(e){}"
            + "try{var tx=(document.body?document.body.innerText:'').slice(0,15000);"
            + "if(/立即下载|马上下载|下载安装|安装包|免费领取|手机已中|已中病毒|清理垃圾|一键清理|恭喜您|领取奖励/.test(tx))o.lure=1;"
            + "var ic=tx.match(/([\\u4e00-\\u9fff]?ICP[备证]\\s*\\d{4,})/);"
            + "if(ic){o.icp=1;o.icpNo=ic[1];}"
            + "}catch(e){}"
            + "try{o.zh=/^zh/i.test(document.documentElement.lang||navigator.language||'');}catch(e){}"
            + "try{var b=document.body;if(b&&(b.getAttribute('oncontextmenu')||document.oncontextmenu))o.blk=1;}catch(e){}"
            + "try{HuandouNative.onPageRisk(JSON.stringify(o));}catch(e){}"
            + "}catch(e){}})();";

    /** 把 JS 报上来的 JSON 换成强类型对象；解析失败给 null。 */
    public static PageSignals parseSignals(org.json.JSONObject o) {
        if (o == null) return null;
        PageSignals p = new PageSignals();
        p.passwords = o.optInt("pw", 0);
        p.crossForms = o.optInt("cf", 0);
        p.hiddenIframes = o.optInt("hi", 0);
        p.iframes = o.optInt("ifr", 0);
        p.metaRefresh = o.optInt("mr", 0) == 1;
        p.obfuscated = o.optInt("ob", 0) == 1;
        p.downloadLure = o.optInt("lure", 0) == 1;
        p.icp = o.optInt("icp", 0) == 1;
        p.icpNo = o.optString("icpNo", "");
        p.mixedContent = o.optInt("mix", 0) == 1;
        p.zh = o.optInt("zh", 0) == 1;
        p.blockCopy = o.optInt("blk", 0) == 1;
        return p;
    }

    /** 给「详情」弹窗用的分段文案。 */
    public static String describe(Verdict v) {
        StringBuilder b = new StringBuilder();
        if (v.host.length() > 0) {
            b.append("域名：").append(v.host).append('\n');
        }
        b.append("风险值：").append(v.score).append("/100");
        if (v.feed.length() > 0) b.append("（命中").append(v.feed).append("）");
        b.append("\n\n");
        List<String> rs = v.reasons;
        for (int i = 0; i < rs.size(); i++) {
            b.append("· ").append(rs.get(i)).append('\n');
        }
        if (b.length() > 0 && b.charAt(b.length() - 1) == '\n') {
            b.setLength(b.length() - 1);
        }
        return b.toString();
    }
}
