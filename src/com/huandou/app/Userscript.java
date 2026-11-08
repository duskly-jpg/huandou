package com.huandou.app;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个油猴脚本（.user.js）。
 *
 * <p>这里只做三件事：解析 ==UserScript== 元数据、判断某个网址是否命中、
 * 序列化给存储层。注入和 GM API 在 {@link UserscriptEngine}。
 *
 * <p>匹配语义对齐 Tampermonkey：{@code @match} 按 Chrome 扩展的 match pattern
 * 解释（{@code *.a.com} 含裸域），{@code @include} 用通配或 {@code /正则/}，
 * {@code @exclude} 优先级最高。
 */
public final class Userscript {

    public static final String RUN_START = "document-start";
    public static final String RUN_END = "document-end";
    public static final String RUN_IDLE = "document-idle";

    private static final Pattern META =
            Pattern.compile("^\\s*//\\s*@([A-Za-z0-9_:.-]+)\\s*(.*?)\\s*$");
    private static final Pattern HEAD_OPEN = Pattern.compile("==UserScript==");
    private static final Pattern HEAD_CLOSE = Pattern.compile("==/UserScript==");

    public String id = "";
    public String name = "";
    public String namespace = "";
    public String version = "";
    public String description = "";
    public String icon = "";
    public String sourceUrl = "";
    public String runAt = RUN_END;
    public boolean enabled = true;
    public long installedAt = 0L;
    public int bytes = 0;

    public final ArrayList<String> matches = new ArrayList<String>();
    public final ArrayList<String> includes = new ArrayList<String>();
    public final ArrayList<String> excludes = new ArrayList<String>();
    public final ArrayList<String> requires = new ArrayList<String>();
    public final ArrayList<String> grants = new ArrayList<String>();

    /** 一条匹配规则：正则型的 /…/ 用 find（包含即命中），通配型用全串匹配。 */
    private static final class Rule {
        final Pattern p;
        final boolean find;

        Rule(Pattern p, boolean find) {
            this.p = p;
            this.find = find;
        }
    }

    private Rule[] mCache;
    private Rule[] iCache;
    private Rule[] eCache;

    /** 从脚本文本解析元数据。解析失败返回 null —— 调用方据此拒绝安装。 */
    public static Userscript parse(String code, String sourceUrl) {
        if (code == null) return null;
        Userscript u = new Userscript();
        u.sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
        u.bytes = code.length();
        u.installedAt = System.currentTimeMillis();

        Matcher mo = HEAD_OPEN.matcher(code);
        Matcher mc = HEAD_CLOSE.matcher(code);
        String head = null;
        if (mo.find() && mc.find(mo.end())) {
            head = code.substring(mo.end(), mc.start());
        }
        if (head != null) {
            String[] lines = head.split("\n");
            for (int i = 0; i < lines.length && i < 400; i++) {
                Matcher m = META.matcher(lines[i]);
                if (!m.find()) continue;
                String key = m.group(1).toLowerCase(Locale.US);
                String val = m.group(2);
                if (val.length() == 0) continue;
                if (key.equals("name")) {
                    if (u.name.length() == 0) u.name = val;
                } else if (key.startsWith("name:")) {
                    // @name:zh-CN 之类，主名称为空时兜底。
                    if (u.name.length() == 0) u.name = val;
                } else if (key.equals("namespace")) {
                    u.namespace = val;
                } else if (key.equals("version")) {
                    u.version = val;
                } else if (key.equals("description") || key.startsWith("description:")) {
                    if (u.description.length() == 0) u.description = val;
                } else if (key.equals("icon")) {
                    u.icon = val;
                } else if (key.equals("match")) {
                    u.matches.add(val);
                } else if (key.equals("include")) {
                    u.includes.add(val);
                } else if (key.equals("exclude")) {
                    u.excludes.add(val);
                } else if (key.equals("require")) {
                    if (u.requires.size() < 8) u.requires.add(val);
                } else if (key.equals("grant")) {
                    String g = val.trim();
                    if (g.length() > 0 && !u.grants.contains(g)) u.grants.add(g);
                } else if (key.equals("run-at")) {
                    String r = val.trim().toLowerCase(Locale.US);
                    if (r.equals("document-start")) u.runAt = RUN_START;
                    else if (r.equals("document-idle")) u.runAt = RUN_IDLE;
                    else u.runAt = RUN_END;
                } else if (key.equals("downloadurl") || key.equals("updateurl")) {
                    if (u.sourceUrl.length() == 0) u.sourceUrl = val;
                }
            }
        }

        if (u.name.length() == 0) {
            // 没有 @name 的多半不是油猴脚本，但也可能是不规范的——用首行凑一个。
            String first = code.trim();
            int nl = first.indexOf('\n');
            if (nl > 0) first = first.substring(0, nl);
            if (first.length() > 60) first = first.substring(0, 60);
            if (first.trim().length() == 0) return null;
            u.name = "未命名脚本";
        }
        // 一条规则都没有的脚本永远不会运行，装着只会让人困惑。
        if (u.matches.isEmpty() && u.includes.isEmpty() && head == null) return null;
        return u;
    }

    public String displayName() {
        return name.length() == 0 ? id : name;
    }

    /** 元数据里的规则条数，用于列表上的副标题。 */
    public int ruleCount() {
        return matches.size() + includes.size();
    }

