package com.huandou.app;

import android.content.Context;

import java.util.ArrayList;

/**
 * 把用户脚本编译成可注入的 JS。
 *
 * <p>脚本跑在页面上下文里（和 Tampermonkey 的「无沙箱」模式一样），
 * GM_* 通过 WebView 的 JavascriptInterface 桥接到原生，这样 GM_xmlhttpRequest
 * 能绕开页面的同源策略，GM_setValue 也不受站点清缓存影响。
 *
 * <p>每段脚本都包在独立 IIFE 里，一个脚本报错不会带崩后面的脚本。
 */
public final class UserscriptEngine {

    private UserscriptEngine() {
    }

    /** 某个网址、某个阶段要注入的脚本；没有匹配返回 null。 */
    public static String snippet(Context c, String url, String runAt) {
        if (url == null) return null;
        UserscriptStore store = UserscriptStore.get(c);
        ArrayList<Userscript> list = store.forUrl(url, runAt);
        if (list.size() == 0) return null;
        StringBuilder b = new StringBuilder(8192);
        b.append(CORE);
        // 同一阶段只跑一次：document-start 的脚本可能先由 HTML 改写注入，
        // onPageFinished 的兜底那一遍必须能被拦下，否则同一脚本跑两遍。
        String guard = "__hdUsDone" + runAt.replace("-", "");
        b.append(";(function(){if(window.").append(guard).append(")return;")
                .append("window.").append(guard).append("=1;\n");
        int total = 0;
        for (int i = 0; i < list.size(); i++) {
            Userscript u = list.get(i);
            String code = store.readCode(u);
            if (code == null || code.length() == 0) continue;
            String req = store.readRequires(u);
            // 一次 evaluateJavascript 塞太多内容会被 WebView 截断，宁可少跑一个脚本。
            int size = code.length() + (req == null ? 0 : req.length());
            if (total + size > 1536 * 1024) {
                b.append(";try{console.warn('[幻豆油猴] ").append(q(u.displayName()))
                        .append(" 超出单页注入上限，已跳过');}catch(e){}\n");
                continue;
            }
            total += size;
            b.append(wrap(u, req, code));
        }
        b.append("})();\n");
        return b.toString();
    }

    static String wrap(Userscript u, String req, String code) {
        StringBuilder b = new StringBuilder(code.length() + 2048);
        b.append(";(function(){try{\n");
        b.append("var __G=window.__hdUsMakeGM(").append(q(u.id)).append(",")
                .append(metaJson(u)).append(");\n");
        b.append("var GM_info=__G.GM_info,GM=__G.GM,unsafeWindow=window;\n");
        b.append("var GM_getValue=__G.GM_getValue,GM_setValue=__G.GM_setValue,")
                .append("GM_deleteValue=__G.GM_deleteValue,GM_listValues=__G.GM_listValues,")
                .append("GM_addStyle=__G.GM_addStyle,GM_log=__G.GM_log,")
                .append("GM_notification=__G.GM_notification,GM_setClipboard=__G.GM_setClipboard,")
                .append("GM_openInTab=__G.GM_openInTab,GM_download=__G.GM_download,")
                .append("GM_xmlhttpRequest=__G.GM_xmlhttpRequest,")
                .append("GM_registerMenuCommand=__G.GM_registerMenuCommand;\n");
        if (req != null && req.length() > 0) b.append(req).append("\n;\n");
        // 页面是 HTML 内联注入时，代码里的 </script 会提前结束标签。
        b.append(code.replace("</script", "<\\/script"));
        b.append("\n}catch(e){try{console.error('[幻豆油猴] '+")
                .append(q(u.displayName()))
                .append("+' 执行出错:',e);}catch(x){}}})();\n");
        b.append("//# sourceURL=hd-us-").append(u.id).append(".js\n");
        return b.toString();
    }

    private static String metaJson(Userscript u) {
        StringBuilder b = new StringBuilder(256);
        b.append("{name:").append(q(u.name))
                .append(",namespace:").append(q(u.namespace))
                .append(",version:").append(q(u.version))
                .append(",description:").append(q(u.description))
                .append(",runAt:").append(q(u.runAt))
                .append(",matches:").append(array(u.matches))
                .append(",grants:").append(array(u.grants))
                .append("}");
        return b.toString();
    }

