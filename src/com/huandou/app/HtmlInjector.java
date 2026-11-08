package com.huandou.app;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Injects the fingerprint script into the main HTML document before any site script runs.
 * Returns null on any failure so WebView falls back to its own normal loading path.
 */
public final class HtmlInjector {

    private static final Pattern HEAD = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML = Pattern.compile("<html[^>]*>", Pattern.CASE_INSENSITIVE);

    private HtmlInjector() {
    }

    public static WebResourceResponse inject(WebResourceRequest req, String script) {
        return injectBoth(req, script, null);
    }

    /** 指纹脚本与用户脚本要共用同一次 HTML 改写，否则只能注入其中一份。 */
    public static WebResourceResponse injectBoth(WebResourceRequest req, String a, String b) {
        boolean hasA = a != null && a.length() > 0;
        boolean hasB = b != null && b.length() > 0;
        if (!hasA && !hasB) return null;
        String url = req.getUrl().toString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
        if (!"GET".equalsIgnoreCase(req.getMethod())) return null;

        Map<String, String> hs = req.getRequestHeaders();
        if (hs == null) return null;
        String accept = hs.get("Accept");
        if (accept == null || !accept.toLowerCase(Locale.US).contains("text/html")) return null;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> e : hs.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                String lk = k.toLowerCase(Locale.US);
                if (lk.equals("accept-encoding") || lk.equals("host") || lk.equals("connection")
                        || lk.equals("cookie") || lk.equals("if-none-match")
                        || lk.equals("if-modified-since") || lk.equals("content-length")
                        || lk.equals("range") || lk.equals("upgrade-insecure-requests")) continue;
                conn.setRequestProperty(k, e.getValue());
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.length() > 0) conn.setRequestProperty("Cookie", cookie);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String ctype = conn.getContentType();
            if (ctype == null || !ctype.toLowerCase(Locale.US).contains("text/html")) return null;

            Map<String, List<String>> rh = conn.getHeaderFields();
            if (rh != null) {
                for (Map.Entry<String, List<String>> e : rh.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")
                            && e.getValue() != null) {
                        for (String c : e.getValue()) {
                            try {
                                CookieManager.getInstance().setCookie(url, c);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            InputStream in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.length() > 0) {
                String e = enc.toLowerCase(Locale.US);
                if (e.contains("gzip")) {
                    in = new GZIPInputStream(in);
                } else if (!e.contains("identity")) {
                    // br / deflate 之类解不了。硬塞回去就是一堆乱码 + 白屏，
                    // 宁可放弃改写，让 WebView 自己正常加载。
                    return null;
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            try {
                in.close();
            } catch (Throwable ignored) {
            }

            String html = new String(bos.toByteArray(), charsetOf(ctype));
            StringBuilder sn = new StringBuilder();
            if (hasA) sn.append(FpScript.wrappedForHtml(a));
            if (hasB) sn.append(FpScript.wrappedForHtml(b));
            String out = insert(html, sn.toString());
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("Content-Type", "text/html; charset=utf-8");
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                    new ByteArrayInputStream(out.getBytes("UTF-8")));
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String charsetOf(String ctype) {
        String lower = ctype.toLowerCase(Locale.US);
        int i = lower.indexOf("charset=");
        if (i < 0) return "UTF-8";
        String cs = ctype.substring(i + 8).trim().replace("\"", "").replace("'", "");
        int semi = cs.indexOf(';');
        if (semi > 0) cs = cs.substring(0, semi).trim();
        return cs.length() > 0 ? cs : "UTF-8";
    }

    private static String insert(String html, String script) {
        Matcher m = HEAD.matcher(html);
        if (m.find()) {
            return html.substring(0, m.end()) + script + html.substring(m.end());
        }
        Matcher m2 = HTML.matcher(html);
        if (m2.find()) {
            return html.substring(0, m2.end()) + "<head>" + script + "</head>"
                    + html.substring(m2.end());
        }
        return script + html;
    }
}