    public boolean appliesTo(String url) {
        if (url == null || !enabled) return false;
        String u = stripFragment(url);
        String low = u.toLowerCase(Locale.US);
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false;
        Rule[] ex = excludes();
        if (ex.length > 0 && hit(ex, u)) return false;
        if (matches.isEmpty() && includes.isEmpty()) return false;
        if (hit(matches(), u)) return true;
        return hit(includes(), u);
    }

    private static boolean hit(Rule[] rs, String url) {
        for (int i = 0; i < rs.length; i++) {
            if (rs[i] == null) continue;
            try {
                if (rs[i].find ? rs[i].p.matcher(url).find()
                        : rs[i].p.matcher(url).matches()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i > 0 ? url.substring(0, i) : url;
    }

    private Rule[] matches() {
        Rule[] c = mCache;
        if (c == null) {
            c = build(matches, true);
            mCache = c;
        }
        return c;
    }

    private Rule[] includes() {
        Rule[] c = iCache;
        if (c == null) {
            c = build(includes, false);
            iCache = c;
        }
        return c;
    }

    private Rule[] excludes() {
        Rule[] c = eCache;
        if (c == null) {
            c = build(excludes, false);
            eCache = c;
        }
        return c;
    }

    private static Rule[] build(ArrayList<String> rules, boolean matchSyntax) {
        ArrayList<Rule> out = new ArrayList<Rule>();
        for (int i = 0; i < rules.size(); i++) {
            String s = rules.get(i).trim();
            if (s.length() == 0) continue;
            try {
                if (matchSyntax) {
                    String re = matchToRegex(s);
                    if (re != null) out.add(new Rule(Pattern.compile(re), false));
                } else if (s.length() > 2 && s.startsWith("/") && s.endsWith("/")) {
                    // /正则/ 的语义是「地址里出现即命中」，不是整串相等。
                    out.add(new Rule(Pattern.compile(s.substring(1, s.length() - 1)), true));
                } else {
                    out.add(new Rule(Pattern.compile("^" + wild(s) + "$"), false));
                }
            } catch (Throwable ignored) {
            }
        }
        return out.toArray(new Rule[0]);
    }

    /**
     * Chrome 扩展的 match pattern 转正则：
     * {@code *://*.example.com/path*} → {@code ^https?://(?:[^/]+\.)?example\.com/path.*$}
     */
    public static String matchToRegex(String pattern) {
        if (pattern == null) return null;
        String s = pattern.trim();
        int sp = s.indexOf("://");
        if (sp <= 0) return null;
        String scheme = s.substring(0, sp);
        String rest = s.substring(sp + 3);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        if (host.length() == 0) return null;

        StringBuilder b = new StringBuilder(96);
        b.append("^");
        if ("*".equals(scheme)) {
            b.append("https?");
        } else if (scheme.indexOf('*') >= 0) {
            b.append(wild(scheme));
        } else {
            b.append(Pattern.quote(scheme));
        }
        b.append("://");

        // 端口单独拆出来，否则 *.example.com:8080 会被当成主机名的一部分。
        String port = "";
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(']') < colon) {
            port = host.substring(colon);
            host = host.substring(0, colon);
        }
        if ("*".equals(host)) {
            b.append("[^/]+");
        } else if (host.startsWith("*.")) {
            b.append("(?:[^/]+\\.)?").append(wild(host.substring(2)));
        } else {
            b.append(wild(host));
        }
        if (port.length() > 0) b.append(Pattern.quote(port));
        b.append(wild(path));
        b.append("$");
        return b.toString();
    }

    /** @include 支持通配与 /正则/ 两种写法。 */
    public static Pattern includeToPattern(String rule) {
        String t = rule == null ? "" : rule.trim();
        if (t.length() > 2 && t.startsWith("/") && t.endsWith("/")) {
            return Pattern.compile(t.substring(1, t.length() - 1));
        }
        return Pattern.compile("^" + wild(t) + "$");
    }

    /**
     * 从 GreasyFork 这类安装页里把源码抠出来。页面里的代码块是转义过的纯文本，
     * 先剥标签再还原实体。找不到返回 null。
     */
    public static String extractFromHtml(String html) {
        if (html == null) return null;
        int i = html.indexOf("==UserScript==");
        if (i < 0) return null;
        if (html.indexOf("==/UserScript==", i) < 0) return null;

        int pre = html.lastIndexOf("<pre", i);
        int preEnd = html.indexOf("</pre>", i);
        if (pre >= 0 && preEnd > pre) {
            int gt = html.indexOf('>', pre);
            if (gt > 0) {
                String text = unescape(stripTags(html.substring(gt + 1, preEnd)));
                if (text.indexOf("==UserScript==") >= 0) return text;
            }
        }
        return unescape(stripTags(html.substring(i)));
    }

    private static String stripTags(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean tag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                tag = true;
                continue;
            }
            if (c == '>') {
                tag = false;
                continue;
            }
            if (!tag) b.append(c);
        }
        return b.toString();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static String wild(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            int star = s.indexOf('*', i);
            if (star < 0) {
                b.append(Pattern.quote(s.substring(i)));
                break;
            }
            if (star > i) b.append(Pattern.quote(s.substring(i, star)));
            b.append(".*");
            i = star + 1;
        }
        return b.toString();
    }
}
