package com.huandou.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 与界面无关的几件小工具：强制深色、阅读模式取正文、风险网址判断、书签编解码。
 *
 * <p>单独放一个类，是为了让 MainActivity 不再继续膨胀；这些逻辑都能脱离
 * Activity 单独测试。</p>
 */
public final class Extras {

    private Extras() {
    }

    // ------------------------------------------------------------------
    // 强制深色
    // ------------------------------------------------------------------

    /**
     * 整页反色 + 色相旋转 180°，是浏览器「强制深色」最省事的做法：
     * 白底变黑底，彩色图再反回来一次，不会变成负片。
     *
     * <p>用注入 style 而不是 WebSettings.setForceDark，是因为后者只有
     * Android 10+ 且依赖 WebView 的自动算法，很多站点会漏掉内联白底。</p>
     */
    public static final String DARK_JS = "(function(){"
            + "try{var id='hd-dark-css';var s=document.getElementById(id);"
            + "if(!s){s=document.createElement('style');s.id=id;"
            + "(document.head||document.documentElement).appendChild(s);}"
            + "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;"
            + "background:#0d0b12!important}"
            + "img,video,picture,canvas,svg,iframe,embed,object{"
            + "filter:invert(1) hue-rotate(180deg)!important}';}catch(e){}})();";

    /** 关掉深色：删掉注入的样式表。 */
    public static final String UNDARK_JS = "(function(){"
            + "try{var s=document.getElementById('hd-dark-css');"
            + "if(s&&s.parentNode)s.parentNode.removeChild(s);}catch(e){}})();";

    // ------------------------------------------------------------------
    // 阅读模式
    // ------------------------------------------------------------------

    /**
     * 正文提取：先按「文本多、链接少」给容器打分选出主体，再递归收集块级
     * 文本，保留段落结构。比直接 innerText 干净，也比引入 Readability 轻。
     *
     * <p>返回 JSON 字符串，交给 Java 端用 JSONTokener 解两层。</p>
     */
    public static final String READER_JS = "(function(){"
            + "try{"
            + "var best=null,bs=0;"
            + "var cs=document.querySelectorAll('article,main,section,div');"
            + "for(var i=0;i<cs.length&&i<1500;i++){var e=cs[i];"
            + "if(!e.offsetHeight)continue;"
            + "var tx=e.innerText||'';"
            + "if(tx.length<400)continue;"
            + "var links=e.getElementsByTagName('a').length;"
            + "var sc=tx.length-links*45;"
            + "if(sc>bs){bs=sc;best=e;}}"
            + "if(!best)best=document.body;"
            + "var junk=['SCRIPT','STYLE','NAV','HEADER','FOOTER','ASIDE','FORM',"
            + "'IFRAME','NOSCRIPT','BUTTON','SELECT','SVG'];"
            + "function grab(n,out){"
            + "for(var i=0;i<n.children.length;i++){var ch=n.children[i];"
            + "if(junk.indexOf(ch.tagName)>=0)continue;"
            + "if(ch.offsetHeight===0&&ch.innerText&&ch.innerText.length<2)continue;"
            + "var block=false;"
            + "for(var k=0;k<ch.children.length;k++){"
            + "if(/^(DIV|P|SECTION|ARTICLE|LI|H[1-6]|BLOCKQUOTE|PRE)$/"
            + ".test(ch.children[k].tagName)){block=true;break;}}"
            + "if(block){grab(ch,out);}"
            + "else{var s=(ch.innerText||ch.textContent||'').trim();"
            + "if(s.length>0)out.push(s);}}"
            + "if(n.children.length===0){"
            + "var t=(n.innerText||n.textContent||'').trim();"
            + "if(t.length>0)out.push(t);}}"
            + "var parts=[];grab(best,parts);"
            + "var body=parts.join('\\n\\n').replace(/\\n{3,}/g,'\\n\\n').trim();"
            + "if(body.length>250000)body=body.substring(0,250000);"
            + "var h=document.querySelector('h1');"
            + "var title=((h&&h.innerText)||document.title||'').trim();"
            + "return JSON.stringify({ok:body.length>80,title:title,"
            + "text:body,url:location.href});"
            + "}catch(e){return JSON.stringify({ok:false,title:'',text:'',url:''});}"
            + "})();";

    // ------------------------------------------------------------------
    // 风险网址
    // ------------------------------------------------------------------

    private static final String[] BRANDS = {
            "icbc", "ccb", "abchina", "boc", "cmbchina", "alipay", "taobao", "tmall",
            "jd", "wechat", "weixin", "tencent", "unionpay", "95588", "10086", "12306",
            "gov", "apple", "icloud", "microsoft", "google", "paypal"
    };

