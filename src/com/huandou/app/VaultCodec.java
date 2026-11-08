package com.huandou.app;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java codec for per-identity site-state snapshots.
 *
 * <p>No Android and no org.json dependency, so the round trip, the cookie
 * splitter and the restore script can all be unit-tested on a plain JVM.
 *
 * <p>Wire format is line based with UTF-8 URL-encoded fields, so arbitrary
 * cookie values and localStorage payloads survive without escaping bugs:
 * <pre>
 *   V1
 *   T&lt;timestamp&gt;
 *   C&lt;host&gt; \t &lt;raw Cookie header&gt;
 *   L&lt;key&gt;  \t &lt;localStorage value&gt;
 *   S&lt;key&gt;  \t &lt;sessionStorage value&gt;
 * </pre>
 */
public final class VaultCodec {

    /** Cap on a single stored value, and on the whole snapshot. */
    public static final int MAX_VALUE = 64 * 1024;
    public static final int MAX_TOTAL = 480 * 1024;

    public static final class Snap {
        public long time;
        public final LinkedHashMap<String, String> cookies = new LinkedHashMap<String, String>();
        public final LinkedHashMap<String, String> ls = new LinkedHashMap<String, String>();
        public final LinkedHashMap<String, String> ss = new LinkedHashMap<String, String>();

        public boolean isEmpty() {
            return cookies.isEmpty() && ls.isEmpty() && ss.isEmpty();
        }
    }

    private VaultCodec() {
    }

    public static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String dec(String s) {
        if (s == null || s.length() == 0) return "";
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    private static String clip(String v) {
        if (v == null) return "";
        return v.length() > MAX_VALUE ? v.substring(0, MAX_VALUE) : v;
    }

    public static String encode(Snap s) {
        StringBuilder b = new StringBuilder("V1\n");
        b.append('T').append(s.time).append('\n');
        for (Map.Entry<String, String> e : s.cookies.entrySet()) {
            if (b.length() > MAX_TOTAL) break;
            b.append('C').append(enc(e.getKey())).append('\t')
                    .append(enc(clip(e.getValue()))).append('\n');
        }
        for (Map.Entry<String, String> e : s.ls.entrySet()) {
            if (b.length() > MAX_TOTAL) break;
            b.append('L').append(enc(e.getKey())).append('\t')
                    .append(enc(clip(e.getValue()))).append('\n');
        }
        for (Map.Entry<String, String> e : s.ss.entrySet()) {
            if (b.length() > MAX_TOTAL) break;
            b.append('S').append(enc(e.getKey())).append('\t')
                    .append(enc(clip(e.getValue()))).append('\n');
        }
        return b.toString();
    }

    public static Snap decode(String text) {
        Snap s = new Snap();
        if (text == null || !text.startsWith("V1\n")) return s;
        String[] lines = text.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.length() < 2) continue;
            char tag = ln.charAt(0);
            String body = ln.substring(1);
            int t = body.indexOf('\t');
            String a = t < 0 ? body : body.substring(0, t);
            String v = t < 0 ? "" : body.substring(t + 1);
            if (tag == 'T') {
                try {
                    s.time = Long.parseLong(body);
                } catch (Throwable ignored) {
                }
            } else if (tag == 'C') {
                s.cookies.put(dec(a), dec(v));
            } else if (tag == 'L') {
                s.ls.put(dec(a), dec(v));
            } else if (tag == 'S') {
                s.ss.put(dec(a), dec(v));
            }
        }
        return s;
    }

    private static final String[] NON_COOKIE = {
            "path", "domain", "expires", "max-age", "secure", "httponly",
            "samesite", "priority", "partitioned"
    };

    /**
     * Split a CookieManager.getCookie() header into individual name=value pairs.
     * Semicolons inside a quoted value are preserved; Set-Cookie attribute
     * keywords are dropped in case a raw header ever reaches us.
     */
    public static List<String> splitCookies(String raw) {
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.length() == 0) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == ';' && !quoted) {
                addCookie(out, cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        addCookie(out, cur.toString());
        return out;
    }

    private static void addCookie(List<String> out, String part) {
        String p = part.trim();
        if (p.length() == 0) return;
        int eq = p.indexOf('=');
        if (eq <= 0) return;
        String name = p.substring(0, eq).trim().toLowerCase(Locale.US);
        for (String k : NON_COOKIE) {
            if (name.equals(k)) return;
        }
        out.add(p);
    }

    /** Unescape the JSON string literal returned by WebView.evaluateJavascript. */
    public static String fromEvalResult(String r) {
        if (r == null) return "";
        String s = r.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        if (s.equals("null")) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n':
                    b.append('\n');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignored) {
                            b.append('u');
                        }
                    } else {
                        b.append('u');
                    }
                    break;
                default:
                    b.append(n);
                    break;
            }
        }
        return b.toString();
    }

    /**
     * Apply a DUMP_STORAGE_JS result to a snapshot (replaces ls / ss,
     * keeps cookies and timestamp which are filled in natively).
     */
    public static boolean applyDump(Snap s, String evalResult) {
        String text = fromEvalResult(evalResult);
        if (!text.startsWith("V1")) return false;
        s.ls.clear();
        s.ss.clear();
        String[] lines = text.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.length() < 2) continue;
            int t = ln.indexOf('\t');
            String a = t < 0 ? ln.substring(1) : ln.substring(1, t);
            String v = t < 0 ? "" : ln.substring(t + 1);
            if (ln.charAt(0) == 'L') s.ls.put(dec(a), dec(v));
            else if (ln.charAt(0) == 'S') s.ss.put(dec(a), dec(v));
        }
        return true;
    }

    private static String jsQuote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    private static String objectOf(LinkedHashMap<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(jsQuote(e.getKey())).append(':').append(jsQuote(e.getValue()));
        }
        return b.append('}').toString();
    }

    /**
     * Build a script that clears both Web Storage areas and writes the saved
     * values back. It must run before the site's own scripts, so it is injected
     * in onPageStarted (with an onPageFinished retry as a safety net).
     */
    public static String restoreScript(Snap s) {
        String L = s == null ? "{}" : objectOf(s.ls);
        String S = s == null ? "{}" : objectOf(s.ss);
        return "(function(){try{"
                + "var L=" + L + ",S=" + S + ",k;"
                + "try{localStorage.clear();}catch(e){}"
                + "for(k in L){if(Object.prototype.hasOwnProperty.call(L,k)){"
                + "try{localStorage.setItem(k,String(L[k]));}catch(e){}}}"
                + "try{sessionStorage.clear();}catch(e){}"
                + "for(k in S){if(Object.prototype.hasOwnProperty.call(S,k)){"
                + "try{sessionStorage.setItem(k,String(S[k]));}catch(e){}}}"
                + "window.__hdVaultRestored=true;return '1';"
                + "}catch(e){window.__hdVaultRestored=false;return '0';}})();";
    }

    public static int cookieCount(Snap s) {
        if (s == null) return 0;
        int n = 0;
        for (String v : s.cookies.values()) n += splitCookies(v).size();
        return n;
    }

    public static int keyCount(Snap s) {
        if (s == null) return 0;
        return s.ls.size() + s.ss.size();
    }

    public static String brief(Snap s) {
        int c = cookieCount(s);
        int k = keyCount(s);
        if (c == 0 && k == 0) return "空存档";
        return c + " 条 Cookie · " + k + " 个键";
    }
}
