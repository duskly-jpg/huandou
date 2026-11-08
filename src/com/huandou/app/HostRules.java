package com.huandou.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 黑名单的规范化与匹配。
 *
 * <p>抽成独立类有两个原因，都不是为了"好看"：</p>
 * <ol>
 *   <li>它是这次改动里唯一有可能把 App 拦到白屏的地方——一条配错的规则就能
 *       让首页打不开。所以它必须能被单独测。</li>
 *   <li>它跑在 {@code shouldInterceptRequest} 里，一次页面就是几十上百次调用，
 *       不能有任何存储或 JSON 解析在里面。</li>
 * </ol>
 *
 * <p>这个类刻意不 import 任何 android.* —— 这样 {@code javac} + {@code java}
 * 就能在普通 JVM 上跑测试，不需要模拟器。</p>
 */
final class HostRules {

    private HostRules() {
    }

    /**
     * 把后端下发的原始列表整理成可用的规则。
     *
     * <p>会被丢掉的：空项、含通配符的、短于 3 字符的、不含点的。
     * 这不是洁癖——后端写一条 {@code "*"} 或 {@code "com"}，整个 App 就打不开了，
     * 而"手滑写错一个字符"的概率远高于"真想这么拦"。</p>
     */
    static String[] normalize(String[] raw) {
        if (raw == null || raw.length == 0) return new String[0];
        List<String> keep = new ArrayList<String>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i] == null ? "" : raw[i].trim().toLowerCase(Locale.US);
            // 容忍后端写成 "https://x.com/path" 或 "x.com:8080" 这种。
            int s1 = s.indexOf("://");
            if (s1 >= 0) s = s.substring(s1 + 3);
            int s2 = s.indexOf('/');
            if (s2 >= 0) s = s.substring(0, s2);
            int s3 = s.indexOf(':');           // 端口
            if (s3 >= 0) s = s.substring(0, s3);
            if (s.startsWith("*.")) s = s.substring(2);
            while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
            if (s.length() < 3) continue;
            if (s.indexOf('*') >= 0 || s.indexOf('?') >= 0) continue;
            if (s.indexOf('.') < 0) continue;
            keep.add(s);
        }
        return keep.toArray(new String[0]);
    }

    /**
     * host 是否命中规则。
     *
     * <p>语义是"完全相同，或者是它的子域"。不用 {@code endsWith(rule)}，
     * 因为那样 {@code notexample.com} 会命中 {@code example.com}——
     * 这是这类实现最常见的一个错。</p>
     */
    static boolean match(String[] rules, String host) {
        if (rules == null || rules.length == 0) return false;
        if (host == null || host.length() == 0) return false;
        String h = host.toLowerCase(Locale.US);
        for (int i = 0; i < rules.length; i++) {
            String r = rules[i];
            if (r == null || r.length() == 0) continue;
            if (h.equals(r)) return true;
            // 子域：长度差至少 2（一个点 + 至少一个字符），且点前是分隔符。
            if (h.length() > r.length() + 1 && h.endsWith(r)
                    && h.charAt(h.length() - r.length() - 1) == '.') {
                return true;
            }
        }
        return false;
    }
}