    private static final String[] BAD_TLD = {
            ".top", ".xyz", ".tk", ".cc", ".buzz", ".cyou", ".icu", ".work",
            ".click", ".shop", ".vip", ".ltd", ".fit", ".rest", ".monster"
    };

    private static final String[] LURE = {
            "verify", "login", "signin", "safe", "secure", "auth", "update",
            "unlock", "account", "service", "support", "help"
    };

    /**
     * 判断一条地址是否值得预警。返回 null 表示没意见；否则返回给用户看的原因。
     *
     * <p>这不是云端黑名单，而是本地启发式规则：命中特征只做提示，不硬拦，
     * 用户仍可继续访问——误伤一个正常站点比漏报更让人恼火。</p>
     */
    public static String riskOf(String url, String blacklist) {
        if (url == null || url.length() == 0) return null;
        String host = null;
        String scheme = null;
        int port = -1;
        try {
            // 用 java.net.URI 而不是 android.net.Uri：这套规则能脱离 Android 单独测。
            java.net.URI u = new java.net.URI(url.replace(" ", "%20"));
            host = u.getHost();
            scheme = u.getScheme();
            port = u.getPort();
        } catch (Throwable ignored) {
        }
        if (host == null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^(https?)://([^/:?#]+)(?::(\\d+))?")
                    .matcher(url);
            if (m.find()) {
                scheme = m.group(1);
                host = m.group(2);
                if (m.group(3) != null) {
                    try {
                        port = Integer.parseInt(m.group(3));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (host == null) return null;
        host = host.toLowerCase(Locale.US);
        String low = url.toLowerCase(Locale.US);

        if (blacklist != null) {
            for (String bad : blacklist.split("[,\\s]+")) {
                String b = bad.trim().toLowerCase(Locale.US);
                if (b.length() > 2 && host.contains(b)) {
                    return "这个域名在你自己的黑名单里（" + b + "）";
                }
            }
        }

        if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            if (port != -1 && port != 80 && port != 443) {
                return "直接用 IP 加非常规端口访问，常见于钓鱼页与木马下载站";
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return "直接用 IP 明文访问，无法确认网站身份";
            }
        }

        boolean brand = false;
        for (String b : BRANDS) {
            if (host.contains(b)) {
                brand = true;
                break;
            }
        }
        if (brand) {
            for (String t : BAD_TLD) {
                if (host.endsWith(t)) {
                    return "域名同时包含知名品牌词和高风险后缀，很像仿冒站点";
                }
            }
            for (String l : LURE) {
                if (host.contains(l)) {
                    return "域名里同时有品牌词和「验证 / 登录」字样，疑似钓鱼";
                }
            }
        }

        if (low.endsWith(".apk") || low.endsWith(".exe") || low.endsWith(".msi")
                || low.contains(".apk?") || low.contains(".exe?")) {
            return "这个链接会下载安装包或可执行文件，请先确认来源";
        }
        if (low.startsWith("http://") && low.contains("password")) {
            return "明文页面里出现 password 参数，账号可能被窃听";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 书签
    // ------------------------------------------------------------------

    /** 书签列表转 JSON。每项是 {title, url}。 */
    public static String encodeBookmarks(List<String[]> items) {
        JSONArray a = new JSONArray();
        try {
            for (String[] it : items) {
                if (it == null || it.length < 2 || it[1] == null) continue;
                JSONObject o = new JSONObject();
                o.put("title", it[0] == null ? "" : it[0]);
                o.put("url", it[1]);
                a.put(o);
            }
        } catch (Throwable ignored) {
        }
        return a.toString();
    }

    /**
     * 解析书签。既认本应用导出的 JSON，也认浏览器导出的
     * Netscape 书签（{@code <A HREF="...">标题</A>}），这样换设备能直接用。
     */
    public static List<String[]> decodeBookmarks(String raw) {
        List<String[]> out = new ArrayList<String[]>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.length() == 0) return out;

        if (s.startsWith("[")) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String u = o.optString("url", "");
                    if (u.length() == 0) continue;
                    out.add(new String[]{o.optString("title", ""), u});
                }
                return out;
            } catch (Throwable ignored) {
            }
        }

        // Netscape Bookmark File：<DT><A HREF="url" ...>标题</A>
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<A[^>]+HREF=\"([^\"]+)\"[^>]*>(.*?)</A>",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(s);
        while (m.find() && out.size() < 2000) {
            String u = m.group(1).trim();
            String t = m.group(2).replaceAll("<[^>]+>", "").trim();
            if (u.startsWith("http") || u.startsWith("ftp")) out.add(new String[]{t, u});
        }
        if (!out.isEmpty()) return out;

        // 纯文本：一行一个地址
        for (String line : s.split("\\r?\\n")) {
            String u = line.trim();
            if (u.startsWith("http://") || u.startsWith("https://")) {
                out.add(new String[]{"", u});
            }
        }
        return out;
    }
}