    private static String array(ArrayList<String> a) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) b.append(',');
            b.append(q(a.get(i)));
        }
        return b.append(']').toString();
    }

    /** JS 字符串字面量。手写而不引 JSONObject，避免把 / 转义成 \/ 影响正则。 */
    static String q(String s) {
        if (s == null) return "''";
        StringBuilder b = new StringBuilder(s.length() + 16);
        b.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'': b.append("\\'"); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                case '<': b.append("\\u003c"); break;
                default:
                    if (c < 0x20) {
                        b.append("\\u");
                        String h = Integer.toHexString(c);
                        for (int k = h.length(); k < 4; k++) b.append('0');
                        b.append(h);
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('\'').toString();
    }

    // ---------------- GM 运行时 ----------------

    static final String CORE =
            "(function(){"
            + "if(window.__hdUsCore)return;window.__hdUsCore=1;"
            + "window.__hdUsMenus=window.__hdUsMenus||{};"
            + "window.__hdUsXhrPend=window.__hdUsXhrPend||{};"

            + "window.__hdUsXhrDone=function(rid,status,body,headers,finalUrl,err){"
            + "var o=window.__hdUsXhrPend[rid];if(!o)return;"
            + "delete window.__hdUsXhrPend[rid];"
            + "if(err){try{if(o.onerror)o.onerror({error:err,status:0});}catch(e){}return;}"
            + "var r={status:status,statusText:'',responseText:body,response:body,"
            + "finalUrl:finalUrl,responseHeaders:headers};"
            + "if(o.responseType==='json'){try{r.response=JSON.parse(body);}catch(e){}}"
            + "try{if(o.onload)o.onload(r);}catch(e){}"
            + "};"

            + "window.__hdUsRunMenu=function(id,idx){"
            + "var l=window.__hdUsMenus[id];"
            + "if(l&&l[idx]&&typeof l[idx].fn==='function'){try{l[idx].fn();}catch(e){}}"
            + "};"

            // 页面被替换时上一页的挂起请求会丢失，避免回调打到新文档上。
            + "window.__hdUsReset=function(){window.__hdUsXhrPend={};};"

            + "window.__hdUsMakeGM=function(id,info){"
            + "var seq=0;"
            + "function raw(k){try{var v=HuandouUs.get(id,k);"
            + "return (v===null||v===undefined)?'':String(v);}catch(e){return null;}}"
            + "function get(k,d){var v=raw(k);"
            + "if(v===null){try{v=localStorage.getItem('__hdus_'+id+'_'+k)||'';}catch(e){v='';}}"
            + "if(v==='')return d;"
            + "try{return JSON.parse(v);}catch(e){return v;}}"
            + "function set(k,v){var s;try{s=JSON.stringify(v);}catch(e){s=JSON.stringify(String(v));}"
            + "if(s===undefined)s='null';"
            + "try{HuandouUs.set(id,k,s);}"
            + "catch(e){try{localStorage.setItem('__hdus_'+id+'_'+k,s);}catch(x){}}}"
            + "function del(k){try{HuandouUs.del(id,k);}"
            + "catch(e){try{localStorage.removeItem('__hdus_'+id+'_'+k);}catch(x){}}}"
            + "function keys(){var s='[]';try{s=HuandouUs.keys(id);}catch(e){}"
            + "var a=[];try{a=JSON.parse(s||'[]');}catch(e){return [];}return a;}"
            + "function addStyle(css){var s=document.createElement('style');"
            + "s.textContent=css;var p=document.head||document.documentElement;"
            + "if(!p){document.addEventListener('DOMContentLoaded',function(){"
            + "var q=document.head||document.documentElement;if(q)q.appendChild(s);});return s;}"
            + "p.appendChild(s);return s;}"
            + "function log(){try{console.log.apply(console,"
            + "['[幻豆油猴]'].concat(Array.prototype.slice.call(arguments)));}catch(e){}}"
            + "function notify(a,b){var t='',x='';"
            + "if(a&&typeof a==='object'){t=a.title||'';x=a.text||'';}"
            + "else{x=String(a==null?'':a);t=b?String(b):'';}"
            + "try{HuandouUs.notify(t,x);}catch(e){}}"
            + "function copy(t){try{HuandouUs.copy(String(t==null?'':t));}catch(e){}}"
            + "function openTab(u,x){try{HuandouUs.open(String(u),!!x);}"
            + "catch(e){try{window.open(u,'_blank');}catch(x2){}}}"
            + "function download(a){var u=(typeof a==='string')?a:(a&&a.url);"
            + "var n=(a&&a.name)||'';if(!u)return;"
            + "try{HuandouUs.download(String(u),String(n));}"
            + "catch(e){try{window.open(u,'_blank');}catch(x){}}}"
            + "function xhr(o){var rid=id+'_'+(++seq);"
            + "window.__hdUsXhrPend[rid]=o;"
            + "var h='{}';try{h=o.headers?JSON.stringify(o.headers):'{}';}catch(e){}"
            + "try{HuandouUs.xhr(rid,(o.method||'GET').toUpperCase(),o.url,h,"
            + "o.data==null?'':String(o.data));}"
            + "catch(e){delete window.__hdUsXhrPend[rid];"
            + "try{if(o.onerror)o.onerror({error:String(e)});}catch(x){}}"
            + "return {abort:function(){try{HuandouUs.xhrAbort(rid);}catch(e){}}};}"
            + "function regMenu(n,fn){var l=window.__hdUsMenus[id]||(window.__hdUsMenus[id]=[]);"
            + "l.push({name:String(n),fn:fn});return l.length-1;}"
            + "var GM={info:info,"
            + "getValue:function(k,d){return Promise.resolve(get(k,d));},"
            + "setValue:function(k,v){set(k,v);return Promise.resolve();},"
            + "deleteValue:function(k){del(k);return Promise.resolve();},"
            + "listValues:function(){return Promise.resolve(keys());},"
            + "addStyle:addStyle,log:log,notification:notify,"
            + "setClipboard:function(t){copy(t);return Promise.resolve();},"
            + "openInTab:function(u){openTab(u,true);return Promise.resolve();},"
            + "download:function(a){download(a);return Promise.resolve();},"
            + "registerMenuCommand:regMenu,"
            + "xmlHttpRequest:function(o){return new Promise(function(res,rej){"
            + "var p={method:o.method,url:o.url,headers:o.headers,data:o.data,"
            + "responseType:o.responseType,timeout:o.timeout,"
            + "onload:res,onerror:rej};xhr(p);});}};"
            + "return {GM_info:info,GM:GM,"
            + "GM_getValue:get,GM_setValue:set,GM_deleteValue:del,GM_listValues:keys,"
            + "GM_addStyle:addStyle,GM_log:log,GM_notification:notify,"
            + "GM_setClipboard:copy,GM_openInTab:openTab,GM_download:download,"
            + "GM_xmlhttpRequest:xhr,GM_registerMenuCommand:regMenu};"
            + "};"
            + "})();";
}
