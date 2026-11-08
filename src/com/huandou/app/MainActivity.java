package com.huandou.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String PREF = "huandou_pref";
    private static final String KEY_IMMERSIVE = "immersive";
    private static final String KEY_NOTI_ASKED = "noti_permission_asked";
    private static final String KEY_FP = "fp_enabled";
    private static final String KEY_HTML = "html_inject";
    private static final String KEY_CANVAS = "canvas_noise";
    private static final String KEY_AWAKE = "keep_awake";
    private static final String KEY_DEBUG = "web_debug";
    private static final String KEY_ZOOM = "text_zoom";
    private static final String KEY_HOME = "home_url";
    /** 地址栏用的搜索引擎下标，见 ENGINES / ENGINE_URLS。 */
    private static final String KEY_ENGINE = "search_engine";

    /**
     * 上一次云端验证的结论。跨对话框保留，重开诊断页还能看到上次结果。
     *
     * <p>只存「服务端说的」，不存本地推断。null 表示这次启动还没验过。</p>
     */
    private static volatile AttestClient.Verdict lastVerdict;
    /** 上一次验证的时间戳，给「几分钟前」用。 */
    private static volatile long lastVerdictAt;

    /** 云端验证的过程日志，显示在诊断文本顶部。只在主线程写，最多留 8 行。 */
    private final StringBuilder cloudLog = new StringBuilder();

    private static final String[] ENGINES = {"百度", "必应", "Google", "DuckDuckGo"};
    private static final String[] ENGINE_URLS = {
            "https://www.baidu.com/s?wd=",
            "https://www.bing.com/search?q=",
            "https://www.google.com/search?q=",
            "https://duckduckgo.com/?q="
    };
    private static final String KEY_COMPAT = "compat_mode";
    private static final String KEY_COMPAT_DONE = "compat_v12_done";
    private static final String KEY_TLS_ALIGN = "tls_align_chrome";
    private static final String KEY_TO_MP4 = "auto_to_mp4";
    /** 上次下载用的剧名（不含「第N集」），用于下一集自动沿用。 */
    private static final String KEY_LAST_NAME = "last_dl_name";
    private static final String KEY_LAST_EP = "last_dl_ep";
    /** 上次页面上抓到的原始剧名，用来判断「还是这部剧吗」。 */
    private static final String KEY_LAST_SRC = "last_dl_src";
    /** 记录「已经弹过更新说明的版本号」。按版本号比较，换版本必然重新弹一次。 */
    private static final String KEY_NOTICE_VERSION = "upgrade_notice_version";
    /** 服务端公告：记录已关闭的那条的标识（内容哈希）。内容变了会重新出现。 */
    private static final String KEY_RC_NOTICE = "rc_notice_seen";
    private static final String KEY_INCOGNITO = "incognito_mode";
    private static final String KEY_DARK = "force_dark";
    private static final String KEY_SAFE = "safe_browse";
    private static final String KEY_BOOKMARKS = "bookmarks_json";
    private static final String KEY_BLACKLIST = "safe_blacklist";
    /** 严格模式：高危站点直接拦截页，不再问「要不要继续」。 */
    private static final String KEY_SAFE_STRICT = "safe_strict";
    private static final String KEY_WHITELIST = "safe_whitelist";
    private static final String KEY_SAFE_RDAP = "safe_rdap";
    private static final String KEY_SAFE_PROXY = "safe_proxy";
    private static final String KEY_SAFE_AGE = "safe_age_cache";
    private static final String KEY_US = "userscripts_on";
    private static final String KEY_US_START = "userscripts_first_frame";

    // 版本号一律从 PackageManager 读（即 AndroidManifest / build.sh 里的值），
    // 不再在源码里写死 —— 否则改包版本时这里会永远停在旧值。
    // 只允许出现在弹窗内：启动器名称、工具栏、toast 一律不带版本号。
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int appVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- Unified native z-order -----------------------------------------
    // WebView(0) < toolbar(2) < blur backdrop(10) < fullscreen video(15)
    // < find/error overlay(20) < splash(30) < dialog Window(100).
    // Dialogs live in their own Window, so they are ALWAYS above every View
    // here.  H5 z-index only exists inside the WebView and can never cross
    // this native stack -- no CSS injection is needed (or desirable).
    private static final float Z_WEB = 0f;
    private static final float Z_TOOLBAR = 2f;
    private static final float Z_BACKDROP = 10f;
    private static final float Z_FULLSCREEN = 15f;
    private static final float Z_OVERLAY = 20f;
    private static final float Z_SPLASH = 30f;
    /** Single 40% alpha mask, within the recommended 30%-50% range. */
    private static final int MASK_COLOR = 0x660A070D;
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_PERMISSION = 1002;
    private static final int REQ_BOOKMARK_IN = 1004;

    /** 等待通知权限期间暂存的下载任务（授予后自动开始）。 */
    private String pendingDlUrl;
    private String pendingDlType;
    private String pendingDlBase;
    /** 应用内进度兜底：通知被禁用时也能看到进度，用过的百分比去重。 */
    private int lastUiPct = -1;
    /** 不依赖系统通知的悬浮进度条。 */
    private TextView dlHud;
    private Runnable dlHudHide;

    private static final int BRAND = 0xFF0A070D;
    private static final int BAR = 0xFF171021;
    private static final int ACCENT = 0xFF9B7BFF;
    private static final int MP = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String SITE_CLEAN_JS = "(function(){"
            + "try{localStorage.clear();}catch(e){}"
            + "try{sessionStorage.clear();}catch(e){}"
            + "try{if(window.indexedDB&&indexedDB.databases){"
            + "indexedDB.databases().then(function(ds){(ds||[]).forEach(function(d){"
            + "try{indexedDB.deleteDatabase(d.name);}catch(e){}});});}}catch(e){}"
            + "try{if(window.caches){caches.keys().then(function(ks){(ks||[]).forEach(function(k){"
            + "caches.delete(k);});});}}catch(e){}"
            + "try{if(navigator.serviceWorker&&navigator.serviceWorker.getRegistrations){"
            + "navigator.serviceWorker.getRegistrations().then(function(rs){(rs||[]).forEach(function(r){"
            + "r.unregister();});});}}catch(e){}"
            + "})();";

    /** Export Web Storage in the VaultCodec line format (see VaultCodec.applyDump). */
    private static final String DUMP_STORAGE_JS = "(function(){try{"
            + "var o=['V1'],i,k;"
            + "try{for(i=0;i<localStorage.length;i++){k=localStorage.key(i);"
            + "o.push('L'+encodeURIComponent(k)+'\\t'+"
            + "encodeURIComponent(String(localStorage.getItem(k))));}}catch(e){}"
            + "try{for(i=0;i<sessionStorage.length;i++){k=sessionStorage.key(i);"
            + "o.push('S'+encodeURIComponent(k)+'\\t'+"
            + "encodeURIComponent(String(sessionStorage.getItem(k))));}}catch(e){}"
            + "return o.join('\\n');"
            + "}catch(e){return 'V1';}})();";

    private static final String BLANK_FIX_JS = "(function(){"
            + "if(window.__hdBlankFix)return;window.__hdBlankFix=true;"
            + "document.addEventListener('click',function(e){var a=e.target;"
            + "while(a&&a.tagName!=='A')a=a.parentElement;"
            + "if(a&&a.target==='_blank')a.target='_self';},true);})();";

    /** Scan the page for media URLs already loaded by the browser. */
    private static final String MEDIA_SCAN_JS = "(function(){"
            + "try{var out=[],seen={};"
            + "function add(u){if(typeof u!=='string'||!u)return;"
            + "if(!/^https?:/i.test(u))return;"
            + "if(seen[u])return;seen[u]=1;out.push(u);}"
            + "try{if(window.performance&&performance.getEntriesByType){"
            + "performance.getEntriesByType('resource').forEach(function(e){add(e.name);});}}catch(e){}"
            + "try{var els=document.querySelectorAll("
            + "'video,source,audio,img,picture source,a[href]');"
            + "for(var i=0;i<els.length;i++){var e=els[i];"
            + "add(e.currentSrc||e.src||e.getAttribute('src'));"
            + "add(e.href||e.getAttribute('href'));"
            // 图片常写成 srcset="a.jpg 1x, b.jpg 2x"，不解析就只有一张
            + "var ss=e.getAttribute&&e.getAttribute('srcset');"
            + "if(ss){var ps=ss.split(',');for(var k=0;k<ps.length;k++){"
            + "add((ps[k]||'').trim().split(' ')[0]);}}"
            + "if(e.dataset){add(e.dataset.src);add(e.dataset.url);"
            + "add(e.dataset.original);add(e.dataset.lazy);}}}catch(e){}"
            + "try{var html=(document.documentElement&&document.documentElement.innerHTML)||'';"
            + "var re=/https?:[^\\s\"'<>]{0,300}?\\.(?:m3u8|mp4|m4v|ts|webm|mov|mkv|"
            + "mpd|mp3|m4a|aac|flac|ogg|wav|jpg|jpeg|png|gif|webp|avif)"
            + "(?:\\?[^\\s\"'<>]*)?/gi;"
            + "var m,n=0;while((m=re.exec(html))!==null&&n<60){add(m[0]);n++;}}catch(e){}"
            + "try{HuandouNative.onMediaFound(JSON.stringify(out));}catch(e){}"
            + "}catch(e){}})();";

    /**
     * 抓「这部剧叫什么、现在第几集」。
     *
     * <p>短剧站的标题几乎都是「某某剧 第3集 | 共80集」，但单页应用切集时
     * document.title 经常不更新。这里同时收集 document.title、og:title、
     * 播放器标题和页面上常见标题容器的文本，交给 Java 侧评分挑最像剧名的一条。</p>
     */
    private static final String PAGE_META_JS = "(function(){"
            + "try{var o={t:'',og:'',h:[],ep:''};"
            + "o.t=(document.title||'').replace(/\\s+/g,' ').trim();"
            + "try{var m=document.querySelector('meta[property=\"og:title\"]');"
            + "if(m)o.og=(m.getAttribute('content')||'').replace(/\\s+/g,' ').trim();"
            + "if(!o.og){var m2=document.querySelector('meta[name=\"twitter:title\"]');"
            + "if(m2)o.og=(m2.getAttribute('content')||'').replace(/\\s+/g,' ').trim();}}catch(e){}"
            + "var sel=['h1','h2','h3','.video-title','.vod-title','.detail-title','.play-title',"
            + "'.series-title','.drama-title','.episode-title','.player-title','.title','.name',"
            + "'[class*=\"videoName\"]','[class*=\"video-name\"]','[class*=\"dramaName\"]',"
            + "'[class*=\"drama-name\"]','[class*=\"playTitle\"]','[class*=\"play-title\"]'];"
            + "var seen={},n=0;"
            + "for(var i=0;i<sel.length&&n<16;i++){var ns;try{ns=document.querySelectorAll(sel[i]);}catch(e){continue;}"
            + "for(var j=0;j<ns.length&&n<16;j++){var s=(ns[j].textContent||'').replace(/\\s+/g,' ').trim();"
            + "if(!s||s.length>40||seen[s])continue;"
            + "if(!/[\\u4e00-\\u9fff]/.test(s)&&s.length<6)continue;"
            + "seen[s]=1;o.h.push(s);n++;}}"
            + "try{var v=document.querySelector('video');if(v){var at=['title','data-title','data-name'];"
            + "for(var k=0;k<at.length;k++){var a=v.getAttribute(at[k]);"
            + "if(a&&a.trim()){var q=a.replace(/\\s+/g,' ').trim();if(!seen[q]){seen[q]=1;o.h.push(q);}}}}}catch(e){}"
            // 找「紧挨着第N集标记」的那段文字：那才是正在播的这部剧。
            // 首页 banner 上的「热门短剧随心看」离集数很远，不会被选中。
            // 关键：去掉集数后必须还剩至少两个汉字，否则抓到的是「第1集|共5集」
            // 这种纯标记，反而把真正的剧名挡在外面。
            + "var near='';try{var bc=1e9;"
            + "var nc=document.querySelectorAll('[class*=title],[class*=name],[class*=ep],"
            + "[class*=num],[class*=video],h1,h2,h3,h4');"
            + "for(var q=0;q<nc.length&&q<80;q++){var e2=nc[q];"
            + "for(var up=0;up<3&&e2;up++){var tx=(e2.textContent||'').replace(/\\s+/g,' ').trim();"
            + "if(tx.length>0&&tx.length<=60&&/第\\s*0*\\d{1,4}\\s*[集话話期]/.test(tx)){"
            + "var b2=tx.replace(/第\\s*0*\\d{1,4}\\s*[集话話期]/g,'')"
            + ".replace(/[共全]\\s*0*\\d{1,4}\\s*[集话話期]/g,'')"
            + ".replace(/[|｜\\/·\\-]/g,' ').trim();"
            + "var cj=(b2.match(/[\\u4e00-\\u9fff]/g)||[]).length;"
            + "if(cj>=2&&tx.length<bc){bc=tx.length;near=tx;}}"
            + "e2=e2.parentElement;}}}catch(e){}o.near=near;"
            + "var all=[o.t,o.og].concat(o.h).join(' ');"
            + "var mm=all.match(/第\\s*0*(\\d{1,4})\\s*[集话話期]/);if(mm)o.ep=mm[1];"
            + "if(!o.ep){var mu=location.href.match(/[?&#](?:ep|episode|ji|n)=0*(\\d{1,3})/i);if(mu)o.ep=mu[1];}"
            + "try{HuandouNative.onPageMeta(JSON.stringify(o));}catch(e){}"
            + "}catch(e){}})();";

    private static final String[] MENU = {
            // null = 不在菜单里显示。留空占位是刻意的：索引与下面的 case 一一对应，
            // 删项不重排就不会把功能点串。
            null,                                   // 0 原「随机新建身份」，与顶栏「新身份」重复
            "身份管理 / 新建 / 切换…",
            "清除站点数据并重载",
            "── 指纹与伪装（开启可能触发网站风控）──",
            "指纹伪装",
            "HTML 首帧注入",
            "Canvas 指纹扰动",
            "查看当前 UA / 设备档案",
            "自定义 UA…",
            "── 浏览工具 ──",
            "查找网页文字",
            "分享当前页面",
            "用外部浏览器打开",
            "字体大小…",
            "屏幕常亮",
            "回到首页",
            "设置首页地址…",
            "刷新页面",
            "下载记录",
            "诊断信息",
            "WebView 远程调试",
            "沉浸模式",
            "⟳ 一键修复：恢复兼容模式",
            "🎬 媒体嗅探 / 下载…",
            null,                                   // 24 原「自动转 MP4」开关，已固定为常开
            null,                                   // 25 原「批量生成身份」，已并入身份管理面板
            "🔒 Chrome 版本对齐本机 WebView",
            "── 隐私与阅读 ──",
            "🕶 无痕模式",
            "🌙 强制深色",
            "📖 阅读模式 / 朗读",
            "⭐ 书签",
            "🛡 安全浏览 / 拦截…",
            "── 扩展与脚本 ──",
            "🐵 用户脚本（油猴）…"
    };

    private String home;
    /**
     * 首页的域名，用于给黑名单做自保护。
     *
     * <p>后端名单配错一条就能把 App 拦到白屏，而「首页被拦」是最糟的一种：
     * 用户连诊断页都不一定进得去。所以首页域名永远不参与拦截判断，
     * 这一条不接受远端配置。</p>
     */
    private String homeHost;
    private SharedPreferences pref;
    private IdentityManager identities;
    private IdentityVault vault;
    /** Hosts seen loading resources, so sub-domain cookies get captured too. */
    private final LinkedHashSet<String> cookieHosts = new LinkedHashSet<String>();
    private String pendingRestoreJs;
    private String lastRestoreJs;
    private boolean restoreCheckArmed;
    private boolean restoreRetried;
    private long lastCaptureAt;
    private boolean switching;
    private volatile DeviceProfile profile;

    private FrameLayout root;
    private LinearLayout mainBox;
    private LinearLayout toolbar;
    private View progressLine;
    /** 服务端公告条，整条常驻在工具栏下方、网页上方；没有公告时 GONE。 */
    private LinearLayout noticeBar;
    private TextView noticeText;
    private LinearLayout findBar;
    private EditText findInput;
    private PullRefreshLayout pull;
    private WebView web;
    private View splash;
    private ProgressBar splashProgress;
    private View errorView;
    private TextView errorTitle, errorDetail, errorUrl, errorTip;
    private LinearLayout infoHolder;
    private DinoView dinoView;
    private TextView dinoClose;
    private FrameLayout fullscreenHost;
    private boolean mediaHintShown;
    /**
     * 本进程内被黑名单拦下的域名，给诊断页看。
     *
     * <p>用同步 Set 是因为 {@code shouldInterceptRequest} 跑在 WebView 的 IO 线程，
     * 诊断页在主线程读。上限 40 条，防止某个页面疯狂请求把内存堆爆。</p>
     */
    private final java.util.Set<String> blockedSeen =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    private FrameLayout blurLayer;
    private ImageView blurImage;
    private int glassRefs;
    private Dialog glassTop;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> filePathCallback;

    private boolean splashDone;
    private boolean immersive;
    private boolean compatMode;
    private boolean compatWarned;
    private boolean pendingDeepClean;
    private boolean fpEnabled;
    private boolean htmlInject;
    private boolean canvasNoise;
    private boolean keepAwake;
    private boolean webDebug;
    private boolean tlsAlign;
    /** 无痕：不接收 Cookie、退出时清空本地数据。 */
    private boolean incognito;
    /** 所有网页强制反色成深色。 */
    private boolean forceDark;
    /** 访问可疑站点时先提示。 */
    private boolean safeBrowse;
    private TextToSpeech tts;
    private String ttsText;
    /** 已经提示过的风险地址，避免重定向时连弹。 */
    private String lastRiskUrl;
    /** 本地威胁情报库（十几万条域名指纹），启动后异步载入内存。 */
    private volatile SafeBrowse.Store phishStore;
    private volatile SafeBrowse.Store malwareStore;
    /** 严格模式：高危直接拦截页。 */
    private boolean safeStrict;
    /** 域名年龄查询（RDAP）。只在页面本身已经可疑时才查，且只发送注册域名。 */
    private boolean safeRdap;
    /** 可选的情报查询代理前缀（自建 Worker），为空表示直连。 */
    private String safeProxy = "";
    /** 整页拦截层与顶部提示条。 */
    private View blockView;
    private TextView safeBanner;
    /** 本次运行内点过「仍然访问」的地址，不再打扰。 */
    private final LinkedHashSet<String> safeApproved = new LinkedHashSet<String>();
    /** 域名注册时间缓存：domain -> 注册时刻毫秒；-1 表示查不到。 */
    private final HashMap<String, Long> safeAgeCache = new HashMap<String, Long>();
    /** 用户点了「检查当前页面」，下一次页面上报要出详细报告而不是提示条。 */
    private boolean pendingPageReport;
    /** 油猴脚本总开关与「首帧注入」（改写 HTML 以保证 document-start 的时序）。 */
    private boolean userscriptsOn;
    private boolean userscriptsStart;
    private UserscriptStore userscripts;
    private Dialog usDetailDlg;
    /** 搜索面板选中的引擎，见 ENGINES / ENGINE_URLS。 */
    private int engineIndex;
    private int textZoom;
    private long lastBackTime;
    /** 准入页自动重试过几次。限流给一次自动重试，再多就该让用户自己决定。 */
    private int gateAttempt;
    private String currentUrl;

    /**
     * 从页面 DOM 抓到的「剧名 + 第几集」。
     *
     * <p>短剧站几乎都是单页应用：{@code web.getTitle()} 常年停在「幻豆短剧」不动，
     * 真实剧名只出现在播放器下方的 DOM 里。所以这里由注入脚本主动上报，
     * 命名时优先用它，其次才退回 WebView 标题。</p>
     */
    private volatile String pageTitleHint;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 第一件事：装崩溃记录。放在签名校验之前，因为它自己也不该因为
        // 某个初始化顺序问题而漏掉——上一版就是点按钮闪退、而手上什么都没有。
        CrashLog.install(this);

        // 设备凭据与配置的同步排在 20 秒后，不占启动路径。
        scheduleCloudSync();

        // 第二步就验签名，比任何功能初始化都早。改包重签的安装包到这里就停，
        // WebView 不会起、Cookie 不会被读、脚本引擎不会跑。
        // 这一处判断能被 patch，所以 DownloadService 那边还有一道（见 onStartCommand）。
        final String sigActual = SignatureGuard.check(this);
        if (sigActual != null) {
            showTamperScreen(sigActual);
            return;
        }

        // 第二道：设备准入，fail-closed。
        //
        // 放在这里而不是像上一版那样「先启动、20 秒后后台校验、失败静默」——
        // 那个顺序等于没有门禁：改包版早就进到界面里了，而后台那句失败
        // 只会写在诊断页里，没人看。
        //
        // 判据是「手上有没过期的凭据」，不是「这一次连上网了」。凭据 7 天有效，
        // 所以正常用户断网照常用；改包版从来没成功过一次，手里永远是空的。
        if (!Gate.allowed(this)) {
            showGateScreen();
            return;
        }

        pref = getSharedPreferences(PREF, MODE_PRIVATE);
        home = pref.getString(KEY_HOME, getString(R.string.home_url));
        homeHost = hostOf(home);
        engineIndex = pref.getInt(KEY_ENGINE, 0);
        if (engineIndex < 0 || engineIndex >= ENGINES.length) engineIndex = 0;
        immersive = pref.getBoolean(KEY_IMMERSIVE, false);

        // v1.1 默认开启指纹伪装 + HTML 注入，站点会把这种「克隆设备」判为
        // 身份授权码重放（IDENTITY_CODE_REPLAYED）。此处做一次性回退。
        compatMode = pref.getBoolean(KEY_COMPAT, true);
        boolean needMigrate = !pref.getBoolean(KEY_COMPAT_DONE, false);
        if (needMigrate) {
            compatMode = true;
            pref.edit()
                    .putBoolean(KEY_COMPAT, true)
                    .putBoolean(KEY_FP, false)
                    .putBoolean(KEY_HTML, false)
                    .putBoolean(KEY_CANVAS, false)
                    .putBoolean(KEY_COMPAT_DONE, true)
                    .apply();
        }
        fpEnabled = compatMode ? false : pref.getBoolean(KEY_FP, false);
        htmlInject = compatMode ? false : pref.getBoolean(KEY_HTML, false);
        canvasNoise = compatMode ? false : pref.getBoolean(KEY_CANVAS, false);
        keepAwake = pref.getBoolean(KEY_AWAKE, false);
        webDebug = pref.getBoolean(KEY_DEBUG, false);
        tlsAlign = pref.getBoolean(KEY_TLS_ALIGN, false);
        incognito = pref.getBoolean(KEY_INCOGNITO, false);
        forceDark = pref.getBoolean(KEY_DARK, false);
        safeBrowse = pref.getBoolean(KEY_SAFE, true);
        // 本地开关是用户自己的意愿，服务端开关是紧急刹车，两者取「与」：
        // 用户自己关了的，服务端不能替他打开；服务端叫停的，用户也开不了。
        // 默认值传 true——脚本引擎是已发布功能，配置拿不到时不该凭空消失。
        userscriptsOn = pref.getBoolean(KEY_US, true)
                && RemoteConfig.feature(this, "userscript", true);
        // 首帧注入要由 Java 重新拉一遍 HTML，代价是多一次请求；换来的是脚本
        // 真的在站点脚本之前执行。关掉后 document-start 脚本延到 onPageFinished。
        userscriptsStart = pref.getBoolean(KEY_US_START, true);
        userscripts = UserscriptStore.get(this);
        safeStrict = pref.getBoolean(KEY_SAFE_STRICT, false);
        safeRdap = pref.getBoolean(KEY_SAFE_RDAP, true);
        safeProxy = pref.getString(KEY_SAFE_PROXY, "");
        textZoom = pref.getInt(KEY_ZOOM, 100);
        // 自动转 MP4 已改为固定行为，把旧存档里的「关」也纠正过来。
        if (!pref.getBoolean(KEY_TO_MP4, true)) {
            pref.edit().putBoolean(KEY_TO_MP4, true).apply();
        }
        loadSafeStores();

        identities = new IdentityManager(this);
        vault = new IdentityVault(this);
        if (compatMode) {
            // 兼容模式：使用本机原生 WebView UA，身份保持稳定，不做任何指纹改写。
            profile = DeviceProfile.system(this);
        } else {
            profile = identities.active();
            if (profile == null) {
                profile = DeviceProfile.system(this);
                compatMode = true;
                pref.edit().putBoolean(KEY_COMPAT, true).apply();
            }
        }

        setupWindow();
        buildUi();
        setupWebView();
        applyPreferences();
        // 用上次缓存的配置先亮一次公告，不用等 20 秒后的后台同步。
        // 首次安装时这里是空的，等 sync 成功会再刷一次。
        applyNotice();
        requestPermissionsIfNeeded();

        if (needMigrate) {
            hardResetStorage();
            pendingDeepClean = true;
        }
        web.loadUrl(home);
        showUpdateNoticeIfNeeded();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSplash();
            }
        }, 9000);
    }

    /**
     * 更新说明：按「版本号」判断是否已提示过，而不是一个一次性布尔值。
     *
     * 旧实现用的是 upgrade_notice_v154_done 这种布尔开关，弹过一次就永久关闭，
     * 之后再怎么升级都不会再提示；而且标题里的版本号是源码里写死的常量，
     * 即使弹出来也永远显示旧版本。现在两者都改为跟随真实包版本。
     */
    private void showUpdateNoticeIfNeeded() {
        final String ver = appVersion();
        String seen = pref.getString(KEY_NOTICE_VERSION, "");
        if (ver.equals(seen)) return;          // 这个版本已经提示过
        final boolean firstRun = seen.length() == 0;
        pref.edit().putString(KEY_NOTICE_VERSION, ver).apply();

        String title = (firstRun ? "欢迎使用 幻豆 " : "幻豆 已更新到 ") + ver;
        showGlass(new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(changeLogFor(ver))
                .setPositiveButton("知道了", null));
    }

    /** 当前版本的更新说明。没有专门文案的版本给一段通用说明。 */
    private String changeLogFor(String ver) {
        if ("1.9.5".equals(ver)) {
            return "本次更新：首次启动需要联网校验一次\n\n"
                    + "· 从这一版开始，幻豆在启动时会向服务端确认一次安装包。"
                    + "通过之后记住 7 天，这 7 天里断网、飞行模式都不影响使用。\n\n"
                    + "· 为什么要这样做：App 里的判断谁都能改，服务端签发的凭据改不了。"
                    + "被重新打包过的安装包拿不到这份凭据，也始终通不过校验。\n\n"
                    + "· 刚装上、或者凭据过期的第一次，必须联网。这次会慢一点"
                    + "（生成设备证书要一两秒）。校验没通过会显示具体原因，"
                    + "不是白屏也不是闪退。\n\n"
                    + "· 校验不通过时页面会写明原因：设备系统版本过低、系统已解锁、"
                    + "安装包被改过，或者只是暂时连不上服务器。能重试的会给重试按钮。\n\n"
                    + "· 诊断页原来那行「设备凭据」改成了「准入」，会显示还剩几天到期。\n\n"
                    + "· 注意：这一版对老设备更严格了。如果你有 Android 7 以下的设备，"
                    + "或者刷过第三方系统的机器，可能无法通过校验。";
        }
        if ("1.9.4".equals(ver)) {
            return "本次更新：能看见这个站到底在跟谁说话了\n\n"
                    + "· 诊断页「技术细节」展开后，多了一段「第三方域名」：本次打开页面时，"
                    + "除了它自己的服务器，还连了哪些外面的域名，一共几个。\n\n"
                    + "· 里面带「统计/广告」标记的，是内置参考表里认得的统计和广告平台。"
                    + "只是提示，不会自动拦——自动拦错一次，页面就打不开了。\n\n"
                    + "· 为什么做这个：要拦广告，先得知道拦谁。凭空猜域名，猜错就是白屏。\n\n"
                    + "· 这段只在展开技术细节后显示。默认那份是会被随手截图发出去的，"
                    + "域名列表等于访问记录，不该放在那里。\n\n"
                    + "· 其它一切不变。";
        }
        if ("1.9.3".equals(ver)) {
            return "本次更新：服务端的通知和拦截名单，这次真的生效了\n\n"
                    + "· 上一版把管道铺好了，但配置拉下来只是存着，一个字都没用上。"
                    + "这一版把它接到了实际行为上。\n\n"
                    + "· 公告：服务端下发公告时，工具栏下面会出现一条通知条，可以直接关掉。"
                    + "关过的那条不会再出现；服务端换了新内容，会重新出现一次。\n\n"
                    + "· 拦截名单：服务端下发的域名直接断掉，不再加载。"
                    + "如果整页被拦，会显示一张说明页，而不是白屏。"
                    + "诊断页能看本次拦下了几个域名。\n\n"
                    + "· 应急开关：脚本引擎可以由服务端临时关闭。"
                    + "默认是开的，拉不到配置就一切照旧。\n\n"
                    + "· 逃生门：诊断页「技术细节」展开后可以清空下发配置。"
                    + "万一哪天名单里错配了一条，你能自己救自己。\n\n"
                    + "· 现在服务端还没下发任何内容，所以装上先看不到区别。";
        }
        if ("1.9.2".equals(ver)) {
            return "本次更新：为设备凭据做准备，没有可见变化\n\n"
                    + "· 这一版不会让你看到任何不同，说明白点：它是给服务端留的一条管道。\n\n"
                    + "· 冷启动二十秒后，App 会在后台悄悄做一次验证，向服务端换一张七天有效的"
                    + "设备凭据，再用它拉取配置（公告、黑名单更新这类东西）。\n\n"
                    + "· 为什么现在做：以后想在服务端区分「官方包」和「重打包的包」，"
                    + "靠的就是这张凭据。没有它，验证结论只能显示在诊断页上给人看，"
                    + "打完包的人把那一行字删掉就绕过去了。\n\n"
                    + "· 换不到会怎样：什么都不发生。断网、服务端没上线、设备不支持硬件认证，"
                    + "全都不影响任何功能——配置是增量，不是依赖。失败后会歇六小时再试，"
                    + "不会每次启动都跑一遍。\n\n"
                    + "· 诊断页多一行「设备凭据」，显示有效期或失败原因。";
        }
        if ("1.9.1".equals(ver)) {
            return "本次更新：修 1.9.0 点按钮必闪退\n\n"
                    + "· 现象：诊断页点「一键验证」，一两秒内直接闪退，没有提示。\n\n"
                    + "· 原因：验证跑在后台线程，网络回来之后要在界面上写结论。"
                    + "我漏了把这一步切回主线程——Android 不允许非主线程碰界面，"
                    + "一碰就抛异常，而且抛在子线程里，没人接，于是整个进程退出。\n\n"
                    + "· 修法：结论回到界面的那一下，显式切主线程。顺带把这条路径的"
                    + "每个回调都过了一遍，只有这一处漏了。\n\n"
                    + "· 另外加了崩溃记录：以后万一再闪退，打开诊断页就能看到上次崩在哪，"
                    + "不用你描述、我也不用猜。记录只有一份、只存栈和版本号，"
                    + "不含任何地址或账号信息。\n\n"
                    + "· 其它功能和 1.9.0 完全一样。";
        }
        if ("1.9.0".equals(ver)) {
            return "本次更新：诊断页可以一键验证了\n\n"
                    + "· 以前要手工来两趟：去网页取一个挑战码，粘进来，点导出，"
                    + "再把一大段 PEM 复制给服务端。现在点一下就行——App 自己取码、"
                    + "自己申请证书、自己提交，结论直接显示在按钮上。\n\n"
                    + "· 那个挑战码输入框去掉了。它在的时候，贴错一格服务端就报"
                    + "「挑战码不匹配」，看起来像设备不支持，实际上只是复制漏了字符。"
                    + "这种错最难查，不如不让它发生。\n\n"
                    + "· 长按按钮还是老行为：只导出证书链到剪贴板，不联网。"
                    + "断网、或者想拿同一张链去离线比对时用这条。\n\n"
                    + "· 验证失败不影响任何功能。这不是登录门禁，只是一次自检——"
                    + "连不上服务端、或者设备本身不支持硬件认证，都照常用。\n\n"
                    + "· 服务端地址写在代码里（attest.flaros.xyz）。换成别的地址要等我发新版本，"
                    + "这是故意的：远程下发的地址本身就是一个可以被改的入口。";
        }
        if ("1.8.9".equals(ver)) {
            return "本次更新：证书可以绑定服务端下发的挑战码\n\n"
                    + "· 诊断页多了一个输入框。把服务端给的挑战码粘进去，"
                    + "再点导出，生成的证书里就带着这个数。\n\n"
                    + "· 这一步是为了防重放：不带它，一张旧证书可以反复提交给服务端，"
                    + "服务端没法判断是不是这一次新造的。带上它，服务端一比对就知道。\n\n"
                    + "· 输入框留空还是老行为——本地随机生成，适合离线看结论。"
                    + "填了但不合法（不是 64 位十六进制）会直接提示，不会悄悄截断。\n\n"
                    + "· 导出文本多一行 challenge 来源和值，方便人工核对。";
        }
        if ("1.8.8".equals(ver)) {
            return "本次更新：修正导出证书链时注脚里的机型\n\n"
                    + "· 上一版导出的文本第二行写着「设备 iPhone 14 / iOS / 390x844」。"
                    + "那行取的是浏览器伪装身份，不是你手机的真实型号。"
                    + "证书本身没问题，但注脚和内容对不上，发给别人看会被当成假数据。\n\n"
                    + "· 现在改成读系统里的真实机型（品牌 + 型号 + Android 版本）。"
                    + "伪装身份只用在网页那边，不该混进这种要拿去核对的东西里。\n\n"
                    + "· 顺带在注脚里补了一行包名和 versionCode，"
                    + "后端比对签名时可以少猜一次。\n\n"
                    + "· 探测逻辑、诊断页布局、其它功能都没动。";
        }
        if ("1.8.7".equals(ver)) {
            return "本次更新：修诊断页的按钮被内容顶出屏幕\n\n"
                    + "· 现象：打开诊断信息，只看到一路到「字体缩放：100%」，"
                    + "下面直接就是关闭按钮。新增的导出按钮和右下角的「技术细节」都不见了。\n\n"
                    + "· 原因：诊断文本是直接塞进对话框的，没有任何高度限制。"
                    + "下载日志一长，文本就把下面所有东西推出可视区。"
                    + "不是按钮没创建，是它排在了屏幕外面。\n\n"
                    + "· 处理：把诊断文本单独放进一个最高半屏的滚动区，"
                    + "按钮和「技术细节」放在滚动区外面。这样日志再长，"
                    + "按钮也钉在原地。文字比半屏短的时候，滚动区会自动收回去，不留空档。\n\n"
                    + "· 顺带一提：之前几版的「技术细节」其实也有同样的毛病，"
                    + "只是它一直在右下角，被顶出去的时候没人注意。现在一并修好。\n\n"
                    + "· App 的其它功能一行没动。";
        }
        if ("1.8.6".equals(ver)) {
            return "本次更新：诊断页多了一个「导出 Key Attestation 链」按钮\n\n"
                    + "· 这个按钮不修 Bug，也不加功能，是用来做一次实测的："
                    + "向手机的 Keystore 要一张由硬件背书（TEE）签发的证书，"
                    + "看它肯不肯把「这个包是谁签的」写进证书里。\n\n"
                    + "· 为什么这事值得做：你之前想上 Play Integrity 做云端校验，"
                    + "但它要 GMS、要谷歌账号、要联网，国行机基本没戏。"
                    + "而 Android 自带的密钥认证不用这些——它由手机的 TEE 直接签名，"
                    + "证书里会带上当前安装包的签名摘要。重签过的包，这一项对不上。"
                    + "对侧载分发来说，它比 Play Integrity 更准。\n\n"
                    + "· 但有一个前提我不确定：很多机型只写「应用包名」，"
                    + "不写「签名摘要」。写不写取决于 Keymaster 版本。"
                    + "这件事只能在真机上试，所以加了这颗按钮。\n\n"
                    + "· 点一下，它会做三件事：生成一把临时密钥（带随机 challenge）、"
                    + "读回证书链、在证书里搜 challenge 和本包签名摘要。"
                    + "结论直接显示在按钮上（例如「可用 · TEE · challenge 匹配 · 签名摘要 已写入」），"
                    + "整条证书链同时复制到剪贴板。\n\n"
                    + "· 密钥用完立刻删掉，不留在手机里。导出的文本只含证书和一段说明，"
                    + "里面有机型、系统版本和签名摘要——只发给你自己的服务端。\n\n"
                    + "· 如果显示「不可用」，说明这台设备的 Keystore 没接硬件认证，"
                    + "云端校验就得退回到普通签名校验那一档，别指望它。\n\n"
                    + "· App 的其它功能一行没动。";
        }
        if ("1.8.5".equals(ver)) {
            return "本次更新：加固外壳的身份标识改造（功能零改动）\n\n"
                    + "· 换了三处标记：加固 so 的文件名、日志标签、加密容器的文件头。"
                    + "原来这三个都是开源壳的默认值，任何一个都能直接指向「这是 Xop」。\n\n"
                    + "· 为什么值得改：网上针对这类壳的一键脱壳脚本，第一步就是按文件名找 so、"
                    + "按文件头识别容器、按 logcat 标签抓日志。默认值一个不改，"
                    + "等于把说明书贴在门上。改完能挡住「照着现成脚本跑一遍」的人，"
                    + "但挡不住真正动手分析的人——壳的加密逻辑没有变，这是实话。\n\n"
                    + "· App 自身的代码一行没动。加密存档、下载、界面都与 1.8.4 相同。\n\n"
                    + "· 如果这一版启动异常，直接装回 1.8.4，数据通用。";
        }
        if ("1.8.4".equals(ver)) {
            return "本次更新：修好加密流下载（加密视频下到一半报错）\n\n"
                    + "· 现象：某些站点的视频是 AES-128 加密的 HLS，下载第一段就失败，"
                    + "诊断日志里是「第 1 个分片解密失败：byte[] offset=-15 length=1 dst.length=16」。"
                    + "其它视频不受影响。\n\n"
                    + "· 原因：1.8.3 把下载器的解析层整体做了指令级虚拟化，"
                    + "其中「按分片序号推算初始向量」那段，本该生成 16 字节的数组，"
                    + "翻译后实际只有 1 字节。长度不够，解密库直接拒收。"
                    + "那段代码只有几十个字节——说明出问题的不是方法太大，"
                    + "而是数组创建这类指令本身没翻译对。\n\n"
                    + "· 处理：下载器整体撤出虚拟化，回到普通的 dex 加密；"
                    + "嗅探规则和两个网页接口照旧保留。另外给初始向量加了一道长度校验，"
                    + "以后再有类似问题，日志会直接说「IV 长度异常（N 字节，应为 16）」，"
                    + "不用再从一串天书里反推。\n\n"
                    + "· 结论修正：上次定的「500 字节以下就安全」不成立。"
                    + "大小只是必要条件，不是充分条件。";
        }
        if ("1.8.3".equals(ver)) {
            return "本次更新：加固范围调整（功能没有变化）\n\n"
                    + "· 这次把下载器的解析层（MediaDownloader）和嗅探规则（MediaSniffer）"
                    + "纳入指令级虚拟化。直链识别、m3u8 变体挑选、TS 的 AES-128 解密"
                    + "都在这两个类里，是「破解下载逻辑」真正要动的地方。\n\n"
                    + "· 但不是整类塞进去。打包器现在有一条硬线：方法代码超过 500 字节就不虚拟化，"
                    + "原样留在已加密的 dex 里。1.8.0 那次出事的方法有三千多字节，"
                    + "翻成自定义指令流之后不是崩溃，而是静默没反应——这种失败最难查，"
                    + "所以先用大小把风险圈起来。\n\n"
                    + "· 另外加了两个构建期脚本：一个统计每个方法的大小（含有没有异常表、"
                    + "有没有 synchronized、有没有 lambda），一个把「原始类名白名单」"
                    + "自动翻译成当前版本的混淆名。后者是必需的——R8 每次构建都会重新分配"
                    + "混淆名，写死的话下个版本就静默失效。\n\n"
                    + "· 加密存档、下载调度、界面都没动。如果下载异常，"
                    + "诊断页的「最近下载日志」仍然能看到每个环节。";
        }
        if ("1.8.2".equals(ver)) {
            return "本次更新：诊断信息分两级，日志里的地址脱敏\n\n"
                    + "· 原来那一屏是技术全放：算法名、系统版本、WebView 版本、完整播放地址，"
                    + "一打开全在。现在默认只显示「能不能用」——版本、安全状态、"
                    + "身份数量、通知权限、最近下载日志。\n\n"
                    + "· 要看成套技术细节，连点右下角「技术细节」七次。"
                    + "这一步拦不住真心想逆向的人（dex 就在包里，反编译什么都能看），"
                    + "但能拦住「随手截个图发出去」。\n\n"
                    + "· 更要紧的其实是另一件事：上一版的下载日志把完整地址打出来了，"
                    + "而那种直链的 query 里带的是 token / sign / expires，"
                    + "一个当场就能播的凭证。截图发给别人，等于把链接一起给出去了。"
                    + "现在写日志时就去掉全部查询参数，只留域名和路径。\n\n"
                    + "· 已经写进日志文件的历史记录不会自动清理，"
                    + "下次下载时新的记录会把它们挤出最近 12 行；"
                    + "想立刻清掉，在手机设置里清除应用数据即可（会一起清掉身份，不建议）。";
        }
        if ("1.8.1".equals(ver)) {
            return "本次更新：修视频下载「只弹通知、没有下文」\n\n"
                    + "· 你遇到的现象：点下载，通知栏出现「幻豆 · 视频下载」，"
                    + "然后就没了，文件也没落地。图片下载正常。\n\n"
                    + "· 最可疑的一环是 1.8.0 新加的指令级虚拟化。下载服务的十三个方法"
                    + "（含启动、调度、写文件那几段）当时全被翻译成了自定义指令流，"
                    + "而图片走的是另一条路（JsBridge），没经过它。所以「图片行、视频不行」"
                    + "这个现象，正好卡在两者的分界上。\n\n"
                    + "· 这个版本把下载服务整体退出虚拟化，回到普通的 dex 加密；"
                    + "两个网页桥（JsBridge / UserscriptBridge）保持虚拟化不变，"
                    + "它们已经用图片下载验证过了。\n\n"
                    + "· 同时补了下载日志：服务的每个关键节点都会写一行到本机文件——"
                    + "收到启动没有、任务有没有入队、网络拿到多少字节、保存到哪、抛了什么异常。"
                    + "诊断信息里新增「最近下载日志」，会显示最后 12 行。"
                    + "以后再出现「点了没反应」，截这一块就够了，不用再猜。\n\n"
                    + "· 如果这个版本下载仍然失败，说明问题不在虚拟化。"
                    + "请把诊断信息里的「最近下载日志」发我，那里会写清楚断在哪一步。";
        }
        if ("1.8.0".equals(ver)) {
            return "本次更新：加固（功能没有变化）\n\n"
                    + "· 功能一行没改。不加固的那份 1.8.0 和 1.7.9 除了版本号完全一样，"
                    + "已经在用 1.7.9 的话，装不装都行；真正有意义的是 -xop 那个包。\n\n"
                    + "· 除了原来的整包 dex 加密，这次把三个类的字节码也做成了"
                    + "指令级虚拟化（Xop 的 True-VMP）：下载调度 DownloadService、"
                    + "网页桥 JsBridge、脚本桥 UserscriptBridge。它们的方法体不再以 Dalvik "
                    + "指令的形式躺在 dex 里，而是被翻译成自定义指令流，由壳里的解释器执行。"
                    + "拿 jadx 打开这几个类，看到的是空方法或者一堆数字表。\n\n"
                    + "· 为什么只挑这三个：网页能直接摸到的入口就是它们。"
                    + "改包的人想插手下载、想截走脚本请求，都得改这几个类。\n\n"
                    + "· 为什么不动加密那块（VaultCrypto）：它的结果直接决定你已有的存档能不能解开。"
                    + "虚拟化万一有一处语义偏差，表现不是崩溃，是「解出来是乱码」——"
                    + "收益很小，代价是你 3 个身份全没了。不划算，不做。\n\n"
                    + "· 兼容性：方法若超出虚拟机的约束（寄存器超 32 个、字节码超 512 单元、"
                    + "带不支持的指令），打包器会自动退回普通加密，不会硬塞。"
                    + "所以不必担心某个方法被改坏。\n\n"
                    + "· 如果加固包启动闪退或页面白屏，直接换回 1.7.9 普通版，"
                    + "两者数据完全通用。";
        }
        if ("1.7.9".equals(ver)) {
            return "本次更新：修掉诊断页那句「自检未通过」\n\n"
                    + "· 你看到的现象：1.7.7 诊断信息里「本地加密」后面写着「自检未通过」，"
                    + "1.7.8 再看又变成「自检通过」。两个版本的加密代码一行都没差，"
                    + "所以这跟版本无关，是个概率问题——大约每四次打开诊断页会有一次报失败。\n\n"
                    + "· 原因不在加密，在自检的测法。自检最后一步要验证「密文被改动会解不开」："
                    + "以前是随手把密文 Base64 的倒数第三个字符在 A 和 B 之间换一下。"
                    + "但密文 43 字节，Base64 之后末尾是 xx==，"
                    + "倒数第三个字符只有高 2 位有效、低 4 位是填充位，"
                    + "而 A 和 B 的高 2 位一模一样——换了等于没换，密文一个字节没变，"
                    + "当然解得开，于是判成「未通过」。那个位置只可能是 A/Q/g/w 四个字符，"
                    + "轮到 A 就翻车，概率正好四分之一。\n\n"
                    + "· 现在改成直接翻转密文最后一个字节的最低位，"
                    + "改没改到是确定的，不再看运气。\n\n"
                    + "· 你机器上已经加密的数据不受影响，密钥和 IV 都没动，照常解。";
        }
        if ("1.7.8".equals(ver)) {
            return "本次更新：大视频也能转 MP4 了\n\n"
                    + "· 之前是这么卡的：转换前先把整个 TS 读进内存，所以硬设了 200MB 上限，"
                    + "超过就直接跳过、存成 .ts。更麻烦的是 100～200MB 的文件，"
                    + "在内存小一点的机器上会因为读不进去而失败，一样退回 .ts，"
                    + "而从外面看只是「这次没转」。\n\n"
                    + "· 现在改成按块读取：内存固定占 192KB，跟视频多大没有关系。"
                    + "TS 是 188 字节的定长包，按 188 的整数倍切窗口，包不会被切断。\n\n"
                    + "· 上限抬到 2GB，而且不再是「内存能不能装下」，而是缓存目录还剩多少。"
                    + "转换期间目录里要同时放原始 TS、音视频临时流和输出 MP4，"
                    + "大约是源文件的两倍。\n\n"
                    + "· 空间确实不够时会给准数：「缓存空间不足（还需要约 xxx MB），"
                    + "已保留原始 TS」，而不是含糊地报个错误。\n\n"
                    + "· 已经下好的 .ts 不会自动重转——那些文件当时就是按 TS 存的，"
                    + "想换成 MP4 需要重新下一次。";
        }
        if ("1.7.7".equals(ver)) {
            return "本次更新：本地数据加密\n\n"
                    + "· 身份存档（整条 Cookie、站点 localStorage）和用户脚本正文，"
                    + "以前是明文躺在自己的目录里，拿到备份文件就能直接读。"
                    + "现在落盘一律加密：AES-256-GCM。\n\n"
                    + "· 为什么是 GCM 不是 CBC：CBC 只管藏，不管改。密文被人动过一个字节，"
                    + "解密照样吐出一段错误的东西，程序无从判断。GCM 带 128 位认证标签，"
                    + "对不上就直接拒绝——被改过的 Cookie 灌不回网页。\n\n"
                    + "· 密钥不是写死的一个常量。它由本机首次运行生成的随机种子，"
                    + "加上安装包签名指纹，过 HKDF-SHA256 派生出来。所以把数据文件拷到"
                    + "另一台手机、或者把包重签后带走，都解不开。\n\n"
                    + "· 每条数据用独立的随机 IV，并把「这条叫什么」绑进认证范围。"
                    + "把 A 身份的存档搬到 B 身份下面，同样解不开。\n\n"
                    + "· 旧数据自动迁移：第一次读到明文存档 / 明文脚本时，顺手加密写回，只做一次，"
                    + "你不会感觉到任何变化。\n\n"
                    + "· 沉浸模式右下角那个「✕ 退出沉浸」悬浮按钮去掉了，它压着网页底部的"
                    + "导航栏。退出改成按返回键，或者到菜单里关。\n\n"
                    + "· 诊断信息里多一行「本地加密」，会显示算法和一次自检结果。";
        }
        if ("1.7.6".equals(ver)) {
            return "本次更新：签名校验\n\n"
                    + "· 启动第一步先验安装包签名。不是原始密钥签出来的包，"
                    + "到这里就停，WebView 不启动、Cookie 不读、脚本引擎不跑——"
                    + "重签一个包装上去，打开只会看到一页说明和两个指纹。\n\n"
                    + "· 校验点不止一处：启动时一次，下载服务开工前一次，"
                    + "用户脚本发请求前一次。脚本那个口子最要紧，"
                    + "它默认会带上当前网站的 Cookie，被改过的包从这里发不出去。\n\n"
                    + "· 签名对不上时不会闪退，会告诉你哪一步不对："
                    + "页面上并排列出「内置指纹」和「实际指纹」，"
                    + "自己换过密钥重打包的话，一眼能看出是不是这个原因。\n\n"
                    + "· 说清楚它的边界：这是 Java 层的判断，专门逆向的人能改掉。"
                    + "它挡的是「反编译塞点东西再重签分发」这种做法，不是加固。";
        }
        if ("1.7.5".equals(ver)) {
            return "本次更新：代码混淆与裁剪\n\n"
                    + "· 构建链里的 d8 换成了 R8，并开启 Full Mode：类名、方法名、字段名全部改名，"
                    + "没被调用到的类和方法直接删掉。dex 从 416 KB 降到 306 KB，"
                    + "类数量 261 → 115，方法数 2365 → 1450，安装包 420 KB → 375 KB。\n\n"
                    + "· 只保留了三类不能改名的东西：Manifest 里写的入口组件、"
                    + "15 个 @JavascriptInterface 方法（网页里按名字调用），"
                    + "以及 WebViewClient / WebChromeClient 的回调。其余全部放开混淆。\n\n"
                    + "· 为什么 JS 桥要单独保：WebView 靠反射读方法上的注解来判断谁能被网页调用，"
                    + "注解一旦被裁，保存、下载进度、油猴接口会静默失效，不报错但没反应。"
                    + "这一条专门做过验证：混淆前后都是 15 个方法，名字一字不差。\n\n"
                    + "· 混淆后崩溃栈仍然带源文件名和行号，出问题还能定位。";
        }
        if ("1.7.3".equals(ver)) {
            return "本次更新：顶栏按钮\n\n"
                    + "· 顶栏原来右边的「身份」「新身份」换成了「回主页」「刷新」。"
                    + "浏览时最常用的是回首页和重试，身份不是每次都要动，"
                    + "放到菜单第一项「身份管理 / 新建 / 切换…」里，功能一个没少。\n\n"
                    + "· 「刷新」在出错页面也能用：按下去会重新请求那个地址，"
                    + "不是只在已经加载好的页面上做一次重绘。\n\n"
                    + "· 「回主页」点标题「幻豆」仍然是同一个动作，两个入口都留着。";
        }
        if ("1.7.2".equals(ver)) {
            return "本次更新：加载失败页\n\n"
                    + "· 失败页重做了：会写明是哪一步出的问题（解析不到域名 / 连不上 / 超时 / 证书），"
                    + "并把失败的那个网址显示出来。\n\n"
                    + "· 加了网络建议。像 GreasyFork、Google 这类平时就要用代理才能开的站，"
                    + "会直接说「需要先开 VPN」而不是让你反复点重试。\n\n"
                    + "· 除了「重新加载」，多了一个「回首页」。\n\n"
                    + "· 彩蛋：失败页底部有只小恐龙，点进去可以玩跳仙人掌，"
                    + "点屏幕起跳，撞上就结束，最高分记在本机。\n\n"
                    + "· 用户脚本面板底部的说明压成一行，不再是一大段。";
        }
        if ("1.7.1".equals(ver)) {
            return "本次更新：搜索入口与脚本库\n\n"
                    + "· 顶栏加了一个放大镜按钮，点开是搜索面板：输入关键词就用选中的引擎搜"
                    + "（百度 / 必应 / Google / DuckDuckGo，点一下切换），"
                    + "输入网址就直接访问。\n\n"
                    + "· 判断偏保守：带空格的一律当搜索词，不会把「某剧 第2集」当成域名。\n\n"
                    + "· 「打开 GreasyFork」不再跳到系统浏览器，改成在幻豆里直接逛。"
                    + "脚本页点「安装此脚本」会被拦下来，直接进安装确认，不用先下载成文件。\n\n"
                    + "· 菜单里的「用系统浏览器打开」还在，需要时仍可丢给外部浏览器。";
        }
        if ("1.7.0".equals(ver)) {
            return "本次更新：用户脚本（油猴）\n\n"
                    + "· 菜单新增「🐵 用户脚本」，可以直接装 .user.js：去广告、视频倍速、"
                    + "网盘直链解析这类脚本都能跑。\n\n"
                    + "· 三种安装方式：贴 GreasyFork 脚本页地址（自动从页面抠源码）、"
                    + "粘贴全文、在脚本安装页一键读取。\n\n"
                    + "· 兼容 Tampermonkey 的元数据：@match / @include / @exclude / "
                    + "@run-at / @require / @grant；GM_getValue、GM_setValue、"
                    + "GM_xmlhttpRequest、GM_addStyle、GM_notification 等常用接口都有。\n\n"
                    + "· document-start 的脚本会用 HTML 改写保证在站点脚本之前执行；"
                    + "嫌每页多一次请求，可在面板里关掉「首帧注入」。";
        }
        if ("1.6.9".equals(ver)) {
            return "本次更新：两处菜单整理\n\n"
                    + "· 「自动转 MP4（无损封装）」的开关去掉了。HLS 下载拿到的是一堆 ts 分片，"
                    + "不封装成 mp4，很多播放器和相册都打不开——这是下载的收尾步骤，不是可选项。"
                    + "之前误触关掉的人会得到一堆放不了的 ts，所以现在固定开启，"
                    + "旧版本里存成「关」的也会自动纠正回来。\n\n"
                    + "· 菜单里的「随机新建身份」删掉了，它和身份面板里的新建是同一个动作，"
                    + "同一个功能摆两遍，反而容易点错。\n\n"
                    + "· 「批量生成随机身份」也收进了身份面板，菜单第一项改为"
                    + "「身份管理 / 新建 / 切换…」：新建、切换、批量生成、删除，一个面板全办完。";
        }
        if ("1.6.8".equals(ver)) {
            return "本次更新：安全浏览重做\n\n"
                    + "· 本地威胁情报库。把 Phishing Army（聚合 PhishTank、OpenPhish、urlscan.io "
                    + "等 20 多家）、URLhaus、OpenPhish 的名单整包下到手机，十几万条域名在本地比对。"
                    + "不是每次访问都去问服务器——情报源根本收不到你的浏览请求，也就无从记录你看了什么。\n\n"
                    + "· 多维度评分。域名年龄（RDAP）、punycode 与西里尔字母伪装、品牌词一字之差、"
                    + "IP 直连加非常规端口、短链接、直接下载安装包，都会累加风险值。\n\n"
                    + "· 页面结构分析。密码框把内容提交到另一个域名、隐藏 iframe、打开就跳转、"
                    + "脚本混淆、用「手机中毒」诱导安装、有没有 ICP 备案号。\n\n"
                    + "· 三档处理：命中情报库 → 整页拦截；高危 → 弹窗确认；只是有点可疑 → "
                    + "顶部提示条，不打断你正在看的内容。\n\n"
                    + "· 拦截页有 5 秒冷静期，之后才出现「仍然访问」；也可以一键加入白名单。\n\n"
                    + "· 菜单「安全浏览」现在是设置面板：情报库条数与更新时间、立即更新、"
                    + "严格 / 提示切换、域名年龄开关、自建代理前缀、白名单与自定义黑名单。";
        }
        if ("1.6.7".equals(ver)) {
            return "本次更新：新增一组隐私与阅读工具\n\n"
                    + "· 无痕模式：不接收 Cookie（含第三方），开启时自动打开指纹伪装与 "
                    + "Canvas 扰动，退出应用即清空缓存、历史与本地存储。\n\n"
                    + "· 强制深色：所有网页反色成黑底白字，图片和视频会二次反色，不会变成负片。"
                    + "开一次以后自动生效。\n\n"
                    + "· 阅读模式：一键提取正文，只留文字，可调出系统语音朗读，也能复制全文。\n\n"
                    + "· 书签：收藏当前页、删除、导入导出。导出的 bookmarks.json 在 下载/Huandou，"
                    + "浏览器导出的书签 HTML 也能直接导入。\n\n"
                    + "· 安全浏览：访问仿冒域名、IP 直连、安装包下载等可疑地址时先提示，"
                    + "确认后再进。规则全在本地，不上传任何浏览记录。";
        }
        if ("1.6.6".equals(ver)) {
            return "本次更新：\n\n"
                    + "· 嗅探不再只管视频。图片（含 srcset 里的多倍图）和音频也会被捕捉，"
                    + "封面、剧照、背景音乐都能直接保存。\n\n"
                    + "· 面板顶部多了「视频 / 音频 / 图片」三个筛选，默认停在视频，"
                    + "右侧数字是本类已抓到的数量。\n\n"
                    + "· 列表里不再出现单个 ts 分片——以前一集能刷出几十条同名项，"
                    + "把真正的整集挤到看不见。\n\n"
                    + "· 保存时按类型给扩展名与 MIME：图片存 jpg/png/webp，音频存 mp3/m4a，"
                    + "不再一律当视频写成 .ts。\n\n"
                    + "· 菜单里的「视频嗅探」改名为「媒体嗅探」。";
        }
        if ("1.6.5".equals(ver)) {
            return "本次更新：\n\n"
                    + "· 修好了「热门短剧随心看」这种站点标语被当成片名的问题。"
                    + "现在优先取紧挨着「第N集」标记的那段文字——那才是正在播的这部剧；"
                    + "首页横幅上的宣传语离集数很远，不会再被选中。\n\n"
                    + "· 重命名会被记住。比如把「点过你爸 第1集」改成「床笫 第1集」，"
                    + "下一集自动叫「床笫 第2集」；页面识别不到集数时，就在上次集数上 +1。"
                    + "只有确认还是同一部剧才沿用，换剧不会串名。\n\n"
                    + "· 重命名输入框加了描边框，一眼能看出哪里可以改。";
        }
        if ("1.6.4".equals(ver)) {
            return "本次更新：\n\n"
                    + "· 下载的文件名不再叫 v1。短剧站的播放地址里常带 /v1/ 或 ?name=v1，"
                    + "旧逻辑把「v1」当成了片名，于是每集都存成 v1.mp4，分不清是哪部剧的第几集。\n\n"
                    + "· 现在会从页面里读剧名和集数：页面标题、og:title、页面上的标题元素、"
                    + "播放器标题都比对一遍，挑最像剧名的那条，再补上「第N集」——"
                    + "例如「床第 第1集.mp4」。\n\n"
                    + "· 单页应用切集不刷新页面时标题也会跟着更新（开始播放、换集后自动重抓）。\n\n"
                    + "· 视频嗅探列表里的单个分片会标出「第12段」，不再一列全是同一个名字。\n\n"
                    + "· 同名文件不再互相覆盖：重名会自动加 (2)(3)。";
        }
        if ("1.6.3".equals(ver)) {
            return "本次更新：\n\n"
                    + "· 「下载记录」修好了。之前这一页查的是系统的 DownloadManager，"
                    + "而视频嗅探的下载走的是应用自己的后台服务，两条路互不相干，"
                    + "所以文件明明已经下到 下载/Huandou，页面却一直显示「暂无下载记录」。\n\n"
                    + "· 现在直接查手机里 下载/Huandou 的真实文件：显示文件名、大小、下载时间，"
                    + "点一下就播放；「打开文件夹」可以直接跳到那个目录。\n\n"
                    + "· 之前几版下载过的视频也会出现在列表里（文件在就一定能看到）。\n\n"
                    + "· 下载中的任务仍不在此列表——只有真正写入文件后才会出现；"
                    + "失败的任务会在通知栏保留失败原因。";
        }
        if ("1.6.2".equals(ver)) {
            return "本次更新：\n\n"
                    + "· 菜单里的「退出」删掉了。它和系统的侧滑返回 / 返回键完全重复，"
                    + "而且点了只是关掉界面，正在下载的任务照样在后台跑。\n\n"
                    + "· 退出应用就是系统那套手势：返回到首页后再按一次返回（两秒内连按两次）即可退出。\n\n"
                    + "· 菜单条目整体上移一位，其他功能位置不变、行为不变。";
        }
        if ("1.6.1".equals(ver)) {
            return "本次更新：\n\n"
                    + "· 去掉了重复的下载进度显示。之前同一个进度会出现三次：通知栏一条、"
                    + "界面底部一个小条、还有一个「已下载 15/22（52%）」的弹窗提示。"
                    + "现在只要通知能正常显示，界面上就不再出现任何进度提示，只留通知栏那一条。\n\n"
                    + "· 只有通知被系统关掉 / 没给权限时，才退回界面底部的小条和文字提示，"
                    + "免得彻底看不到进度。\n\n"
                    + "· 下载开始、进度、完成、失败，统一以通知栏为准；完成后点通知可直接播放。\n\n"
                    + "· 去掉了菜单里的「通知自检」——既然通知已经正常，这个入口没用了。"
                    + "诊断信息里仍然保留通知权限 / 渠道状态，出问题时还能看。";
        }
        if ("1.6.0".equals(ver)) {
            return "本次更新（重点：通知栏）：\n\n"
                    + "· 找到视频后的操作面板重做。上一版的按钮列表在部分机型上会被压成 0 高，"
                    + "屏幕上只剩标题、网址和一个「关闭」，根本点不到「下载」——这也是「点了没反应、"
                    + "也没通知」的直接原因。现在按钮由应用自己绘制，一定能点到。\n\n"
                    + "· 通知改为「立即显示」。Android 12 起，系统的前台服务通知默认会延后 10 秒才出现；"
                    + "下载往往几秒就结束，通知还没出现任务就完了。现已改为立即显示。\n\n"
                    + "· 通知渠道重建：重要性恢复为「默认」并带提示音。之前的渠道是「无声 + 低重要性」，"
                    + "小米 / OPPO 等系统会把它归到不重要通知里折叠掉，状态栏看不到图标。\n\n"
                    + "· 诊断信息里会显示通知权限、通知开关、通知渠道重要性，"
                    + "被系统折叠时能一眼看出来。\n\n"
                    + "· 下载一开始就在界面底部显示进度条，不依赖通知；失败时通知会保留原因，"
                    + "不会再一闪而过。\n\n"
                    + "· 下载确认不再弹八段长文说明，文件名、保存位置、进度在哪看，一屏说完。";
        }
        if ("1.5.6".equals(ver) || "1.5.7".equals(ver)) {
            return "本次更新：\n\n"
                    + "· 修复视频下载失败。上一版接入「应用层 TLS 指纹伪装」后，读取响应时会一直等到"
                    + "填满调用方的缓冲区才返回，遇到 keep-alive 的长连接就永远等不到，"
                    + "表现为进度卡在 0% 后读超时。现已改为有数据立即返回。\n\n"
                    + "· 下载链路继续走伪装 TLS：ClientHello 的套件顺序、扩展顺序（含 extended_master_secret）"
                    + "都按 Chrome 对齐；站点只支持 TLS 1.3 时自动回退系统 TLS，不会断流。\n\n"
                    + "· 后台下载服务启动加了兜底：个别机型上前台服务启动抛异常会导致"
                    + "「点了下载既没有通知、也没有文件」，现在会降级继续，不再整体失败。\n\n"
                    + "· 更新说明改为按版本号触发，以后每次升级都会弹一次；"
                    + "设置页标题和诊断信息里的版本号也改为读取真实包版本，不再写死。";
        }
        return "本次更新：修复与优化若干细节。\n\n当前版本 " + ver
                + "（versionCode " + appVersionCode() + "）。";
    }

    private void setupWindow() {
        getWindow().setBackgroundDrawable(new ColorDrawable(BRAND));
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BRAND);
            getWindow().setNavigationBarColor(BRAND);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void applyPreferences() {
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(webDebug);
        // 无痕状态要跟着 WebView 走：新建 WebView、切换身份后同样不接收 Cookie。
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(!incognito);
            if (web != null) cm.setAcceptThirdPartyCookies(web, !incognito);
        } catch (Throwable ignored) {
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BRAND);
        setContentView(root, new FrameLayout.LayoutParams(MP, MP));

        mainBox = new LinearLayout(this);
        mainBox.setOrientation(LinearLayout.VERTICAL);
        mainBox.setBackgroundColor(BRAND);
        root.addView(mainBox, new FrameLayout.LayoutParams(MP, MP));

        toolbar = buildToolbar();
        mainBox.addView(toolbar, new LinearLayout.LayoutParams(MP, dp(48)));

        progressLine = new View(this);
        progressLine.setBackgroundColor(ACCENT);
        progressLine.setVisibility(View.GONE);
        mainBox.addView(progressLine, new LinearLayout.LayoutParams(0, dp(2)));

        // 公告条放在工具栏和网页之间：不遮挡页面内容，也不会被网页的滚动带走。
        // 它只在服务端下发公告时出现，平时是 GONE，不占高度。
        noticeBar = buildNoticeBar();
        noticeBar.setVisibility(View.GONE);
        mainBox.addView(noticeBar, new LinearLayout.LayoutParams(MP, LinearLayout.LayoutParams.WRAP_CONTENT));

        pull = new PullRefreshLayout(this);
        pull.setScrollCheck(new PullRefreshLayout.ScrollCheck() {
            @Override
            public boolean canScrollUp() {
                return !mWebAtTop || (web != null && web.getScrollY() > 0);
            }
        });
        pull.setOnRefreshListener(new PullRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                hideError();
                web.reload();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        pull.finishRefresh();
                    }
                }, 9000);
            }
        });

        web = new WebView(this);
        pull.addView(web, new FrameLayout.LayoutParams(MP, MP));
        pull.setTarget(web);
        mainBox.addView(pull, new LinearLayout.LayoutParams(MP, 0, 1f));

        buildSplash();
        buildErrorView();

        fullscreenHost = new FrameLayout(this);
        fullscreenHost.setBackgroundColor(Color.BLACK);
        fullscreenHost.setVisibility(View.GONE);
        root.addView(fullscreenHost, new FrameLayout.LayoutParams(MP, MP));

        // 沉浸模式不再挂常驻悬浮按钮：它压住网页右下角，还会跟着页面滚动留在屏上。
        // 退出方式保留两个——返回键、菜单里的「沉浸模式」开关。

        // Apply the unified z-order once every native layer exists.
        pull.setZ(Z_WEB);
        web.setZ(Z_WEB);
        toolbar.setZ(Z_TOOLBAR);
        toolbar.setElevation(dp(3));
        fullscreenHost.setZ(Z_FULLSCREEN);
        if (splash != null) splash.setZ(Z_SPLASH);
        if (errorView != null) errorView.setZ(Z_OVERLAY);
        if (findBar != null) findBar.setZ(Z_OVERLAY);

        applyImmersive(immersive);
    }

    private volatile boolean mWebAtTop = true;

    private LinearLayout buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(dp(12), 0, dp(6), 0);

        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF9B7BFF, 0xFF5B3FA8});
        dg.setShape(GradientDrawable.OVAL);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dlp.setMargins(dp(2), 0, dp(9), 0);
        bar.addView(dot, dlp);

        TextView title = new TextView(this);
        title.setText("幻豆");
        title.setTextColor(0xFFF3EFFA);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(4), 0, dp(10), 0);
        title.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHome();
            }
        });
        bar.addView(title, new LinearLayout.LayoutParams(WC, MP));
        bar.addView(makeBarButton("🔍", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchPanel();
            }
        }));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, MP, 1f));

        bar.addView(makeBarButton("回主页", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goHome();
            }
        }));
        bar.addView(makeBarButton("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshPage();
            }
        }));
        bar.addView(makeBarButton("菜单", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        }));

        return bar;
    }

    /**
     * 搜索面板。顶栏只有一个放大镜按钮，点开才出现，不占页面的高度。
     * 输入网址就跳转，输入关键词就用选中的引擎搜。
     */
    private void showSearchPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(2), dp(18), dp(2));

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(0x2EFFFFFF);
        fb.setCornerRadius(dp(12));
        fb.setStroke(Math.max(1, dp(1)), 0x3DFFFFFF);
        field.setBackground(fb);
        field.setPadding(dp(12), 0, dp(12), 0);

        TextView glass = new TextView(this);
        glass.setText("🔍");
        glass.setTextSize(13);
        glass.setAlpha(0.8f);
        glass.setGravity(Gravity.CENTER);
        field.addView(glass, new LinearLayout.LayoutParams(dp(22), WC));

        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setTextSize(14);
        et.setTextColor(0xFFF3EFFA);
        et.setHintTextColor(0xFF9C90AC);
        et.setHint("搜索关键词，或直接输入网址");
        et.setBackground(null);
        et.setPadding(dp(8), 0, 0, 0);
        et.setGravity(Gravity.CENTER_VERTICAL);
        et.setIncludeFontPadding(false);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        field.addView(et, new LinearLayout.LayoutParams(0, dp(48), 1f));
        box.addView(field, new LinearLayout.LayoutParams(MP, WC));

        final TextView[] chips = new TextView[ENGINES.length];
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(12), 0, dp(2));
        for (int i = 0; i < ENGINES.length; i++) {
            final int idx = i;
            TextView c = new TextView(this);
            c.setText(ENGINES[i]);
            c.setTextSize(11.5f);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(10), dp(6), dp(10), dp(6));
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    engineIndex = idx;
                    pref.edit().putInt(KEY_ENGINE, engineIndex).apply();
                    paintEngineChips(chips);
                }
            });
            chips[i] = c;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WC, WC);
            clp.setMargins(0, 0, dp(6), 0);
            chipRow.addView(c, clp);
        }
        paintEngineChips(chips);
        box.addView(chipRow, new LinearLayout.LayoutParams(MP, WC));

        final Dialog dlg = showGlassD(new AlertDialog.Builder(this)
                .setTitle("搜索 / 访问")
                .setView(box)
                .setNegativeButton("取消", null));

        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                doSearch(dlg, et);
                return true;
            }
        });
        et.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    et.requestFocus();
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) {
                }
            }
        }, 180);
    }

    private void paintEngineChips(TextView[] chips) {
        if (chips == null) return;
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            boolean on = i == engineIndex;
            chips[i].setTextColor(on ? 0xFF1B1020 : 0xFFC9BEDD);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(9));
            g.setColor(on ? 0xFFB9A2FF : 0x1FFFFFFF);
            chips[i].setBackground(g);
        }
    }

    private void doSearch(Dialog dlg, EditText et) {
        String q = et.getText().toString().trim();
        if (q.length() == 0) {
            toast("输入关键词或网址");
            return;
        }
        if (dlg != null) dlg.dismiss();
        if (web != null) web.loadUrl(toUrlOrSearch(q));
    }

    /**
     * 判断该跳转还是该搜。刻意保守：只有明显是域名 / IP / 带协议头的才当网址，
     * 「床笫 第1集」这种带空格的一定走搜索。
     */
    private String toUrlOrSearch(String q) {
        String low = q.toLowerCase(Locale.US);
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("ftp://")) return q;
        if (q.indexOf(' ') < 0 && q.indexOf('\u3000') < 0) {
            String host = q;
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);
            int qm = host.indexOf('?');
            if (qm > 0) host = host.substring(0, qm);
            if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?")) return "http://" + q;
            if ("localhost".equals(host) || host.startsWith("localhost:")) return "http://" + q;
            if (host.matches("[^/:]+:\\d{1,5}")) return "https://" + q;
            // 普通后缀、punycode、中文后缀都算域名；「index.m3u8」这种仍当搜索词。
            if (host.matches(".+\\.[a-zA-Z]{2,6}$")
                    || host.matches(".+\\.xn--[a-zA-Z0-9]+$")
                    || host.matches(".+\\.[\\u4e00-\\u9fa5]{2,}$")) return "https://" + q;
        }
        return ENGINE_URLS[engineIndex] + Uri.encode(q);
    }

    /** 这个链接是不是一个用户脚本文件（GreasyFork 的安装按钮长这样）。 */
    private static boolean isUserScriptUrl(String url) {
        if (url == null || url.length() == 0) return false;
        String u = url.toLowerCase(Locale.US);
        int cut = u.length();
        int q = u.indexOf('?');
        if (q > 0) cut = q;
        int h = u.indexOf('#');
        if (h > 0 && h < cut) cut = h;
        return u.substring(0, cut).endsWith(".user.js");
    }

    private TextView makeBarButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFE6DDF5);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF35264A, 0xFF221630});
        g.setCornerRadius(dp(10));
        g.setStroke(Math.max(1, dp(1)), 0x2EFFFFFF);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC, WC);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * 服务端公告条。
     *
     * <p>为什么值得单独做一条：它是唯一天然带「时效」的下发内容。线路变更、临时维护、
     * 新版本发布，这些信息只在特定时间有意义，写死在包里发不出去。而重打包的包拿不到，
     * 用户就会慢慢发现「别人知道的事我不知道」——这比任何硬拦截都管用，也没有误伤。</p>
     */
    private LinearLayout buildNoticeBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF2A2340);
        bar.setPadding(dp(16), dp(9), dp(6), dp(9));

        noticeText = new TextView(this);
        noticeText.setTextSize(12);
        noticeText.setTextColor(0xFFE9E2F5);
        noticeText.setLineSpacing(0, 1.2f);
        noticeText.setMaxLines(3);
        noticeText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, WC, 1f);
        bar.addView(noticeText, tp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(13);
        close.setTextColor(0xFF8E86A3);
        close.setPadding(dp(12), dp(4), dp(10), dp(4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissNotice();
            }
        });
        bar.addView(close);
        return bar;
    }

    /**
     * 按当前配置决定公告条显不显示。主线程调用。
     *
     * <p>「关掉过就不再提」是按内容哈希记的，不是按时间——后端重新下发同一份配置时，
     * 用户不该把同一条公告再看一遍。</p>
     */
    private void applyNotice() {
        if (noticeBar == null || noticeText == null) return;
        String n = RemoteConfig.notice(this);
        String id = RemoteConfig.noticeId(this);
        boolean show = n.length() > 0 && id.length() > 0
                && !id.equals(pref.getString(KEY_RC_NOTICE, ""));
        if (show) noticeText.setText(n);
        noticeBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void dismissNotice() {
        pref.edit().putString(KEY_RC_NOTICE, RemoteConfig.noticeId(this)).apply();
        if (noticeBar != null) noticeBar.setVisibility(View.GONE);
    }

    /** 配置在后台线程拉到之后，切回主线程刷公告条。 */
    private void postApplyNotice() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyNotice();
            }
        });
    }

    /** 黑名单命中后给子资源的空响应。返回空内容比抛异常稳妥，页面不会卡在加载态。 */
    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8",
                new java.io.ByteArrayInputStream(new byte[0]));
    }

    /** 主框架被拦时的说明页。白屏会让人以为 App 坏了，这里至少能解释清楚。 */
    private static WebResourceResponse blockedPage(String host) {
        String h = host == null ? "" : host.replace("<", "&lt;").replace(">", "&gt;");
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{background:#15111f;color:#cfc7dd;"
                + "font:15px/1.75 -apple-system,'PingFang SC',sans-serif;padding:64px 30px}"
                + "h2{color:#e9e2f5;font-size:17px;font-weight:600;margin:0 0 14px}"
                + "p{color:#8e86a3;font-size:13px;margin:0 0 12px}"
                + "code{color:#f0a3c8;word-break:break-all}</style></head><body>"
                + "<h2>该页面已被拦截</h2>"
                + "<p><code>" + h + "</code></p>"
                + "<p>这个域名在服务端下发的拦截名单里。如果你认为拦错了，"
                + "可在「菜单 → 诊断」里清空名单后重试。</p>"
                + "</body></html>";
        return new WebResourceResponse("text/html", "utf-8",
                new java.io.ByteArrayInputStream(
                        html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * 常见统计 / 广告 / 归因域名后缀。
     *
     * <p><b>只用来在诊断页打标记，不参与拦截。</b>标记错了最坏情况是用户多看一眼，
     * 但自动拦错就是白屏，所以这里刻意只做提示。</p>
     *
     * <p>选得很保守：只放明确的统计和广告平台，不放 CDN、图片、播放源。
     * 一个字节跳动的图片域名和它的广告域名长得像，但拦了前者页面就废了。</p>
     */
    private static final String[] TRACKER_HINTS = {
            // 国内统计
            "hm.baidu.com", "pos.baidu.com", "cpro.baidu.com", "cnzz.com", "51.la", "51yes.com",
            "umeng.com", "umengcloud.com", "umtrack.com", "uyunad.com",
            // 崩溃/监控（不算广告，但同属「可考虑」）
            "bugly.qq.com", "crashlytics.com", "sentry.io",
            // 国内广告
            "gdt.qq.com", "tanx.com", "alimama.com", "mmstat.com",
            "pangolin-sdk-toutiao.com", "pangle.io", "mintegral.com",
            // 海外广告 / 归因
            "doubleclick.net", "google-analytics.com", "googletagmanager.com",
            "appsflyer.com", "adjust.com", "branch.io", "ironsrc.com",
            "vungle.com", "inmobi.com", "criteo.com", "taboola.com", "outbrain.com",
    };

    /** host 是否命中上面的参考表。纯字符串比对，不联网。 */
    private static boolean looksLikeTracker(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.US);
        for (int i = 0; i < TRACKER_HINTS.length; i++) {
            String t = TRACKER_HINTS[i];
            if (h.equals(t)) return true;
            if (h.length() > t.length() + 1 && h.endsWith(t)
                    && h.charAt(h.length() - t.length() - 1) == '.') return true;
        }
        return false;
    }

    private void buildSplash() {
        LinearLayout sp = new LinearLayout(this);
        sp.setOrientation(LinearLayout.VERTICAL);
        sp.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A0F26, 0xFF0A070D, 0xFF0A070D});
        sp.setBackground(gd);
        sp.setClickable(true);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int s = dp(92);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(s, s);
        logoLp.bottomMargin = dp(20);
        sp.addView(logo, logoLp);

        TextView name = new TextView(this);
        name.setText("幻 豆");
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(26);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        sp.addView(name, new LinearLayout.LayoutParams(WC, WC));

        TextView sub = new TextView(this);
        sub.setText("正在进入…");
        sub.setTextColor(0xFF9A8FAE);
        sub.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(WC, WC);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(26);
        sp.addView(sub, subLp);

        splashProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        splashProgress.setMax(100);
        splashProgress.setProgress(8);
        try {
            if (splashProgress.getProgressDrawable() != null) {
                splashProgress.getProgressDrawable().setColorFilter(ACCENT, PorterDuff.Mode.SRC_IN);
            }
        } catch (Throwable ignored) {
        }
        sp.addView(splashProgress, new LinearLayout.LayoutParams(dp(180), dp(3)));

        root.addView(sp, new FrameLayout.LayoutParams(MP, MP));
        splash = sp;
    }

    private void buildErrorView() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BRAND);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER);
        info.setPadding(dp(30), 0, dp(30), 0);

        TextView icon = new TextView(this);
        icon.setText("⚠");
        icon.setTextSize(30);
        icon.setTextColor(0xFF6E5C8C);
        icon.setGravity(Gravity.CENTER);
        info.addView(icon, new LinearLayout.LayoutParams(WC, WC));

        errorTitle = new TextView(this);
        errorTitle.setText("页面没能打开");
        errorTitle.setTextColor(0xFFE8E2F5);
        errorTitle.setTextSize(16);
        errorTitle.setTypeface(null, Typeface.BOLD);
        errorTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(WC, WC);
        tlp.topMargin = dp(10);
        info.addView(errorTitle, tlp);

        errorDetail = new TextView(this);
        errorDetail.setTextColor(0xFFB0A5C2);
        errorDetail.setTextSize(12);
        errorDetail.setGravity(Gravity.CENTER);
        errorDetail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(WC, WC);
        dlp.topMargin = dp(8);
        info.addView(errorDetail, dlp);

        errorUrl = new TextView(this);
        errorUrl.setTextColor(0xFF6E6383);
        errorUrl.setTextSize(10);
        errorUrl.setGravity(Gravity.CENTER);
        errorUrl.setSingleLine(true);
        errorUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(MP, WC);
        ulp.topMargin = dp(6);
        info.addView(errorUrl, ulp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(WC, WC);
        blp.topMargin = dp(20);

        TextView retry = new TextView(this);
        retry.setText("重新加载");
        retry.setTextColor(0xFF1B1020);
        retry.setTextSize(13);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(dp(22), dp(9), dp(22), dp(9));
        GradientDrawable rg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFB9A2FF, 0xFF8B6BE8});
        rg.setCornerRadius(dp(11));
        retry.setBackground(rg);
        retry.setClickable(true);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                web.reload();
            }
        });
        bar.addView(retry, new LinearLayout.LayoutParams(WC, WC));

        TextView homeBtn = new TextView(this);
        homeBtn.setText("回首页");
        homeBtn.setTextColor(0xFFD9D1E6);
        homeBtn.setTextSize(13);
        homeBtn.setGravity(Gravity.CENTER);
        homeBtn.setPadding(dp(20), dp(9), dp(20), dp(9));
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(0xFF2A1C3A);
        hg.setCornerRadius(dp(11));
        hg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        homeBtn.setBackground(hg);
        homeBtn.setClickable(true);
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                goHome();
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(WC, WC);
        hlp.leftMargin = dp(10);
        bar.addView(homeBtn, hlp);
        info.addView(bar, blp);

        errorTip = new TextView(this);
        errorTip.setTextColor(0xFF8E839C);
        errorTip.setTextSize(10.5f);
        errorTip.setGravity(Gravity.CENTER);
        errorTip.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(MP, WC);
        tipLp.topMargin = dp(18);
        info.addView(errorTip, tipLp);

        TextView egg = new TextView(this);
        egg.setText("🦖 等得无聊？点我玩一把");
        egg.setTextColor(0xFF6E6383);
        egg.setTextSize(11);
        egg.setGravity(Gravity.CENTER);
        egg.setPadding(dp(12), dp(10), dp(12), dp(10));
        egg.setClickable(true);
        egg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dinoView == null) return;
                infoHolder.setVisibility(View.GONE);
                dinoView.setVisibility(View.VISIBLE);
                if (dinoClose != null) dinoClose.setVisibility(View.VISIBLE);
                dinoView.begin();
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(WC, WC);
        elp.topMargin = dp(26);
        info.addView(egg, elp);

        infoHolder = info;
        frame.addView(info, new FrameLayout.LayoutParams(MP, MP));

        dinoView = new DinoView(this);
        dinoView.setVisibility(View.GONE);
        frame.addView(dinoView, new FrameLayout.LayoutParams(MP, MP));

        dinoClose = new TextView(this);
        dinoClose.setText("✕");
        dinoClose.setTextColor(0xFFD9D1E6);
        dinoClose.setTextSize(16);
        dinoClose.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0x33FFFFFF);
        cg.setCornerRadius(dp(16));
        dinoClose.setBackground(cg);
        dinoClose.setVisibility(View.GONE);
        dinoClose.setClickable(true);
        dinoClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dinoView != null) dinoView.halt();
                dinoView.setVisibility(View.GONE);
                dinoClose.setVisibility(View.GONE);
                infoHolder.setVisibility(View.VISIBLE);
            }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(32), dp(32));
        clp.gravity = Gravity.TOP | Gravity.END;
        clp.setMargins(0, dp(28), dp(16), 0);
        frame.addView(dinoClose, clp);

        root.addView(frame, new FrameLayout.LayoutParams(MP, MP));
        errorView = frame;
    }

    /** 失败原因翻成人话。desc 只在没匹配到已知错误码时兜底。 */
    private static String errorReason(int code, String desc) {
        switch (code) {
            case -2:
                return "域名解析失败，可能是网络不通，或 DNS 被干扰";
            case -6:
                return "连不上服务器，连接被中断";
            case -8:
                return "连接超时，网络太慢或请求被拦截";
            case -11:
                return "证书验证没通过，可能是中间人拦截或证书过期";
            case -4:
                return "服务器返回了错误";
            case -14:
                return "页面本身加载不出来，可能是站点已失效";
            default:
                break;
        }
        if (desc != null) {
            String d = desc.trim();
            if (d.length() > 0 && d.length() < 120) return d;
        }
        return "网络请求没有完成";
    }

    /** 这些站在国内网络通常直连不了，失败时直接点明需要代理，省得用户反复重试。 */
    private static final String[] PROXY_HINTS = {
            "google", "youtube", "twitter", "x.com", "facebook", "instagram",
            "telegram", "t.me", "wikipedia", "greasyfork", "github.io", "blogspot"
    };

    private static boolean likelyBlocked(String host) {
        if (host == null || host.length() == 0) return false;
        String h = "." + host.toLowerCase(Locale.US);
        for (int i = 0; i < PROXY_HINTS.length; i++) {
            String k = PROXY_HINTS[i];
            if (h.contains("." + k + ".") || h.endsWith("." + k)
                    || h.contains("." + k + ":")) return true;
        }
        return false;
    }

    private void showError(int code, String desc, String url) {
        if (errorView == null) return;
        if (infoHolder != null) infoHolder.setVisibility(View.VISIBLE);
        if (dinoView != null) {
            dinoView.halt();
            dinoView.setVisibility(View.GONE);
        }
        if (dinoClose != null) dinoClose.setVisibility(View.GONE);

        String host = SafeBrowse.hostFromUrl(url);
        if (errorDetail != null) errorDetail.setText(errorReason(code, desc));
        if (errorUrl != null) errorUrl.setText(url == null ? "" : url);
        if (errorTip != null) {
            errorTip.setText(likelyBlocked(host)
                    ? "这个站在国内网络通常打不开，需要先开代理（VPN），开好后点「重新加载」。"
                    : "先换个网络（Wi-Fi / 流量）试试。如果这个站平时就要用代理，"
                    + "开好 VPN 再点「重新加载」。");
        }
        errorView.bringToFront();
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (errorView != null) errorView.setVisibility(View.GONE);
        if (dinoView != null) dinoView.halt();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setUserAgentString(profile.ua);
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setTextZoom(textZoom);
        s.setDefaultTextEncodingName("UTF-8");
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        s.setCacheMode(isNetworkAvailable() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);

        web.setBackgroundColor(BRAND);
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setLongClickable(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(
                        new ServiceWorkerClient() {
                            @Override
                            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                                return null;
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        web.setFindListener(new WebView.FindListener() {
            @Override
            public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches,
                                             boolean isDoneCounting) {
                if (isDoneCounting) toast("找到 " + numberOfMatches + " 处匹配");
            }
        });
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                WebView.HitTestResult r = web.getHitTestResult();
                if (r == null) return false;
                int type = r.getType();
                String extra = r.getExtra();
                if (type == WebView.HitTestResult.IMAGE_TYPE
                        || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    showLongPressMenu(extra, true);
                    return true;
                }
                if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                        || type == WebView.HitTestResult.ANCHOR_TYPE) {
                    showLongPressMenu(extra, false);
                    return true;
                }
                return false;
            }
        });

        web.addJavascriptInterface(new JsBridge(), "HuandouNative");
        web.addJavascriptInterface(new UserscriptBridge(), "HuandouUs");
        web.setWebViewClient(new HdWebViewClient());
        web.setWebChromeClient(new HdChromeClient());
        web.setDownloadListener(new HdDownloadListener());
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pref.edit().putBoolean(KEY_NOTI_ASKED, true).apply();
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- WebView callbacks ----------------

    private class HdWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            // 服务端下发的域名黑名单。放在最前面：后面那些嗅探、注入、Cookie 记录，
            // 对一条已经被判定为垃圾的请求都没有意义。
            //
            // 拦子资源很安全；但主框架被拦会直接白屏，所以给一张能看懂的说明页，
            // 而不是留个空屏让用户以为 App 坏了。
            try {
                String bh = req.getUrl().getHost();
                // 首页域名不拦。名单配错时这是用户唯一还能进得来的地方，
                // 所以这条保护写死在客户端，不接受远端配置覆盖。
                boolean selfHost = bh != null && homeHost.length() > 0
                        && bh.equalsIgnoreCase(homeHost);
                if (!selfHost && RemoteConfig.blocked(MainActivity.this, bh)) {
                    if (blockedSeen.size() < 40) blockedSeen.add(bh);
                    return req.isForMainFrame() ? blockedPage(bh) : emptyResponse();
                }
            } catch (Throwable ignored) {
            }
            try {
                String h = req.getUrl().getHost();
                if (h != null && h.length() > 0 && h.length() < 120 && cookieHosts.size() < 40) {
                    cookieHosts.add(h);
                }
            } catch (Throwable ignored) {
            }
            try {
                String u = req.getUrl().toString();
                boolean added = MediaSniffer.note(u, currentPageTitle());
                // 图片、音频也会进嗅探表，但不能用它来提示「检测到视频」，
                // 否则随便打开一个带封面的页面都会弹一次。
                if (added && !mediaHintShown && MediaSniffer.isVideo(u)) {
                    mediaHintShown = true;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("检测到视频 · 菜单→「媒体嗅探」可下载");
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
            if (!req.isForMainFrame()) return null;
            String fpJs = (htmlInject && fpEnabled)
                    ? FpScript.build(profile, true, canvasNoise) : null;
            String usJs = null;
            if (userscriptsOn && userscriptsStart) {
                try {
                    usJs = UserscriptEngine.snippet(MainActivity.this,
                            req.getUrl().toString(), Userscript.RUN_START);
                } catch (Throwable ignored) {
                }
            }
            if (fpJs == null && usJs == null) return null;
            try {
                // 两份脚本共用这一次 HTML 改写，否则只有一份能进去。
                return HtmlInjector.injectBoth(req, fpJs, usJs);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme();
            // 脚本库里的「安装此脚本」指向 xxx.user.js，拦下来直接走安装，
            // 否则 WebView 要么把源码当文本显示，要么存成文件。
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                String target = u.toString();
                if (isUserScriptUrl(target)) {
                    installUsFromUrl(target);
                    return true;
                }
                return false;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, u);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ex) {
                toast("无法打开外部链接");
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            currentUrl = url;
            // 换页面了，上一页的剧名不能再拿来给这一页的视频命名。
            pageTitleHint = null;
            lastSignals = null;
            lastSignalsUrl = null;
            hideError();
            hideSafeBanner();
            checkRisk(url);
            if (pendingRestoreJs != null) {
                String js = pendingRestoreJs;
                pendingRestoreJs = null;
                lastRestoreJs = js;
                restoreCheckArmed = true;
                restoreRetried = false;
                v.evaluateJavascript(js, null);
            } else if (pendingDeepClean) {
                pendingDeepClean = false;
                v.evaluateJavascript(SITE_CLEAN_JS, null);
            }
            // 兼容模式下完全不注入指纹脚本，让页面看到原生浏览器环境。
            if (fpEnabled) {
                v.evaluateJavascript(FpScript.build(profile, true, canvasNoise), null);
            }
            if (forceDark) v.evaluateJavascript(Extras.DARK_JS, null);
            v.evaluateJavascript(SCROLL_WATCHER_JS, null);
            v.evaluateJavascript(BLANK_FIX_JS, null);
            updateProgress(10);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            pull.finishRefresh();
            if (fpEnabled) {
                v.evaluateJavascript(FpScript.build(profile, true, canvasNoise), null);
            }
            v.evaluateJavascript(SCROLL_WATCHER_JS, null);
            v.evaluateJavascript(BLANK_FIX_JS, null);
            v.evaluateJavascript(MEDIA_SCAN_JS, null);
            runUserscripts(v);
            if (safeBrowse) v.evaluateJavascript(SafeBrowse.PAGE_JS, null);
            grabPageMeta();
            // 单页应用是在脚本跑完后才把剧名写进 DOM 的，多抓几次兜住渲染延迟。
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    grabPageMeta();
                    // 单页应用常常是在脚本跑完后才把登录框 / 弹层塞进 DOM 的，再扫一次。
                    if (safeBrowse && web != null) {
                        try {
                            web.evaluateJavascript("(function(){window.__hdForce=1;})();"
                                    + SafeBrowse.PAGE_JS, null);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }, 1400);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (web != null) web.evaluateJavascript(MEDIA_SCAN_JS, null);
                    } catch (Throwable ignored) {
                    }
                    grabPageMeta();
                }
            }, 2500);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    grabPageMeta();
                }
            }, 6000);
            captureCurrentVault(false);
            checkRestore(v);
            detectIdentityError(v);
            updateProgress(100);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hideSplash();
                }
            }, 450);
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (!req.isForMainFrame()) return;
            hideSplash();
            int code = -1;
            String desc = null;
            try {
                code = err.getErrorCode();
                CharSequence cs = err.getDescription();
                desc = cs == null ? null : cs.toString();
            } catch (Throwable ignored) {
            }
            showError(code, desc, req.getUrl() == null ? null : req.getUrl().toString());
        }
    }

    private class HdChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView v, int progress) {
            if (splashProgress != null) splashProgress.setProgress(Math.max(progress, 8));
            updateProgress(progress);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            enterFullscreen(view, callback);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            Intent i;
            try {
                i = params.createIntent();
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
            try {
                startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE_CHOOSER);
            } catch (Exception e) {
                filePathCallback = null;
                toast("无法打开文件选择器");
                return false;
            }
            return true;
        }
    }

    private class HdDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null) return;
            // 有些站点的响应头会让 WebView 直接下载，这里兜一次，别把脚本存成文件。
            if (isUserScriptUrl(url)) {
                installUsFromUrl(url);
                return;
            }
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                downloadViaJs(url, mimeType, contentDisposition);
            } else {
                downloadViaManager(url, userAgent, contentDisposition, mimeType);
            }
        }
    }

    private final class JsBridge {
        @JavascriptInterface
        public void setWebAtTop(final boolean atTop) {
            mWebAtTop = atTop;
        }

        @JavascriptInterface
        public void onMediaFound(final String json) {
            try {
                JSONArray a = new JSONArray(json);
                String page = currentPageTitle();
                for (int i = 0; i < a.length() && i < 300; i++) {
                    MediaSniffer.note(a.optString(i, ""), page);
                }
            } catch (Throwable ignored) {
            }
            // 开始播放往往意味着刚切了集，标题 DOM 这时才更新，补抓一次。
            handler.post(new Runnable() {
                @Override
                public void run() {
                    grabPageMeta();
                }
            });
        }

        /**
         * 页面把剧名 / 集数报上来。字段：t=document.title，og=og:title，
         * h=标题类元素的文本数组，ep=页面上的「第N集」。
         */
        @JavascriptInterface
        public void onPageMeta(final String json) {
            try {
                JSONObject o = new JSONObject(json);
                java.util.ArrayList<String> cands = new java.util.ArrayList<String>();
                String t = o.optString("t", "");
                if (t.length() > 0) cands.add(t);
                String og = o.optString("og", "");
                if (og.length() > 0) cands.add(og);
                JSONArray h = o.optJSONArray("h");
                if (h != null) {
                    for (int i = 0; i < h.length() && i < 20; i++) {
                        String s = h.optString(i, "");
                        if (s.length() > 0) cands.add(s);
                    }
                }
                String best = "";
                // 1) 紧贴「第N集」的那段文字，最可能是正在播的这部剧。
                String near = o.optString("near", "").trim();
                if (near.length() > 0) {
                    String show = MediaName.titleOnly(near);
                    if (show.length() > 0) {
                        String nep = MediaName.episodeOf(near);
                        best = nep == null ? show : show + " 第" + nep + "集";
                    }
                }
                // 2) 没有邻近信息，就在所有候选里评分挑一条。
                if (best.length() == 0) {
                    best = MediaName.bestPageTitle(cands.toArray(new String[0]));
                    if (best.length() == 0) return;
                    String ep = o.optString("ep", "");
                    if (ep.length() > 0 && MediaName.episodeOf(best) == null) {
                        best = best + " 第" + ep + "集";
                    }
                }
                pageTitleHint = best.trim();
                // 视频请求往往比剧名渲染得早，把刚才那些空标题的条目补上。
                MediaSniffer.fillMissingTitles(pageTitleHint);
            } catch (Throwable ignored) {
            }
        }

        /**
         * 页面结构特征。字段都很短：pw=密码框数，cf=跨域表单数，hi=隐藏 iframe 数，
         * mr=自动跳转，ob=脚本混淆，lure=诱导下载文案，icp=备案号，mix=混合内容。
         */
        @JavascriptInterface
        public void onPageRisk(final String json) {
            if (!safeBrowse) return;
            try {
                final JSONObject o = new JSONObject(json);
                final String u = o.optString("u", "");
                if (u.length() == 0 || safeApproved.contains(u)) return;
                final SafeBrowse.PageSignals ps = SafeBrowse.parseSignals(o);
                if (ps == null) return;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        handlePageRisk(u, ps);
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void saveBase64(final String b64, final String mime, final String name) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    writeBase64ToDownloads(b64, mime, name);
                }
            });
        }
    }

    /** 页面就绪后跑脚本；document-start 在这里再兜一次，以防 HTML 改写没生效。 */
    private void runUserscripts(WebView v) {
        if (!userscriptsOn) return;
        try {
            String u = v.getUrl();
            if (u == null || u.length() == 0) return;
            String start = UserscriptEngine.snippet(this, u, Userscript.RUN_START);
            if (start != null) v.evaluateJavascript(start, null);
            String end = UserscriptEngine.snippet(this, u, Userscript.RUN_END);
            if (end != null) v.evaluateJavascript(end, null);
            String idle = UserscriptEngine.snippet(this, u, Userscript.RUN_IDLE);
            if (idle != null) v.evaluateJavascript(idle, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 油猴脚本的原生侧。脚本自己跑在页面里，这个桥只提供它拿不到的能力：
     * 持久化键值（不受站点清缓存影响）、跨域请求、剪贴板、通知、下载。
     */
    private final class UserscriptBridge {

        private final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(3);
        private final java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection> live =
                new java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>();

        private SharedPreferences kv() {
            return getSharedPreferences("huandou_us_kv", MODE_PRIVATE);
        }

        @JavascriptInterface
        public String get(String id, String key) {
            try {
                return kv().getString("s:" + id + ":" + key, "");
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public void set(String id, String key, String json) {
            try {
                kv().edit().putString("s:" + id + ":" + key, json).apply();
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void del(String id, String key) {
            try {
                kv().edit().remove("s:" + id + ":" + key).apply();
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public String keys(String id) {
            try {
                String p = "s:" + id + ":";
                JSONArray a = new JSONArray();
                for (String k : kv().getAll().keySet()) {
                    if (k != null && k.startsWith(p)) a.put(k.substring(p.length()));
                }
                return a.toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void xhr(final String rid, final String method, final String url,
                        final String headersJson, final String body) {
            // 脚本能把当前页的 Cookie 一起带出去，是这套桥里最危险的口子。
            // 改包的人就算 patch 掉了启动时的判断，这里也发不出请求。
            if (!SignatureGuard.ok(MainActivity.this)) return;
            if (!Gate.allowed(MainActivity.this)) return;
            if (url == null || url.length() == 0) return;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    doXhr(rid, method, url, headersJson, body);
                }
            });
        }

        @JavascriptInterface
        public void xhrAbort(String rid) {
            HttpURLConnection c = live.remove(rid);
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }

        private void doXhr(String rid, String method, String url,
                           String headersJson, String body) {
            HttpURLConnection conn = null;
            int status = 0;
            String text = "";
            String hdr = "{}";
            String finalUrl = url;
            String err = null;
            try {
                conn = (HttpURLConnection) new java.net.URL(url).openConnection();
                live.put(rid, conn);
                String m = (method == null || method.length() == 0)
                        ? "GET" : method.toUpperCase(Locale.US);
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod(m);
                String ua = profile != null && profile.ua != null ? profile.ua : null;
                if (ua != null) conn.setRequestProperty("User-Agent", ua);
                // 跨域读页面数据大多依赖登录态，默认带上目标站的 Cookie。
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && cookie.length() > 0) conn.setRequestProperty("Cookie", cookie);
                if (headersJson != null && headersJson.length() > 1) {
                    JSONObject h = new JSONObject(headersJson);
                    java.util.Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        try {
                            conn.setRequestProperty(k, h.optString(k, ""));
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (body != null && body.length() > 0
                        && !"GET".equalsIgnoreCase(m) && !"HEAD".equalsIgnoreCase(m)) {
                    conn.setDoOutput(true);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.close();
                }
                status = conn.getResponseCode();
                finalUrl = conn.getURL().toString();
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (in != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 14);
                    byte[] buf = new byte[8192];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 2 * 1024 * 1024) break;
                        bos.write(buf, 0, n);
                    }
                    in.close();
                    text = new String(bos.toByteArray(), "UTF-8");
                }
                JSONObject ho = new JSONObject();
                Map<String, List<String>> hm = conn.getHeaderFields();
                if (hm != null) {
                    for (Map.Entry<String, List<String>> e : hm.entrySet()) {
                        if (e.getKey() == null) continue;
                        List<String> v = e.getValue();
                        if (v != null && v.size() > 0) ho.put(e.getKey(), v.get(0));
                    }
                }
                hdr = ho.toString();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
            } finally {
                live.remove(rid);
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
            usXhrDone(rid, status, text, hdr, finalUrl, err);
        }

        private void usXhrDone(String rid, int status, String body, String headers,
                               String finalUrl, String err) {
            final String js = "try{window.__hdUsXhrDone("
                    + UserscriptEngine.q(rid) + "," + status + ","
                    + UserscriptEngine.q(body) + ","
                    + UserscriptEngine.q(headers) + ","
                    + UserscriptEngine.q(finalUrl) + ","
                    + UserscriptEngine.q(err) + ");}catch(e){}";
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (web != null) web.evaluateJavascript(js, null);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void notify(String title, String text) {
            String t = title == null ? "" : title.trim();
            String x = text == null ? "" : text.trim();
            final String msg = (t.length() == 0 ? x : (x.length() == 0 ? t : t + " · " + x));
            if (msg.length() == 0) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    toast(msg);
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("脚本内容",
                                text == null ? "" : text));
                        toast("脚本已复制到剪贴板");
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void open(final String url) {
            if (url == null || url.length() == 0) return;
            // 页面自己的 JS 也能调这个桥，别让它把 intent:// 之类甩给系统。
            if (!url.startsWith("http://") && !url.startsWith("https://")) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        toast("打不开这个地址");
                    }
                }
            });
        }

        @JavascriptInterface
        public void download(final String url, final String name) {
            if (url == null || url.length() == 0) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    downloadViaManager(url, null, name, null);
                }
            });
        }
    }

    private static final String SCROLL_WATCHER_JS = "(function(){"
            + "if(window.__hdScrollWatcher)return;window.__hdScrollWatcher=true;"
            + "function report(x,y){var can=false;try{"
            + "if(typeof x==='number'&&x>=0){var e=document.elementFromPoint(x,y),g=0;"
            + "while(e&&g<20){if(e.scrollTop>1){can=true;break;}e=e.parentElement;g++;}}"
            + "var d=document.scrollingElement||document.documentElement;"
            + "if(d&&d.scrollTop>1)can=true;"
            + "if(document.body&&document.body.scrollTop>1)can=true;"
            + "}catch(err){}try{HuandouNative.setWebAtTop(!can);}catch(err){}}"
            + "window.addEventListener('touchstart',function(ev){var t=ev.touches&&ev.touches[0];"
            + "report(t?t.clientX:-1,t?t.clientY:-1);},true);"
            + "report(-1,-1);})();";

    // ---------------- Identity & fingerprint ----------------

    private void newIdentity(final int kind) {
        showGlass(new AlertDialog.Builder(this)
                .setTitle("新建随机身份？")
                .setMessage("会从组合池里随机生成一套全新设备档案（机型、Android 版本、"
                        + "Chrome 版本、屏幕、DPR、GPU 等互相自洽）。\n\n"
                        + "当前身份的 Cookie / localStorage 会先存档，切回来时原样恢复；"
                        + "新身份从干净的站点状态开始。\n\n"
                        + "注意：网站用一次性身份票据绑定设备，换身份有可能再次触发风控"
                        + "（IDENTITY_CODE_REPLAYED）。日常使用建议保持兼容模式。")
                .setPositiveButton("仍然新建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doNewIdentity(kind);
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    private void doNewIdentity(int kind) {
        final DeviceProfile p = DeviceProfile.random(identities.nextLabel(), kind,
                alignChromeArg());
        identities.add(p);
        switchTo(p, false, "已新建：" + p.name + "\n" + p.brief()
                + "\n机型池 " + UaFactory.deviceCount() + " 款\n原身份登录状态已存档");
    }

    private void batchRandomIdentities(final int n) {
        showGlass(new AlertDialog.Builder(this)
                .setTitle("批量生成随机身份")
                .setMessage("将生成 " + n + " 个各不相同的设备档案保存到身份列表，"
                        + "不会切换当前身份。\n\n" + UaFactory.varietyHint() + "。\n\n"
                        + "之后可在「身份管理」里逐个切换；每个身份各自的 Cookie / 登录状态单独存档，"
                        + "切回时自动恢复。换身份仍可能触发网站风控。")
                .setPositiveButton("生成 " + n + " 个", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < n; i++) {
                            identities.add(DeviceProfile.random(identities.nextLabel(),
                                    DeviceProfile.KIND_ANDROID, alignChromeArg()));
                        }
                        toast("已生成 " + n + " 个随机身份，列表共 " + identities.size() + " 个");
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    private void showVarietyInfo() {
        showGlass(new AlertDialog.Builder(this)
                .setTitle("随机 UA 是怎么生成的")
                .setMessage(UaFactory.varietyHint() + "。\n\n"
                        + "每次生成会重新抽取全部字段，UA 里的机型 / 系统版本 / Chrome 版本"
                        + "与档案里的屏幕 / DPR / 核数 / GPU 保持一致，不会出现自相矛盾的组合。\n\n"
                        + "关于 TLS / HTTP2 指纹：握手特征由系统 WebView 的 native 网络栈决定，"
                        + "纯 WebView 壳改不了；设置里的「Chrome 版本对齐本机 WebView」能让 UA 版本"
                        + "贴近真实网络栈，减少版本矛盾，但改不了加密套件与 HTTP/2 参数。\n\n"
                        + "为什么默认是「本机原生 UA」：网站给设备签发一次性身份票据，"
                        + "频繁换身份容易被判为克隆或重放。所以默认保持兼容模式，"
                        + "随机身份由你手动开启。")
                .setPositiveButton("知道了", null)
                );
    }

    private void switchIdentity(DeviceProfile p) {
        switchTo(p, false, "已切换：" + p.name + "\n登录状态已按身份恢复（若被风控可一键修复）");
    }

    /** Apply identity parameters only. Never touches site data. */
    private void applyIdentity(DeviceProfile p) {
        profile = p;
        compatMode = false;
        compatWarned = false;
        pref.edit().putBoolean(KEY_COMPAT, false).apply();
        web.getSettings().setUserAgentString(p.ua);
        web.evaluateJavascript(FpScript.build(p, fpEnabled, canvasNoise), null);
    }

    // ---------------- Per-identity site state ----------------

    private String vaultKey(DeviceProfile p, boolean compat) {
        return compat ? IdentityVault.KEY_COMPAT : IdentityVault.keyOf(p.id);
    }

    private String currentVaultKey() {
        return vaultKey(profile, compatMode);
    }

    /** Collect cookies for every host this session has touched. */
    private LinkedHashMap<String, String> collectCookies() {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        LinkedHashSet<String> hosts = new LinkedHashSet<String>();
        try {
            String h = Uri.parse(home).getHost();
            if (h != null) hosts.add(h);
        } catch (Throwable ignored) {
        }
        if (currentUrl != null) {
            try {
                String h = Uri.parse(currentUrl).getHost();
                if (h != null) hosts.add(h);
            } catch (Throwable ignored) {
            }
        }
        hosts.addAll(cookieHosts);
        CookieManager cm = CookieManager.getInstance();
        for (String h : hosts) {
            if (h == null || h.length() == 0) continue;
            try {
                String c = cm.getCookie("https://" + h + "/");
                if (c != null && c.length() > 0) out.put(h, c);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** Persist current identity state; 'force' bypasses the 4s throttle. */
    private void captureCurrentVault(boolean force) {
        if (vault == null || web == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastCaptureAt < 4000) return;
        lastCaptureAt = now;
        captureVault(currentVaultKey(), null);
    }

    private void captureVault(final String key, final Runnable done) {
        if (vault == null) {
            if (done != null) done.run();
            return;
        }
        try {
            vault.saveCookies(key, collectCookies());
        } catch (Throwable ignored) {
        }
        if (web == null) {
            if (done != null) done.run();
            return;
        }
        if (done == null) {
            try {
                web.evaluateJavascript(DUMP_STORAGE_JS, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!switching) vault.saveStorage(key, value);
                    }
                });
            } catch (Throwable ignored) {
            }
            return;
        }
        final AtomicBoolean fired = new AtomicBoolean(false);
        final Runnable once = new Runnable() {
            @Override
            public void run() {
                if (fired.compareAndSet(false, true)) done.run();
            }
        };
        try {
            web.evaluateJavascript(DUMP_STORAGE_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    try {
                        vault.saveStorage(key, value);
                    } catch (Throwable ignored) {
                    }
                    once.run();
                }
            });
        } catch (Throwable ignored) {
            once.run();
        }
        handler.postDelayed(once, 600);
    }

    /**
     * Switch identity without losing either side's login state:
     * save current -> wipe global WebView storage -> restore target -> reload.
     */
    private void switchTo(final DeviceProfile target, final boolean targetCompat,
                          final String toastMsg) {
        if (switching || vault == null) return;
        switching = true;
        final String fromKey = currentVaultKey();
        captureVault(fromKey, new Runnable() {
            @Override
            public void run() {
                performSwitch(target, targetCompat, toastMsg);
            }
        });
    }

    private void performSwitch(final DeviceProfile target, final boolean targetCompat,
                               final String toastMsg) {
        try {
            web.evaluateJavascript(SITE_CLEAN_JS, null);
        } catch (Throwable ignored) {
        }
        final VaultCodec.Snap snap = vault.load(vaultKey(target, targetCompat));
        final AtomicBoolean applied = new AtomicBoolean(false);
        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (!applied.compareAndSet(false, true)) return;
                finishSwitch(target, targetCompat, snap, toastMsg);
            }
        };
        try {
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean value) {
                    finish.run();
                }
            });
        } catch (Throwable ignored) {
            finish.run();
        }
        handler.postDelayed(finish, 900);
    }

    private void finishSwitch(DeviceProfile target, boolean targetCompat,
                              VaultCodec.Snap snap, String toastMsg) {
        restoreCookies(snap);
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }
        try {
            web.clearCache(true);
            web.clearFormData();
            web.clearHistory();
        } catch (Throwable ignored) {
        }

        if (targetCompat) {
            compatMode = true;
            compatWarned = false;
            fpEnabled = false;
            htmlInject = false;
            canvasNoise = false;
            profile = DeviceProfile.system(this);
            pref.edit()
                    .putBoolean(KEY_COMPAT, true)
                    .putBoolean(KEY_FP, false)
                    .putBoolean(KEY_HTML, false)
                    .putBoolean(KEY_CANVAS, false)
                    .apply();
            web.getSettings().setUserAgentString(profile.ua);
        } else {
            identities.setActive(target);
            applyIdentity(target);
        }
        lastCaptureAt = 0;

        pendingRestoreJs = VaultCodec.restoreScript(snap);
        lastRestoreJs = pendingRestoreJs;
        restoreCheckArmed = false;
        restoreRetried = false;
        pendingDeepClean = false;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                switching = false;
                hideError();
                web.loadUrl(home);
            }
        }, 420);
        if (toastMsg != null) toast(toastMsg);
        else if (snap != null && !snap.isEmpty()) {
            toast("已恢复该身份的登录状态：" + VaultCodec.brief(snap));
        }
    }

    private void restoreCookies(VaultCodec.Snap snap) {
        if (snap == null) return;
        CookieManager cm = CookieManager.getInstance();
        for (Map.Entry<String, String> e : snap.cookies.entrySet()) {
            String host = e.getKey();
            if (host == null || host.length() == 0) continue;
            List<String> cookies = VaultCodec.splitCookies(e.getValue());
            for (String c : cookies) {
                try {
                    cm.setCookie("https://" + host + "/", c + "; Path=/; Max-Age=31536000");
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            cm.flush();
        } catch (Throwable ignored) {
        }
    }

    /** onPageFinished safety net: if the early injection missed, inject + reload once. */
    private void checkRestore(final WebView v) {
        if (!restoreCheckArmed) return;
        restoreCheckArmed = false;
        final String retry = lastRestoreJs;
        v.evaluateJavascript(
                "(function(){try{return window.__hdVaultRestored===true?'1':'0'}"
                        + "catch(e){return '0'}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (value != null && value.contains("1")) return;
                        if (retry == null || restoreRetried) return;
                        restoreRetried = true;
                        v.evaluateJavascript(retry, null);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    v.reload();
                                } catch (Throwable ignored) {
                                }
                            }
                        }, 180);
                    }
                });
    }

    /** Native-only storage wipe, safe to call before any page has been loaded. */
    private void hardResetStorage() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable ignored) {
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }
        try {
            web.clearCache(true);
            web.clearFormData();
            web.clearHistory();
        } catch (Throwable ignored) {
        }
    }

    /** Switch back to the native-browser identity, restoring its own saved state. */
    private void restoreCompat(boolean reload) {
        switchTo(null, true, "已恢复兼容模式：原生 UA、关闭伪装\n该模式的登录状态已单独存档");
    }

    /** Manually turning any spoofing on leaves compat mode so the choice persists. */
    private void syncCompatAfterOverride() {
        compatMode = !(fpEnabled || htmlInject || canvasNoise);
        pref.edit().putBoolean(KEY_COMPAT, compatMode).apply();
    }

    /** Detect the site's identity-code error page and explain it instead of raw JSON. */
    private void detectIdentityError(WebView v) {
        if (compatWarned) return;
        try {
            v.evaluateJavascript(
                    "(function(){try{return ((document.body&&document.body.innerText)||"
                            + "(document.documentElement&&document.documentElement.innerText)||'')"
                            + ".slice(0,800);}catch(e){return '';}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value == null) return;
                            if (value.contains("IDENTITY_CODE_REPLAYED")
                                    || value.contains("身份授权码无效")
                                    || value.contains("IDENTITY_CODE_")) {
                                compatWarned = true;
                                showIdentityError();
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void showIdentityError() {
        showGlass(new AlertDialog.Builder(this)
                .setTitle("网站拒绝了当前设备身份")
                .setMessage("页面返回「身份授权码无效或已使用」"
                        + "(IDENTITY_CODE_REPLAYED)。\n\n"
                        + "这是网站服务端的风控结果，不是 APP 崩溃。常见原因是设备指纹 / UA "
                        + "与网站已签发的身份码不一致，或同一设备短时间内重复申领身份码。\n\n"
                        + "点「一键修复」可切回原生浏览器身份，并恢复兼容模式自己保存的登录状态；"
                        + "若仍被拒绝，需要等待网站风控解除或更换网络环境。")
                .setPositiveButton("一键修复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        restoreCompat(true);
                    }
                })
                .setNeutralButton("用浏览器对比", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(home)));
                        } catch (Throwable ignored) {
                            toast("无法打开浏览器");
                        }
                    }
                })
                .setNegativeButton("稍后", null)
                );
    }

    /** Manual wipe: only the current identity's snapshot is dropped. */
    private void clearSiteData(final boolean reload) {
        if (vault != null) vault.remove(currentVaultKey());
        pendingRestoreJs = null;
        lastRestoreJs = null;
        restoreCheckArmed = false;
        try {
            web.evaluateJavascript(SITE_CLEAN_JS, null);
        } catch (Throwable ignored) {
        }
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }
        web.clearCache(true);
        web.clearFormData();
        web.clearHistory();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (reload) {
                    hideError();
                    web.loadUrl(home);
                }
                toast("已清除当前身份的站点数据与存档\n其他身份的登录状态不受影响");
            }
        }, 650);
    }

    private void showIdentityManager() {
        final List<DeviceProfile> list = identities.list();
        final int n = list.size();
        String[] names = new String[n + 7];
        for (int i = 0; i < n; i++) {
            DeviceProfile p = list.get(i);
            names[i] = (p.id == profile.id ? "● " : "○ ") + p.name
                    + "  [" + p.brief() + "]\n      存档：" + vault.brief(IdentityVault.keyOf(p.id));
        }
        names[n] = "🎲 随机生成 1 个安卓身份（海量组合）";
        names[n + 1] = "🎲 批量生成 10 个随机身份";
        names[n + 2] = "＋ 新建 iPhone 身份";
        names[n + 3] = "＋ 新建桌面身份";
        names[n + 4] = "↩ 回到兼容模式（原生 UA）";
        names[n + 5] = "ℹ 随机池说明 / 为什么默认不随机";
        names[n + 6] = "－ 删除身份…";
        showGlass(new AlertDialog.Builder(this)
                .setTitle("身份管理（" + n + " 个 · 各身份独立存档 · 当前"
                        + (compatMode ? "兼容模式" : "自定义身份") + "）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (w < n) switchIdentity(list.get(w));
                        else if (w == n) newIdentity(DeviceProfile.KIND_ANDROID);
                        else if (w == n + 1) batchRandomIdentities(10);
                        else if (w == n + 2) newIdentity(DeviceProfile.KIND_IOS);
                        else if (w == n + 3) newIdentity(DeviceProfile.KIND_DESKTOP);
                        else if (w == n + 4) restoreCompat(true);
                        else if (w == n + 5) showVarietyInfo();
                        else showDeleteIdentity();
                    }
                })
                .setNegativeButton("关闭", null)
                );
    }

    private void showDeleteIdentity() {
        final List<DeviceProfile> list = identities.list();
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
        if (names.length == 0) {
            toast("没有可删除的身份");
            return;
        }
        showGlass(new AlertDialog.Builder(this)
                .setTitle("选择要删除的身份")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, final int w) {
                        if (list.size() <= 1) {
                            toast("至少保留一个身份");
                            return;
                        }
                        DeviceProfile removed = list.get(w);
                        identities.remove(w);
                        if (vault != null) vault.remove(IdentityVault.keyOf(removed.id));
                        if (removed.id == profile.id) {
                            DeviceProfile next = identities.active();
                            if (next == null) {
                                next = DeviceProfile.random(identities.nextLabel(),
                                        DeviceProfile.KIND_ANDROID, alignChromeArg());
                                identities.add(next);
                            }
                            switchTo(next, false,
                                    "已删除：" + removed.name + "，已切到 " + next.name);
                        } else {
                            toast("已删除：" + removed.name);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    /** Real WebView Chrome major; this is the version behind the TLS fingerprint. */
    private String systemChromeMajor() {
        String m = DeviceProfile.chromeMajorOf(WebSettings.getDefaultUserAgent(this));
        return m.length() > 0 ? m : "未知";
    }

    /** Passed to DeviceProfile.random so new identities can match the real stack. */
    private String alignChromeArg() {
        if (!tlsAlign) return null;
        String m = DeviceProfile.chromeMajorOf(WebSettings.getDefaultUserAgent(this));
        return m.length() > 0 ? m : null;
    }

    private void showProfileInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("身份名称：").append(profile.name).append("\n\n");
        sb.append("设备档案：").append(profile.brief()).append("\n\n");
        sb.append("UA：\n").append(profile.ua).append("\n\n");
        sb.append("platform：").append(profile.platform)
                .append("  (UA-CH architecture=").append(profile.arch)
                .append(", bitness=").append(profile.bitness).append(")\n");
        if (profile.deviceYear > 0) {
            sb.append("机型年份：").append(profile.deviceYear).append("\n");
        }
        sb.append("screen：").append(profile.w).append(" × ").append(profile.h)
                .append(" @").append(profile.dpr).append("x\n");
        sb.append("CPU / 内存：").append(profile.cores).append(" 核 / ")
                .append(profile.mem).append(" GB\n");
        sb.append("WebGL：").append(profile.glRenderer).append("\n");
        sb.append("时区：").append(profile.timezone).append("\n");
        sb.append("指纹伪装：").append(onOff(fpEnabled))
                .append("  /  HTML 注入：").append(onOff(htmlInject))
                .append("\nCanvas 扰动：").append(onOff(canvasNoise))
                .append("\n\n站点状态存档：")
                .append(vault == null ? "?" : vault.brief(currentVaultKey()));
        showSelectableDialog("当前身份 / UA", sb.toString());
    }

    private void inputCustomUa() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(profile.ua);
        et.setSelection(et.getText().length());
        et.setPadding(dp(20), dp(12), dp(20), dp(12));
        showGlass(new AlertDialog.Builder(this)
                .setTitle("自定义 UA（留空则用系统默认）")
                .setView(et)
                .setPositiveButton("应用", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        profile.ua = v.length() == 0 ? WebSettings.getDefaultUserAgent(MainActivity.this) : v;
                        if (!identities.contains(profile.id)) identities.add(profile);
                        identities.update(profile);
                        identities.setActive(profile);
                        applyIdentity(profile);
                        web.reload();
                        toast("UA 已应用（已退出兼容模式，不清站点数据）");
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    // ---------------- Glass dialogs & native blur backdrop ----------------

    /**
     * The mask is a native View added to the Activity decor, i.e. it sits on
     * top of the WebView and below the dialog Window.  This is the native
     * equivalent of a sub-window: page scrolling, video surfaces and H5
     * z-index can never make it flicker or "pierce" the dialog.
     */
    private void ensureBlurLayer() {
        if (blurLayer != null) return;
        blurLayer = new FrameLayout(this);
        blurLayer.setZ(Z_BACKDROP);
        blurLayer.setClickable(true);
        blurLayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Tapping the mask closes the top dialog, like a standard sheet.
                if (glassTop != null && glassTop.isShowing()) glassTop.dismiss();
            }
        });
        blurLayer.setVisibility(View.GONE);
        blurImage = new ImageView(this);
        blurImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        blurLayer.addView(blurImage, new FrameLayout.LayoutParams(MP, MP));
        View tint = new View(this);
        tint.setBackgroundColor(MASK_COLOR);
        blurLayer.addView(tint, new FrameLayout.LayoutParams(MP, MP));
        root.addView(blurLayer, new FrameLayout.LayoutParams(MP, MP));
    }

    private void showBlurBackdrop() {
        ensureBlurLayer();
        Bitmap b = null;
        try {
            b = BlurBackdrop.capture(web);
        } catch (Throwable ignored) {
        }
        // A blank capture degrades to a plain translucent mask, never to black.
        blurImage.setImageBitmap(b);
        blurLayer.setAlpha(0f);
        blurLayer.setVisibility(View.VISIBLE);
        blurLayer.animate().alpha(1f).setDuration(180).start();
    }

    private void hideBlurBackdrop() {
        if (blurLayer == null) return;
        blurLayer.animate().cancel();
        blurLayer.setAlpha(0f);
        blurLayer.setVisibility(View.GONE);
        blurImage.setImageBitmap(null);
    }

    /** Single entry point for every native dialog; reference-counted backdrop. */
    private void attachGlass(final Dialog d) {
        glassTop = d;
        // The dialog Window is full-screen but transparent; let a tap on the
        // masked area dismiss it (standard sheet behaviour).
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            // Backdrop supplies the only mask (blur + 40% tint).
            w.setDimAmount(0f);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                glassRefs++;
                showBlurBackdrop();
            }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                if (glassTop == d) glassTop = null;
                glassRefs--;
                if (glassRefs <= 0) {
                    glassRefs = 0;
                    hideBlurBackdrop();
                }
            }
        });
        try {
            d.show();
        } catch (Throwable t) {
            toast("无法显示弹窗");
        }
    }

    private void showGlass(AlertDialog.Builder b) {
        attachGlass(b.create());
    }

    /** 需要自己关掉的调用方用它，避免「开新面板时旧面板还压在下面」。 */
    private Dialog showGlassD(AlertDialog.Builder b) {
        Dialog d = b.create();
        attachGlass(d);
        return d;
    }

    // ---------------- Menu ----------------

    private String onOff(boolean b) {
        return b ? "开" : "关";
    }

    /** One-character badges, stable across all Android fonts (no emoji). */
    private static final String[] MENU_ICONS = {
            "新", "身", "清", "", "伪", "H", "C", "U", "U", "",
            "找", "享", "外", "A", "亮", "家", "址", "刷", "载", "诊",
            "调", "屏", "修", "影", "M", "批", "锁",
            "", "隐", "暗", "读", "签", "盾", "", "猴"
    };

    private static final String[] MENU_SUBS = {
            "",
            "新建、切换、批量生成、删除都在这里", "只清当前身份，不动其他身份存档", "",
            "改写 navigator / WebGL 特征", "在文档首帧注入身份脚本", "给 Canvas 读取加轻微噪声",
            "当前 UA 与设备档案", "手动填写 UA 字符串", "",
            "在页面内高亮搜索", "调起系统分享面板", "用系统浏览器打开", "缩放网页文字",
            "播放时保持唤醒", "", "", "", "", "版本与运行状态",
            "", "隐藏状态栏与工具栏", "切回原生 UA，登录状态单独恢复", "抓视频 / 音频 / 图片，可筛选",
            "", "",
            "降低 UA 与 TLS 指纹的版本矛盾",
            "",
            "不存 Cookie，退出时清空本地数据",
            "所有网页转黑底白字，夜间护眼",
            "只留正文，可语音朗读",
            "收藏当前页，支持导入导出",
            "本地情报库 · 域名评分 · 可整页拦截",
            "",
            "装 .user.js：去广告 / 倍速 / 直链解析"
    };

    private static boolean isToggle(int i) {
        return i == 4 || i == 5 || i == 6 || i == 14 || i == 20 || i == 21
                || i == 24 || i == 26 || i == 28 || i == 29;
    }

    private boolean toggleOn(int i) {
        switch (i) {
            case 4:
                return fpEnabled;
            case 5:
                return htmlInject;
            case 6:
                return canvasNoise;
            case 14:
                return keepAwake;
            case 20:
                return webDebug;
            case 21:
                return immersive;
            case 24:
                return pref.getBoolean(KEY_TO_MP4, true);
            case 26:
                return tlsAlign;
            case 28:
                return incognito;
            case 29:
                return forceDark;
            default:
                return false;
        }
    }

    /** A compact, scrollable glass card capped at 74% of the screen height. */
    private void showMenu() {
        final Dialog d = new Dialog(this, R.style.GlassDialogTheme);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(buildSettingsView(d));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (dm.widthPixels * 0.90f);
            lp.height = (int) (dm.heightPixels * 0.74f);
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        attachGlass(d);
    }

    private View buildSettingsView(final Dialog d) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE8261836, 0xE6120C1C});
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        card.setBackground(bg);
        card.setElevation(dp(18));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(12), dp(12));

        TextView logo = new TextView(this);
        logo.setText("幻");
        logo.setTextColor(0xFFFFFFFF);
        logo.setTextSize(14);
        logo.setTypeface(null, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF9B7BFF, 0xFF5B3FA8});
        lg.setCornerRadius(dp(11));
        logo.setBackground(lg);
        head.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(11), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText("设置");
        t1.setTextColor(0xFFF3EFFA);
        t1.setTextSize(17);
        t1.setTypeface(null, Typeface.BOLD);
        titles.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("幻豆 v" + appVersion() + " · 点击条目直接执行");
        t2.setTextColor(0xFFA99FB8);
        t2.setTextSize(10);
        titles.addView(t2);
        head.addView(titles, new LinearLayout.LayoutParams(0, WC, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFC9BEDD);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                null, new ColorDrawable(0xFFFFFFFF)));
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        card.addView(head, new LinearLayout.LayoutParams(MP, WC));

        View line = new View(this);
        line.setBackgroundColor(0x22FFFFFF);
        card.addView(line, new LinearLayout.LayoutParams(MP, Math.max(1, dp(1))));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(4), dp(8), dp(20));
        for (int i = 0; i < MENU.length; i++) {
            String label = MENU[i];
            if (label == null) continue;          // 该位置已合并 / 隐藏
            if (label.startsWith("──")) {
                String clean = label.replace("─", "").trim();
                if (i == 3 && compatMode) clean = "兼容模式（推荐）· 已关闭全部伪装";
                rows.addView(makeGroupLabel(clean));
            } else {
                rows.addView(makeMenuRow(i, label,
                        i < MENU_SUBS.length ? MENU_SUBS[i] : "",
                        i < MENU_ICONS.length ? MENU_ICONS[i] : "·", d));
            }
        }
        sv.addView(rows, new FrameLayout.LayoutParams(MP, WC));
        card.addView(sv, new LinearLayout.LayoutParams(MP, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("关闭");
        foot.setTextColor(0xFFE6DDF5);
        foot.setTextSize(13);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(0xFF2A1C3A);
        fg.setCornerRadius(dp(12));
        fg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        foot.setBackground(fg);
        foot.setClickable(true);
        foot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(MP, WC);
        flp.setMargins(dp(18), dp(6), dp(18), dp(16));
        card.addView(foot, flp);
        return card;
    }

    private View makeGroupLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF9B7BFF);
        t.setTextSize(11);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(16), dp(14), dp(10), dp(5));
        return t;
    }

    private View makeMenuRow(final int index, String label, String sub,
                             String icon, final Dialog d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF),
                null, new ColorDrawable(0xFFFFFFFF)));
        row.setClickable(true);

        TextView ic = new TextView(this);
        ic.setText(icon == null || icon.length() == 0 ? "·" : icon);
        ic.setTextColor(0xFFD9C7FF);
        ic.setTextSize(12);
        ic.setTypeface(null, Typeface.BOLD);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x229B7BFF);
        circle.setStroke(Math.max(1, dp(1)), 0x339B7BFF);
        ic.setBackground(circle);
        row.addView(ic, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFF3EFFA);
        t.setTextSize(14);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(0xFF9A90AB);
            s.setTextSize(10);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, WC, 1f));

        if (isToggle(index)) {
            boolean on = toggleOn(index);
            TextView pill = new TextView(this);
            pill.setText(on ? "开" : "关");
            pill.setTextColor(on ? 0xFF1A0F26 : 0xFFA99FB8);
            pill.setTextSize(11);
            pill.setTypeface(null, Typeface.BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(11), dp(4), dp(11), dp(4));
            GradientDrawable pg = new GradientDrawable();
            pg.setColor(on ? 0xFF9B7BFF : 0x22FFFFFF);
            pg.setCornerRadius(dp(10));
            pill.setBackground(pg);
            row.addView(pill, new LinearLayout.LayoutParams(WC, WC));
        } else {
            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextColor(0x66FFFFFF);
            arrow.setTextSize(19);
            row.addView(arrow, new LinearLayout.LayoutParams(WC, WC));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                onMenu(index);
            }
        });
        return row;
    }

    private void onMenu(int i) {
        switch (i) {
            case 0:
                // 菜单第 0 项已隐藏（与顶栏「新身份」重复），保留分支只为索引稳定。
                newIdentity(DeviceProfile.KIND_ANDROID);
                break;
            case 1:
                showIdentityManager();
                break;
            case 2:
                clearSiteData(true);
                break;
            case 3:
            case 9:
                showMenu();
                break;
            case 4:
                fpEnabled = !fpEnabled;
                pref.edit().putBoolean(KEY_FP, fpEnabled).apply();
                syncCompatAfterOverride();
                toast("指纹伪装已" + (fpEnabled ? "开启（可能触发网站风控）" : "关闭") + "，下次刷新生效");
                web.evaluateJavascript(FpScript.build(profile, fpEnabled, canvasNoise), null);
                break;
            case 5:
                htmlInject = !htmlInject;
                pref.edit().putBoolean(KEY_HTML, htmlInject).apply();
                syncCompatAfterOverride();
                toast("HTML 首帧注入已" + (htmlInject ? "开启（可能触发网站风控）" : "关闭") + "，下次刷新生效");
                break;
            case 6:
                canvasNoise = !canvasNoise;
                pref.edit().putBoolean(KEY_CANVAS, canvasNoise).apply();
                syncCompatAfterOverride();
                toast("Canvas 扰动已" + (canvasNoise ? "开启" : "关闭"));
                web.evaluateJavascript(FpScript.build(profile, fpEnabled, canvasNoise), null);
                break;
            case 7:
                showProfileInfo();
                break;
            case 8:
                inputCustomUa();
                break;
            case 10:
                showFindBar();
                break;
            case 11:
                sharePage();
                break;
            case 12:
                openExternal(currentUrl == null ? home : currentUrl);
                break;
            case 13:
                showFontDialog();
                break;
            case 14:
                keepAwake = !keepAwake;
                pref.edit().putBoolean(KEY_AWAKE, keepAwake).apply();
                applyPreferences();
                toast("屏幕常亮已" + (keepAwake ? "开启" : "关闭"));
                break;
            case 15:
                goHome();
                break;
            case 16:
                showHomeDialog();
                break;
            case 17:
                hideError();
                web.reload();
                break;
            case 18:
                showDownloads();
                break;
            case 19:
                showDiagnostics();
                break;
            case 20:
                webDebug = !webDebug;
                pref.edit().putBoolean(KEY_DEBUG, webDebug).apply();
                applyPreferences();
                toast("WebView 远程调试已" + (webDebug ? "开启" : "关闭"));
                break;
            case 21:
                applyImmersive(!immersive);
                if (!immersive) toast("已退出沉浸模式");
                break;
            case 22:
                restoreCompat(true);
                break;
            case 23:
                showMediaSniffer();
                break;
            case 24:
                boolean mp4 = !pref.getBoolean(KEY_TO_MP4, true);
                pref.edit().putBoolean(KEY_TO_MP4, mp4).apply();
                toast("自动转 MP4 已" + (mp4 ? "开启（TS 会封装成 MP4）" : "关闭（保留原始格式）"));
                break;
            case 25:
                batchRandomIdentities(10);
                break;
            case 26:
                tlsAlign = !tlsAlign;
                pref.edit().putBoolean(KEY_TLS_ALIGN, tlsAlign).apply();
                if (tlsAlign && htmlInject) {
                    // HttpURLConnection in HtmlInjector uses the Java TLS stack,
                    // which would expose a second, different ClientHello.
                    htmlInject = false;
                    pref.edit().putBoolean(KEY_HTML, false).apply();
                }
                toast(tlsAlign
                        ? "已开启对齐：新身份 Chrome ≈ 本机 WebView " + systemChromeMajor()
                          + "；已关闭 HTML 首帧注入以避免第二套 TLS 指纹"
                        : "已关闭对齐：Chrome 版本恢复自由随机");
                break;
            case 28:
                setIncognito(!incognito);
                break;
            case 29:
                setForceDark(!forceDark);
                break;
            case 30:
                openReaderMode();
                break;
            case 31:
                showBookmarks();
                break;
            case 32:
                showSafeSettings();
                break;
            case 34:
                showUserscripts();
                break;
            default:
                break;
        }
    }

    private void showSelectableDialog(String title, String body) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFFE9E2F5);
        tv.setTextSize(12);
        tv.setPadding(dp(24), dp(16), dp(24), dp(8));
        showGlass(new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(tv)
                .setPositiveButton("关闭", null)
                );
    }

    private void showFontDialog() {
        final String[] labels = {"80%", "90%", "100%（默认）", "110%", "125%", "150%"};
        final int[] vals = {80, 90, 100, 110, 125, 150};
        int checked = 2;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == textZoom) checked = i;
        }
        showGlass(new AlertDialog.Builder(this)
                .setTitle("字体大小")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        textZoom = vals[w];
                        web.getSettings().setTextZoom(textZoom);
                        pref.edit().putInt(KEY_ZOOM, textZoom).apply();
                        d.dismiss();
                        toast("字体大小：" + labels[w]);
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    private void showHomeDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(home);
        et.setSelection(et.getText().length());
        et.setPadding(dp(20), dp(12), dp(20), dp(12));
        showGlass(new AlertDialog.Builder(this)
                .setTitle("设置首页地址")
                .setView(et)
                .setPositiveButton("保存并打开", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        if (!v.startsWith("http://") && !v.startsWith("https://")) {
                            toast("地址必须以 http:// 或 https:// 开头");
                            return;
                        }
                        home = v;
                        // 自保护要跟着首页走，否则换了站之后保护还留在旧域名上。
                        homeHost = hostOf(v);
                        pref.edit().putString(KEY_HOME, home).apply();
                        goHome();
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    /**
     * 真实的「下载记录」：直接查系统媒体库里的 下载/Huandou。
     *
     * <p>旧实现查的是 Android DownloadManager，而视频嗅探的下载走的是自己的前台服务，
     * 根本不进 DownloadManager 的队列，所以永远显示「暂无下载记录」——明明文件就在
     * 下载/Huandou 里躺着。现在改成查 MediaStore，点条目直接播放。</p>
     */
    private void showDownloads() {
        final java.util.ArrayList<DnItem> items = queryHuandouDownloads();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new ViewGroup.LayoutParams(MP, WC));
        box.setPadding(dp(14), dp(2), dp(14), dp(2));

        TextView sub = new TextView(this);
        sub.setText(items.isEmpty()
                ? "保存在 手机「下载 / Huandou」文件夹"
                : "共 " + items.size() + " 个 · 保存在「下载 / Huandou」· 点一下直接播放");
        sub.setTextColor(0xFFB7ABC9);
        sub.setTextSize(12.5f);
        sub.setPadding(dp(4), 0, dp(4), dp(10));
        box.addView(sub);

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有下载完成的视频。\n\n"
                    + "下载中的任务不会出现在这里，完成并写入文件后才会显示；"
                    + "如果刚才下载失败，通知栏里会保留失败原因，不会一闪而过。");
            empty.setTextColor(0xFF9C93AC);
            empty.setTextSize(12.5f);
            empty.setLineSpacing(dp(3), 1f);
            empty.setPadding(dp(4), dp(4), dp(4), dp(12));
            box.addView(empty);
        } else {
            if (items.size() <= 4) {
                for (int i = 0; i < items.size(); i++) {
                    box.addView(makeDownloadRow(items.get(i), i));
                }
            } else {
                ScrollView sv = new ScrollView(this);
                sv.setVerticalScrollBarEnabled(false);
                LinearLayout rows = new LinearLayout(this);
                rows.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < items.size(); i++) {
                    rows.addView(makeDownloadRow(items.get(i), i));
                }
                sv.addView(rows, new LinearLayout.LayoutParams(MP, WC));
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.46f);
                box.addView(sv, new LinearLayout.LayoutParams(MP, Math.min(dp(360), maxH)));
            }
        }

        attachGlass(new AlertDialog.Builder(this)
                .setTitle("下载记录")
                .setView(box)
                .setNeutralButton("打开文件夹", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openDownloadFolder();
                    }
                })
                .setPositiveButton("关闭", null)
                .create());
    }

    private static final class DnItem {
        String name;
        long size;
        long time;
        String mime;
        Uri uri;          // Android 10+ 的 MediaStore 地址
        String path;      // Android 9 及以下的绝对路径
    }

    private View makeDownloadRow(final DnItem it, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1FFFFFFF);
        g.setCornerRadius(dp(12));
        g.setStroke(Math.max(1, dp(1)), 0x26FFFFFF);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        row.setClickable(true);

        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTextColor(0xFFF1ECF8);
        name.setTextSize(13.5f);
        name.setMaxLines(2);
        row.addView(name);

        TextView meta = new TextView(this);
        String kind = "";
        if (it.mime != null) {
            if (it.mime.startsWith("image/")) kind = " · 图片";
            else if (it.mime.startsWith("audio/")) kind = " · 音频";
            else if ("video/mp4".equals(it.mime)) kind = " · MP4";
        }
        meta.setText(formatSize(it.size) + " · " + formatWhen(it.time) + kind);
        meta.setTextColor(0xFF9C93AC);
        meta.setTextSize(11f);
        meta.setPadding(0, dp(3), 0, 0);
        row.addView(meta);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playDownloaded(it);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
        lp.bottomMargin = dp(7);
        row.setLayoutParams(lp);
        return row;
    }

    /** 用系统文件管理器打开 下载/Huandou；打不开就提示路径。 */
    private void openDownloadFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(
                    "content://com.android.externalstorage.documents/document/"
                            + "primary%3ADownload%2FHuandou"),
                    "vnd.android.document/directory");
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        toast("文件在：手机存储 / Download / Huandou");
    }

    private void playDownloaded(DnItem it) {
        if (it.uri == null) {
            // Android 9 及以下没有 MediaStore 地址，只能提示文件位置
            toast("文件在：" + (it.path == null ? "下载/Huandou/" : it.path));
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(it.uri, it.mime == null ? "video/*" : it.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            toast("没有能播放它的应用，可在文件管理器里打开");
        }
    }

    private String formatSize(long b) {
        if (b <= 0) return "大小未知";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    private String formatWhen(long ms) {
        if (ms <= 0) return "";
        return new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.getDefault()).format(new java.util.Date(ms));
    }

    /** 查 下载/Huandou 下的文件；按时间倒序。 */
    private java.util.ArrayList<DnItem> queryHuandouDownloads() {
        if (Build.VERSION.SDK_INT < 29) return legacyDownloadScan();
        java.util.ArrayList<DnItem> out = new java.util.ArrayList<DnItem>();
        Cursor c = null;
        try {
            Uri ext = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_ADDED,
                    MediaStore.Downloads.MIME_TYPE
            };
            String sel = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
            String[] args = {"%" + Environment.DIRECTORY_DOWNLOADS + "/Huandou%"};
            c = getContentResolver().query(ext, proj, sel, args,
                    MediaStore.Downloads.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext() && out.size() < 80) {
                    DnItem it = new DnItem();
                    long id = c.getLong(0);
                    it.name = c.getString(1);
                    it.size = c.getLong(2);
                    it.time = c.getLong(3) * 1000L;
                    it.mime = c.getString(4);
                    it.uri = ContentUris.withAppendedId(ext, id);
                    if (it.name != null) out.add(it);
                }
            }
        } catch (Throwable t) {
            // 个别 ROM 上 MediaStore 会拒绝查询，退回空列表而不是崩掉
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** Android 9 及以下直接扫公共目录，系统没有 MediaStore.Downloads 集合。 */
    private java.util.ArrayList<DnItem> legacyDownloadScan() {
        java.util.ArrayList<DnItem> out = new java.util.ArrayList<DnItem>();
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "Huandou");
            File[] files = dir.listFiles();
            if (files == null) return out;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (int i = 0; i < files.length && out.size() < 80; i++) {
                File f = files[i];
                if (!f.isFile()) continue;
                DnItem it = new DnItem();
                it.name = f.getName();
                it.size = f.length();
                it.time = f.lastModified();
                it.path = f.getAbsolutePath();
                it.mime = it.name.endsWith(".mp4") ? "video/mp4" : "video/*";
                out.add(it);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void showDiagnostics() {
        final TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFFE9E2F5);
        tv.setTextSize(12);
        tv.setPadding(dp(24), dp(16), dp(24), dp(4));

        final TextView more = new TextView(this);
        more.setTextSize(11);
        more.setTextColor(0xFF8C86A0);
        more.setPadding(dp(24), dp(6), dp(24), dp(12));
        more.setGravity(Gravity.END);

        // Key Attestation 探针按钮。放在这里而不是设置页，是因为它和诊断信息是同一类东西：
        // 都是「把设备现在到底是什么状态，用一段能复制的文本说出来」。点一下会向系统要一张
        // 硬件背书的证书，然后整个复制到剪贴板——后端拿到那段 PEM 就能离线验完。
        final TextView attest = new TextView(this);
        attest.setTextSize(12);
        attest.setTextColor(0xFFD6C8F5);
        attest.setPadding(dp(16), dp(9), dp(16), dp(9));
        attest.setGravity(Gravity.CENTER);
        GradientDrawable abg = new GradientDrawable();
        abg.setColor(0x2EA78BFA);
        abg.setCornerRadius(dp(18));
        abg.setStroke(dp(1), 0x66A78BFA);
        attest.setBackground(abg);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(24);
        alp.rightMargin = dp(24);
        alp.topMargin = dp(10);
        attest.setLayoutParams(alp);
        attest.setText(attestLabel(KeyAttest.lastResult()));

        final TextView note = new TextView(this);
        note.setTextSize(10);
        note.setTextColor(0xFF6E6680);
        note.setPadding(dp(26), dp(3), dp(24), 0);
        note.setText("点：取挑战码→要证书→交服务端验 · 长按：只导出，不联网");

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 抢在输入框前面拿焦点，否则一打开对话框就弹键盘，把按钮顶上去。
        box.setFocusableInTouchMode(true);
        box.requestFocus();

        // 诊断文本必须自己滚动，而且必须限高。
        //
        // 第一版是把 tv 直接 addView 进 box 的。结果下载日志一长，tv 就把下面
        // 的按钮整个顶出屏幕——用户看到的是「字体缩放：100%」然后直接是关闭，
        // 按钮"失踪"了。AlertDialog 的滚动只在它自己的 message 区生效，
        // 用 setView 塞进去的自定义布局不再受那层 ScrollView 保护。
        //
        // 所以这里给 tv 套一个高度上限为半屏的 ScrollView，按钮和「技术细节」
        // 放在滚动区外面：不管日志多长，它们永远在可视范围里。
        final ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        sc.setFillViewport(false);
        final int capH = Math.max(dp(220), (getResources().getDisplayMetrics().heightPixels * 5) / 10);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, capH));

        // 1.9.0 起不再需要手工粘贴挑战码：点按钮就自动取。原来那个输入框去掉，
        // 省一行高度，也省掉「用户贴错、服务端报 challenge_mismatch、还以为是设备不支持」
        // 这条最难查的错路。真要手工指定，长按按钮会走本地随机的旧路径。
        box.addView(sc);
        box.addView(attest);
        box.addView(note);
        box.addView(more);

        final boolean[] open = new boolean[]{false};
        final String[] ls = new String[]{"查询中…"};
        final int[] taps = new int[]{0};
        final boolean[] busy = new boolean[]{false};

        // 内容比上限矮时把滚动区收回去，免得短诊断下面挂一块空。
        final Runnable fit = new Runnable() {
            @Override
            public void run() {
                int h = tv.getHeight();
                ViewGroup.LayoutParams lp = sc.getLayoutParams();
                int want = (h > 0 && h < capH) ? h : capH;
                if (lp.height != want) {
                    lp.height = want;
                    sc.setLayoutParams(lp);
                }
            }
        };

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                tv.setText(diagText(open[0], ls[0]));
                tv.post(fit);
            }
        };

        more.setText("技术细节 ▸");
        refresh.run();

        // 一键验证：取挑战码 → 生成密钥 → 提交 → 显示结论。
        //
        // 每次点都重新生成，不复用上次的链。挑战码是一次性的，复用旧链等于拿一个
        // 已经作废的随机数去证明新鲜性，服务端必然拒。代价是一次一两秒。
        //
        // 抽成 starter 是为了让「冲突测试」（技术细节里才有的按钮）复用同一条流程，
        // 唯一差别是请求里多带两个假的客户端自报字段。
        final Starter starter = new Starter() {
            @Override
            public void start(final boolean conflict) {
                if (busy[0]) return;
                if (!hasNetwork()) {
                    Toast.makeText(MainActivity.this,
                            "当前没有网络。要离线导出就长按这个按钮", Toast.LENGTH_LONG).show();
                    return;
                }
                busy[0] = true;
                attest.setText(conflict ? "冲突测试…" : "验证中…");
                cloudLog.setLength(0);
                runCloudVerify(conflict, new Progress() {
                    @Override
                    public void log(final String s) {
                        uiLog(s, refresh, sc);
                    }

                    @Override
                    public void done(final AttestClient.Verdict v) {
                        // done 是从网络线程回调过来的，这里每一行都是 UI 操作，
                        // 必须自己切回主线程。1.9.0 第一版漏了这层，点一下必闪退：
                        // CalledFromWrongThreadException，栈顶在 TextView.setText。
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                busy[0] = false;
                                attest.setText(verifyLabel(v));
                                if (v != null && !v.netErr) {
                                    Toast.makeText(MainActivity.this, v.oneLine(),
                                            Toast.LENGTH_LONG).show();
                                }
                                refresh.run();
                            }
                        });
                    }
                });
            }
        };

        attest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                starter.start(false);
            }
        });

        // 长按 = 只导出，不联网。保留这条路径有两个用处：无网时能自证，
        // 以及怀疑服务端有问题时，把同一张链拿去做离线比对。
        attest.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (busy[0]) return true;
                busy[0] = true;
                attest.setText("向系统要证书…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final KeyAttest.Result r = KeyAttest.probe(MainActivity.this, null);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                busy[0] = false;
                                attest.setText(verifyLabel(lastVerdict));
                                if (r.ok) {
                                    copyAttestChain(r);
                                    Toast.makeText(MainActivity.this,
                                            "证书链已复制（本地挑战码，离线查看）",
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(MainActivity.this,
                                            r.summary(), Toast.LENGTH_LONG).show();
                                }
                                refresh.run();
                            }
                        });
                    }
                }, "key-attest").start();
                return true;
            }
        });

        // 默认只给结论。要看全套得连点七次——不指望拦住谁，只是让「随手截图发出去」
        // 不再顺手带走算法名、完整地址和直链参数。
        //
        // 展开时顺手挂上「自报字段冲突测试」：正常请求不带 package/version_code，
        // 这一条故意带假的。服务端如果拿这两个值当依据，返回值会跟着变——那就是 bug。
        // 藏在这里是因为它没有日常价值，只用于验收。
        final boolean[] conflictAdded = new boolean[]{false};
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (++taps[0] < 7) return;
                taps[0] = 0;
                open[0] = !open[0];
                if (open[0] && !conflictAdded[0]) {
                    conflictAdded[0] = true;
                    TextView cf = new TextView(MainActivity.this);
                    cf.setTextSize(11);
                    cf.setTextColor(0xFF8C86A0);
                    cf.setPadding(dp(24), dp(8), dp(24), dp(2));
                    cf.setText("自报字段冲突测试 ▸ 请求里带 package=com.evil.app / version_code=999");
                    cf.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v2) {
                            starter.start(true);
                        }
                    });
                    box.addView(cf, box.getChildCount() - 1);

                    // 逃生门。拦截页上写了「可在这里清空名单」，那就必须真有这个入口，
                    // 否则哪天后端配错一条域名，用户连自己救自己都做不到。
                    TextView cl = new TextView(MainActivity.this);
                    cl.setTextSize(11);
                    cl.setTextColor(0xFF8C86A0);
                    cl.setPadding(dp(24), dp(8), dp(24), dp(2));
                    cl.setText("清空下发配置 ▸ 公告与黑名单回到默认，下次启动重新拉取");
                    cl.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v2) {
                            RemoteConfig.clear(MainActivity.this);
                            if (noticeBar != null) noticeBar.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this,
                                    "已清空。公告与黑名单不再生效，重启后会重新拉取",
                                    Toast.LENGTH_LONG).show();
                            refresh.run();
                        }
                    });
                    box.addView(cl, box.getChildCount() - 1);

                    // 准入复检。这不是后门：清掉凭据之后还是要过服务端那一关，
                    // 过不了照样被挡在门外。它的用处是「立刻看到门禁在不在工作」——
                    // 否则装上升级版的人手里有 7 天凭据，永远看不到那个页面，
                    // 只能靠信我。
                    TextView gr = new TextView(MainActivity.this);
                    gr.setTextSize(11);
                    gr.setTextColor(0xFF8C86A0);
                    gr.setPadding(dp(24), dp(8), dp(24), dp(2));
                    gr.setText("重新校验准入 ▸ 清掉本地凭据，立刻走一次服务端校验");
                    gr.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v2) {
                            DeviceToken.clear(MainActivity.this);
                            Toast.makeText(MainActivity.this,
                                    "已清除本地凭据，正在重新校验", Toast.LENGTH_SHORT).show();
                            recreate();
                        }
                    });
                    box.addView(gr, box.getChildCount() - 1);
                }
                more.setText(open[0] ? "收起 ▴" : "技术细节 ▸");
                refresh.run();
            }
        });

        showGlass(new AlertDialog.Builder(this).setTitle("诊断信息").setView(box)
                .setPositiveButton("关闭", null));

        web.evaluateJavascript(
                "(function(){try{return String(localStorage.length)}catch(e){return '不可用'}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        ls[0] = value == null ? "?" : value.replace("\"", "");
                        refresh.run();
                    }
                });
    }

    /**
     * 按钮上那行字。没探测过时是「导出…」，探测成功时直接给结论，
     * 免得用户点完还得往下翻诊断文本才知道成没成。
     */
    private String attestLabel(KeyAttest.Result r) {
        if (r == null) return "导出 Key Attestation 链 ▸";
        return r.ok ? "重新导出 ▸ " + r.summary() : "重试 ▸ " + r.summary();
    }

    // ── 云端验证 ────────────────────────────────────────────────

    /**
     * 冷启动 20 秒后在后台同步一次设备凭据与配置。
     *
     * <p>为什么不放在启动路径上：一次 TEE 密钥生成要 1–2 秒，还要两次网络往返。
     * 放在启动里会让首屏明显变慢，而这件事用户根本不感知。</p>
     *
     * <p>为什么 20 秒：给 WebView 和首屏留够时间。太早会和首页渲染抢 CPU 和 IO。</p>
     */
    private void scheduleCloudSync() {
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                final Context ctx = MainActivity.this;

                // 有可用凭据：只拉配置，不重新生成证书。
                if (DeviceToken.valid(ctx) != null) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            if (RemoteConfig.sync(ctx)) postApplyNotice();
                        }
                    }, "hd-config").start();
                    return;
                }

                // 冷却中：上一步刚失败过，别每 20 秒重来一次。
                if (DeviceToken.cooling(ctx)) return;

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (DeviceToken.refresh(ctx) && RemoteConfig.sync(ctx)) postApplyNotice();
                    }
                }, "hd-bind").start();
            }
        }, 20000);
    }

    /** 把 unix 秒格式化成 「MM-dd HH:mm」，给凭据有效期用。 */
    private String fmtTime(long unixSec) {
        if (unixSec <= 0) return "?";
        return android.text.format.DateFormat
                .format("MM-dd HH:mm", unixSec * 1000L).toString();
    }

    /** 验证流程的两个回调，都在子线程调用，实现方负责切主线程。 */
    private interface Progress {
        void log(String line);

        void done(AttestClient.Verdict v);
    }

    /** 「发起一次验证」的入口，供主按钮和冲突测试按钮共用。 */
    private interface Starter {
        void start(boolean conflict);
    }

    /**
     * 跑完整条链路。全程在子线程，异常一律翻译成 Verdict 返回，不往外抛——
     * 抛出去在子线程里就是崩溃，而这里几乎每一种失败都是「预期内的失败」。
     */
    private void runCloudVerify(final boolean conflict, final Progress cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AttestClient.Verdict v = null;
                try {
                    cb.log("取挑战码…");
                    AttestClient.Challenge c = AttestClient.fetchChallenge();
                    cb.log("挑战码 " + c.shortId() + " · 有效期 " + c.expiresIn + "s");

                    cb.log("向系统要证书…");
                    KeyAttest.Result r = KeyAttest.probe(MainActivity.this, c.bytes);
                    if (!r.ok) {
                        v = new AttestClient.Verdict();
                        v.reason = "local_unavailable";
                        v.detail = r.err;
                        cb.log("本机没能签发认证证书：" + r.err);
                        cb.done(v);
                        return;
                    }
                    cb.log("证书 " + levelName(r.level) + " · " + r.chainLen + " 张 · challenge "
                            + (r.challengeOk ? "匹配" : "不匹配"));

                    org.json.JSONObject claimed = null;
                    if (conflict) {
                        claimed = new org.json.JSONObject();
                        claimed.put("package", "com.evil.app");
                        claimed.put("version_code", 999);
                        cb.log("冲突测试：自报 package=com.evil.app version_code=999");
                    }

                    cb.log("提交服务端…");
                    v = AttestClient.verify(c, r.pem, claimed);
                    lastVerdict = v;
                    lastVerdictAt = System.currentTimeMillis();
                    cb.log("结论 " + v.oneLine() + "（" + v.ms + "ms）");
                    cb.done(v);
                } catch (Throwable t) {
                    if (v == null) {
                        v = new AttestClient.Verdict();
                        v.netErr = true;
                        v.detail = t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : " " + t.getMessage());
                    }
                    cb.log("中断 " + v.oneLine());
                    cb.done(v);
                }
            }
        }, "attest-net").start();
    }

    private static String levelName(int level) {
        if (level == 2) return "StrongBox";
        if (level == 1) return "TEE";
        if (level == 0) return "软件模拟";
        return "等级未知";
    }

    /** 把一行日志贴到诊断文本顶部，并滚回顶部——新的一行在最上面。 */
    private void uiLog(final String s, final Runnable refresh, final ScrollView sc) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (cloudLog.length() > 0) cloudLog.append('\n');
                cloudLog.append(s);
                trimCloudLog();
                refresh.run();
                sc.post(new Runnable() {
                    @Override
                    public void run() {
                        sc.fullScroll(View.FOCUS_UP);
                    }
                });
            }
        });
    }

    /** 日志最多留 8 行，超了从最老的删。诊断页本来就小，不能让它无限长。 */
    private void trimCloudLog() {
        int lines = 0;
        for (int i = 0; i < cloudLog.length(); i++) {
            if (cloudLog.charAt(i) == '\n') lines++;
        }
        while (lines >= 8) {
            int p = cloudLog.indexOf("\n");
            if (p < 0) break;
            cloudLog.delete(0, p + 1);
            lines--;
        }
    }

    /** 主按钮上的字：有结论就直接给结论，没有才显示动作。 */
    private String verifyLabel(AttestClient.Verdict v) {
        if (v == null) return "一键验证 ▸";
        // 服务端的 detail 可能带一串英文，直接铺到按钮上会把按钮撑成三行，
        // 又把下面的东西挤出屏幕——这个坑 1.8.7 已经踩过一次了。
        String s = AttestClient.clip(v.oneLine(), 34);
        return (v.ok ? "重新验证 ▸ " : "重试 ▸ ") + s;
    }

    /**
     * 有没有可用网络。
     *
     * <p>没有就直接提示，而不是让用户对着「验证中…」等 12 秒超时。
     * 拿不准的时候返回 true（宁可多试一次，也不要因为读不到网络状态就误报没网）。</p>
     */
    private boolean hasNetwork() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc == null || nc.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 把整条链写进剪贴板。
     *
     * <p>格式上做了取舍：不用 JSON。PEM 多段是 openssl、Java keystore、Python 的
     * {@code ssl.PEM_cert_to_DER_cert} 全都能直接吃的格式，而 JSON 包一层之后，
     * 每个后端都要先写一段拆包代码。头部那几行以 # 开头，是给人和脚本看的注释，
     * 真正的证书从 {@code -----BEGIN} 开始。</p>
     */
    /**
     * 解析粘贴进来的挑战码：64 位十六进制，或 43 位 base64url。
     *
     * <p>服务端两个字段都返回（{@code challenge} 是 base64url，{@code challenge_hex} 是 hex），
     * 用户复制哪个都行。空串返回 null，走本地随机。</p>
     *
     * <p>不做「猜一半」：长度或字符不对就直接抛，让调用方提示用户重贴。
     * 悄悄截断或补零会让服务端校验失败，而用户以为自己贴对了。</p>
     */
    private byte[] parseChallenge(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0) return null;

        if (s.matches("[0-9a-fA-F]{64}")) {
            byte[] out = new byte[32];
            for (int i = 0; i < 32; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }

        String b = s;
        while (b.endsWith("=")) b = b.substring(0, b.length() - 1);
        try {
            byte[] out = Base64.decode(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (out != null && out.length == 32) return out;
        } catch (Throwable ignored) {
        }
        throw new IllegalArgumentException("bad challenge");
    }

    private void copyAttestChain(KeyAttest.Result r) {
        StringBuilder sb = new StringBuilder(r.pem.length() + 512);
        sb.append(r.header()).append('\n');
        // 注意这里必须用真实机型，不能用 profile.brief()。后者是伪装身份，
        // 开着「iPhone 14 / iOS」这种档位时，导出的注释会写成苹果设备——
        // 证书本身是 Android 的，注脚写成 iOS，后端一看就会怀疑这条链的来历。
        sb.append("# 幻豆 ").append(appVersion())
                .append(" · 设备 ").append(realDevice()).append('\n');
        sb.append("# 包名 ").append(getPackageName())
                .append(" · versionCode ").append(appVersionCode()).append('\n');
        // challenge 本身不是秘密（就是个随机数），写出来是为了让服务端和人都能核对
        // 「这条链绑的到底是哪一次请求」。
        sb.append("# challenge ").append("ext".equals(r.challengeSource) ? "服务端下发" : "本地随机")
                .append(" · ").append(r.challengeHex).append('\n');
        sb.append("# 叶子证书 SHA-256 ").append(r.leafFp).append('\n');
        sb.append(r.pem);
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("KeyAttestation", sb.toString()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 真实机型，只用于导出的证书链注脚。
     *
     * <p>{@code Build.MODEL} 里通常已经带了品牌（"Pixel 7"、"M2102K1C"），
     * 再拼一次 MANUFACTURER 会变成「Xiaomi M2102K1C」这种重复且难读的串，
     * 所以只在型号里确实没有品牌时才补。</p>
     */
    private String realDevice() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String d = Build.MODEL == null ? "" : Build.MODEL.trim();
        String name = d.length() == 0 ? "未知机型" : d;
        if (m.length() > 0 && d.length() > 0 && !d.toLowerCase().contains(m.toLowerCase())) {
            name = m + " " + d;
        }
        return name + " · Android " + Build.VERSION.RELEASE;
    }

    /**
     * 诊断文本。
     *
     * <p>默认那一屏只回答「能不能用」：版本、安全状态、身份数量、通知权限、最近的下载日志。
     * 算法名、系统版本、WebView 版本、完整地址这些放进 full=true 的那一段，需要连点七次才展开。
     *
     * <p>不是为了防逆向——dex 就在那儿，反编译什么都看得到。纯粹是因为这个页面会被截图：
     * 发给我排障、发群里问人。截图里出现「AES-256-GCM」不算泄露，出现带 token 的直链才是。
     */
    private String diagText(boolean full, String lsCount) {
        StringBuilder sb = new StringBuilder();
        boolean healthy = false;
        try {
            healthy = SignatureGuard.ok(this) && VaultCrypto.selfTest(this);
        } catch (Throwable ignored) {
        }
        sb.append("应用：幻豆 ").append(appVersion())
                .append("（versionCode ").append(appVersionCode()).append("）\n");

        // 云端验证的实时日志放最上面：这是用户点一下之后最想看的东西，
        // 藏在下面等于让他边滚边找。为空时整块不出现，不占行。
        if (cloudLog.length() > 0) {
            sb.append("—— 云端验证");
            if (lastVerdictAt > 0) {
                sb.append(" ").append(android.text.format.DateFormat.format("HH:mm", lastVerdictAt));
            }
            sb.append(" ——\n").append(cloudLog).append('\n');
        }
        sb.append("安全状态：").append(healthy ? "正常" : "需要检查").append("\n");
        sb.append("运行模式：").append(compatMode ? "兼容模式（原生 UA / 不伪装）" : "自定义身份模式").append("\n");
        sb.append("当前身份：").append(profile.name).append("\n");
        try {
            String cookie = CookieManager.getInstance().getCookie(home);
            sb.append("站点 Cookie：").append(cookie == null ? 0 : cookie.split(";").length).append(" 项\n");
        } catch (Throwable ignored) {
        }
        sb.append("已保存身份：").append(identities.size()).append(" 个 · 存档 ")
                .append(vault == null ? 0 : vault.snapshotCount()).append(" 份\n");
        sb.append("通知权限：").append(hasNotificationPermission() ? "已允许" : "未允许（下载无提醒）")
                .append(" · 通知开关：").append(notificationsEnabled() ? "开" : "关").append("\n");
        // 硬件背书这一行放在结论区：它决定的是「服务端能验到什么程度」，
        // 属于「能不能用」的范畴，不是技术细节。
        KeyAttest.Result ka = KeyAttest.lastResult();
        sb.append("硬件证明：").append(ka == null ? "未检测" : ka.summary()).append("\n");

        // 准入状态。能进到这里说明一定是放行的，所以重点不是「过没过」，
        // 而是「还剩几天」——剩得少的时候用户至少要能提前察觉，而不是某天突然打不开。
        String dt = DeviceToken.usable(this);
        if (dt != null) {
            long leftS = DeviceToken.exp(this) - System.currentTimeMillis() / 1000;
            sb.append("准入：放行 · 凭据至 ").append(fmtTime(DeviceToken.exp(this)))
                    .append("（剩 ").append(Math.max(0, leftS / 86400)).append(" 天）")
                    .append(" · 配置 ")
                    .append(RemoteConfig.fetchedAt(this) > 0 ? "已同步" : "未同步").append("\n");
        } else {
            String e = DeviceToken.lastError(this);
            sb.append("准入：无凭据")
                    .append(e.length() > 0 ? "（" + AttestClient.clip(e, 28) + "）" : "")
                    .append("\n");
        }

        // 光说「配置已同步」没有意义。这里直接写清这份配置到底改变了哪些行为，
        // 否则「配置生效了」永远只是一句没法核对的话。
        if (RemoteConfig.fetchedAt(this) > 0) {
            String nt = RemoteConfig.notice(this);
            sb.append("下发内容：公告 ").append(nt.length() == 0 ? "无" : nt.length() + " 字")
                    .append(" · 黑名单 ").append(RemoteConfig.blocklist(this).length).append(" 条");
            int hit = blockedSeen.size();
            if (hit > 0) {
                StringBuilder hb = new StringBuilder();
                synchronized (blockedSeen) {
                    int k = 0;
                    for (String d : blockedSeen) {
                        if (k > 0) hb.append('、');
                        hb.append(d);
                        if (++k >= 3) break;
                    }
                }
                sb.append(" · 本次拦下 ").append(hit).append(" 个（").append(hb).append("…）");
            }
            sb.append("\n");
        }

        // 下载失败时「通知一闪就没」是最难描述的一类问题，这里直接把服务端到端的
        // 关键节点摆出来：收到没、入队没、拿了多少字节、存到哪、报了什么错。
        // URL 在写入时就已经脱敏（Diag.maskUrl），这里拿到的本来就不含 token。
        String dlLog = Diag.tail(this, 12);
        sb.append("最近下载日志").append(dlLog.length() == 0 ? "：还没有记录\n" : "：\n").append(dlLog);
        sb.append("字体缩放：").append(textZoom).append("%\n");
        sb.append("localStorage 键数：").append(lsCount).append("\n");

        // 上次崩溃只给一行摘要，全文放技术细节。不清空——用户可能还没看，
        // 清早了下次又只能靠猜。要清在技术细节里长按那一行。
        final String crash = CrashLog.last(this);
        if (crash.length() > 0) {
            String type = crash;
            int p = crash.indexOf("类型 ");
            if (p >= 0) {
                int e = crash.indexOf('\n', p);
                type = crash.substring(p + 3, e < 0 ? crash.length() : e);
            }
            sb.append("上次崩溃：").append(type).append("（技术细节里看栈）\n");
        }

        if (!full) return sb.toString();

        sb.append("\n—— 技术细节 ——\n");
        if (crash.length() > 0) {
            sb.append("—— 上次崩溃 ——\n").append(crash).append('\n');
        }

        // 本次加载过的域名。
        //
        // 加这个不是为了排查故障，是为了收集黑名单：要拦广告，先得知道这个站到底在跟谁说话。
        // 没有这份名单，配置里的 blocklist 就只能靠猜，而猜错的代价是页面打不开。
        //
        // 按加载顺序列，不按次数——判断「是不是广告」看域名本身（ads. / track. / 统计平台）
        // 比看频次准得多，一个 CDN 的加载次数永远比广告域名高。
        //
        // 只在技术细节里显示：默认那份是会被随手截图发出去的，域名列表等于访问记录。
        try {
            String[] hs;
            synchronized (cookieHosts) {
                hs = cookieHosts.toArray(new String[0]);
            }
            // 只列第三方域名。主站自己的域名没必要出现——拦它等于白屏，
            // 而且它们必然占满列表，把真正要看的挤出去。
            java.util.ArrayList<String> third = new java.util.ArrayList<String>();
            for (int i = 0; i < hs.length; i++) {
                String h = hs[i];
                if (homeHost.length() > 0) {
                    String lh = h.toLowerCase(java.util.Locale.US);
                    if (lh.equals(homeHost) || lh.endsWith("." + homeHost)) continue;
                }
                third.add(h);
            }
            sb.append("—— 第三方域名（").append(third.size())
                    .append(" / 共 ").append(hs.length).append("）——\n");
            if (third.isEmpty()) {
                sb.append("（还没加载过页面）\n");
            } else {
                for (int i = 0; i < third.size(); i++) {
                    sb.append(third.get(i));
                    if (looksLikeTracker(third.get(i))) sb.append("   ← 统计/广告");
                    sb.append('\n');
                }
            }
            sb.append("（要拦哪个，把这些行发给后端，写进 config.blocklist）\n");
        } catch (Throwable ignored) {
        }
        sb.append("系统：Android ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append("\n");
        String wv = "未知";
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) wv = pi.packageName + " " + pi.versionName;
            }
        } catch (Throwable ignored) {
        }
        sb.append("系统 WebView：").append(wv).append("\n");
        sb.append("设备档案：").append(profile.brief()).append("\n");
        sb.append("指纹伪装：").append(onOff(fpEnabled))
                .append(" / HTML 注入：").append(onOff(htmlInject))
                .append(" / Canvas：").append(onOff(canvasNoise)).append("\n");
        sb.append("当前地址：").append(Diag.maskUrl(currentUrl == null ? home : currentUrl)).append("\n");
        sb.append("缓存占用：").append(dirSize(getCacheDir()) / 1024).append(" KB\n");
        sb.append("本机 WebView：Chrome ").append(systemChromeMajor())
                .append("（TLS/HTTP2 指纹由此决定，APP 无法改）\n");
        sb.append("当前 UA Chrome：")
                .append(profile.chrome.length() > 0 ? profile.chrome : "原生")
                .append(" · 版本对齐：").append(onOff(tlsAlign)).append("\n");
        sb.append("机型池：").append(UaFactory.deviceCount()).append(" 款 ")
                .append(UaFactory.yearRange()).append(" 年机型\n");
        sb.append("嗅探结果：视频 ").append(MediaSniffer.count(MediaSniffer.CAT_VIDEO))
                .append(" · 音频 ").append(MediaSniffer.count(MediaSniffer.CAT_AUDIO))
                .append(" · 图片 ").append(MediaSniffer.count(MediaSniffer.CAT_IMAGE))
                .append(" 条\n");
        sb.append("本地加密：").append(VaultCrypto.describe(this))
                .append(VaultCrypto.selfTest(this) ? "（自检通过）" : "（自检未通过）").append("\n");
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                android.app.NotificationChannel ch =
                        nm.getNotificationChannel("huandou_download_v4");
                sb.append("通知渠道：").append(ch == null ? "未创建（还没下载过）"
                        : "重要性 " + ch.getImportance()
                          + (ch.getImportance() <= android.app.NotificationManager.IMPORTANCE_LOW
                             ? "（会被折叠，不会出现在状态栏）" : "（会在状态栏显示图标）")).append("\n");
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        File[] a = f.listFiles();
        if (a == null) return 0;
        long n = 0;
        for (File x : a) n += x.isDirectory() ? dirSize(x) : x.length();
        return n;
    }

    // ---------------- Find in page ----------------

    private TextView makeFindButton(String t, final Boolean forward) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFFE6DDF5);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                if (forward == null) hideFindBar();
                else web.findNext(forward.booleanValue());
            }
        });
        return v;
    }

    private void showFindBar() {
        if (findBar == null) {
            findBar = new LinearLayout(this);
            findBar.setOrientation(LinearLayout.HORIZONTAL);
            findBar.setGravity(Gravity.CENTER_VERTICAL);
            findBar.setBackgroundColor(BAR);
            findBar.setPadding(dp(10), dp(4), dp(6), dp(4));

            findInput = new EditText(this);
            findInput.setHint("输入要查找的文字");
            findInput.setSingleLine(true);
            findInput.setTextColor(0xFFF3EFFA);
            findInput.setHintTextColor(0xFF9A8FAE);
            findInput.setBackgroundColor(0x00000000);
            findInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                    String q = s.toString();
                    if (q.length() > 0) web.findAllAsync(q);
                    else web.clearMatches();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
            findBar.addView(findInput, new LinearLayout.LayoutParams(0, MP, 1f));
            findBar.addView(makeFindButton("↑", Boolean.FALSE), new LinearLayout.LayoutParams(WC, MP));
            findBar.addView(makeFindButton("↓", Boolean.TRUE), new LinearLayout.LayoutParams(WC, MP));
            findBar.addView(makeFindButton("×", null), new LinearLayout.LayoutParams(WC, MP));
        }
        if (findBar.getParent() == null) {
            mainBox.addView(findBar, 2, new LinearLayout.LayoutParams(MP, dp(44)));
        }
        findInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideFindBar() {
        if (findBar != null && findBar.getParent() != null) mainBox.removeView(findBar);
        web.clearMatches();
        if (findInput != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(findInput.getWindowToken(), 0);
        }
    }

    // ---------------- Long press / share ----------------

    private void showLongPressMenu(final String url, final boolean isImage) {
        if (url == null || url.length() == 0) return;
        String[] items = isImage
                ? new String[]{"保存图片", "复制图片链接"}
                : new String[]{"复制链接", "用外部浏览器打开"};
        showGlass(new AlertDialog.Builder(this)
                .setTitle(isImage ? "图片" : "链接")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (isImage && w == 0) {
                            downloadViaManager(url, profile.ua, null, "image/*");
                        } else if (w == (isImage ? 1 : 0)) {
                            copyToClipboard(url);
                        } else {
                            openExternal(url);
                        }
                    }
                })
                );
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", text));
            toast("已复制");
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void sharePage() {
        try {
            String url = currentUrl == null ? home : currentUrl;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "幻豆");
            i.putExtra(Intent.EXTRA_TEXT,
                    (web.getTitle() == null ? "幻豆" : web.getTitle()) + "  " + url);
            startActivity(Intent.createChooser(i, "分享页面"));
        } catch (Throwable t) {
            toast("分享失败");
        }
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("无法打开外部浏览器");
        }
    }

    // ---------------- Fullscreen video ----------------

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            try {
                callback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
            return;
        }
        customView = view;
        customCallback = callback;
        hideSplash();
        hideError();
        toolbar.setVisibility(View.GONE);
        fullscreenHost.setVisibility(View.VISIBLE);
        fullscreenHost.bringToFront();
        fullscreenHost.addView(view, new FrameLayout.LayoutParams(MP, MP, Gravity.CENTER));
        setFullscreenFlags(true);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreenHost.removeAllViews();
        fullscreenHost.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) {
            try {
                customCallback.onCustomViewHidden();
            } catch (Throwable ignored) {
            }
            customCallback = null;
        }
        applyImmersive(immersive);
    }

    private void setFullscreenFlags(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController c = d.getWindowInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                getWindow().setStatusBarColor(Color.TRANSPARENT);
                getWindow().setNavigationBarColor(Color.TRANSPARENT);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                getWindow().setNavigationBarDividerColor(Color.TRANSPARENT);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(true);
                WindowInsetsController c = d.getWindowInsetsController();
                if (c != null) c.show(WindowInsets.Type.systemBars());
            } else {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            if (Build.VERSION.SDK_INT >= 21) {
                getWindow().setStatusBarColor(BRAND);
                getWindow().setNavigationBarColor(BRAND);
            }
        }
    }

    private void applyImmersive(boolean on) {
        immersive = on;
        if (toolbar != null) toolbar.setVisibility(on ? View.GONE : View.VISIBLE);
        if (progressLine != null) progressLine.setVisibility(View.GONE);
        setFullscreenFlags(on);
        pref.edit().putBoolean(KEY_IMMERSIVE, on).apply();
        if (on) {
            toast("沉浸模式：按返回键退出，或到菜单里关掉");
        }
    }

    // ---------------- 隐私 / 阅读 / 书签 ----------------

    /** 清 Cookie、缓存、历史、本地存储与表单。无痕退出和手动清理共用。 */
    private void wipeBrowsingData() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable ignored) {
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }
        try {
            if (web != null) {
                web.clearCache(true);
                web.clearHistory();
                web.clearFormData();
                web.clearSslPreferences();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 无痕模式：不接收 Cookie（含第三方），退出时清空本地数据。
     *
     * <p>开启时顺带把指纹伪装与 Canvas 扰动打开——这两项本来就是隐私项，
     * 只是平时可能被用户关掉了。兼容模式下不改指纹，避免与「一键修复」打架。</p>
     */
    private void setIncognito(boolean on) {
        incognito = on;
        pref.edit().putBoolean(KEY_INCOGNITO, on).apply();
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(!on);
            if (web != null) cm.setAcceptThirdPartyCookies(web, !on);
        } catch (Throwable ignored) {
        }
        if (on) {
            wipeBrowsingData();
            if (!compatMode) {
                if (!fpEnabled) {
                    fpEnabled = true;
                    pref.edit().putBoolean(KEY_FP, true).apply();
                }
                if (!canvasNoise) {
                    canvasNoise = true;
                    pref.edit().putBoolean(KEY_CANVAS, true).apply();
                }
                syncCompatAfterOverride();
            }
            toast("无痕已开启：不保存 Cookie，退出时清空浏览数据");
        } else {
            toast("无痕已关闭，Cookie 恢复保存");
        }
        try {
            if (web != null) web.reload();
        } catch (Throwable ignored) {
        }
    }

    private void setForceDark(boolean on) {
        forceDark = on;
        pref.edit().putBoolean(KEY_DARK, on).apply();
        try {
            if (web != null) {
                web.evaluateJavascript(on ? Extras.DARK_JS : Extras.UNDARK_JS, null);
            }
        } catch (Throwable ignored) {
        }
        toast(on ? "深色已开启（本页立即生效，以后自动应用）" : "深色已关闭");
    }

    // ---------- 安全浏览 ----------

    /**
     * 本地情报库的加载与自动更新。
     *
     * <p>库文件只有「域名指纹」没有域名原文，一条 8 字节，十几万条也就 1MB 出头，
     * 所以直接读进内存做二分查找，访问时零延迟、零联网。</p>
     */
    private void loadSafeStores() {
        loadAgeCache();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SafeBrowse.Store p = SafeBrowse.load(
                        SafeBrowseFeed.phishFile(MainActivity.this), "钓鱼站点库");
                final SafeBrowse.Store m = SafeBrowse.load(
                        SafeBrowseFeed.malwareFile(MainActivity.this), "恶意软件库");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        phishStore = p;
                        malwareStore = m;
                        maybeAutoUpdateFeed(p.size() + m.size());
                    }
                });
            }
        }, "huandou-safe-load").start();
    }

    /** 库为空、或一周没更新时，在 WiFi 下悄悄拉一次。 */
    private void maybeAutoUpdateFeed(int total) {
        long last = pref.getLong(SafeBrowseFeed.KEY_TRY_TS, 0);
        long now = System.currentTimeMillis();
        if (total > 0 && now - last < 7L * 24 * 3600 * 1000) return;
        if (now - last < 6L * 3600 * 1000) return;
        if (!isUnmetered()) return;
        pref.edit().putLong(SafeBrowseFeed.KEY_TRY_TS, now).apply();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshSafeFeed(true);
            }
        }, 12000);
    }

    /** 只在「不计流量」的网络（WiFi / 以太网）下自动下载，别偷跑用户流量。 */
    private boolean isUnmetered() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null
                    && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (Throwable t) {
            return false;
        }
    }

    private void confirmRefreshFeed() {
        showGlass(new AlertDialog.Builder(this)
                .setTitle("更新本地情报库")
                .setMessage("从 Phishing Army（聚合 PhishTank、OpenPhish、urlscan.io 等 20 多家）、"
                        + "URLhaus、OpenPhish 下载约 3 MB 的域名列表。\n\n"
                        + "列表整包存在手机里，命中判断完全离线，情报源收不到你的浏览记录。"
                        + "以后每周有 WiFi 时会自动更新一次。")
                .setPositiveButton("开始更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        refreshSafeFeed(false);
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        showSafeSettings();
                    }
                }));
    }

    private void refreshSafeFeed(final boolean silent) {
        if (SafeBrowseFeed.isRunning()) {
            if (!silent) toast("正在更新，稍等一下");
            return;
        }
        // 记下这次尝试，免得自动更新在 12 秒后又拉一遍同样的几 MB。
        pref.edit().putLong(SafeBrowseFeed.KEY_TRY_TS, System.currentTimeMillis()).apply();
        SafeBrowseFeed.refresh(this, safeProxy, new SafeBrowseFeed.Callback() {
            @Override
            public void done(int total, String error) {
                phishStore = SafeBrowse.load(
                        SafeBrowseFeed.phishFile(MainActivity.this), "钓鱼站点库");
                malwareStore = SafeBrowse.load(
                        SafeBrowseFeed.malwareFile(MainActivity.this), "恶意软件库");
                if (error == null) {
                    if (!silent) toast("情报库已更新：共 " + total + " 条");
                } else if (!silent) {
                    toast(error);
                }
                if (!silent) showSafeSettings();
            }
        });
    }

    private SafeBrowse.Verdict evaluateUrl(String url) {
        return SafeBrowse.evaluate(url,
                pref.getString(KEY_BLACKLIST, ""),
                pref.getString(KEY_WHITELIST, ""),
                home, phishStore, malwareStore);
    }

    /**
     * 访问前看一眼地址。
     *
     * <p>三档处理：命中情报库 → 整页拦截；分数到高危 → 弹窗确认；只是有点可疑
     * → 顶部提示条，不打断阅读。旧版把所有情况都做成弹窗，用户会觉得「怎么老弹」。</p>
     */
    private void checkRisk(String url) {
        if (!safeBrowse || url == null || url.length() == 0) return;
        if (url.startsWith("about:") || url.startsWith("javascript:")
                || url.startsWith("data:") || url.startsWith("blob:")) return;
        if (safeApproved.contains(url)) return;
        if (url.equals(lastRiskUrl)) return;
        SafeBrowse.Verdict v = evaluateUrl(url);
        if (!v.risky()) return;
        lastRiskUrl = url;
        if (v.level == SafeBrowse.BLOCK || (safeStrict && v.level == SafeBrowse.WARN)) {
            showBlockPage(v, url);
        } else if (v.level == SafeBrowse.WARN) {
            showRiskDialog(v, url);
        } else {
            showSafeBanner(v, url);
        }
    }

    private void showRiskDialog(final SafeBrowse.Verdict v, final String url) {
        showGlass(new AlertDialog.Builder(this)
                .setTitle("⚠ " + v.headline())
                .setMessage(SafeBrowse.describe(v)
                        + "\n\n" + shortUrl(url)
                        + "\n\n拿不准就返回。确认没问题再继续访问。")
                .setPositiveButton("仍然访问", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        safeApproved.add(url);
                    }
                })
                .setNeutralButton("加入白名单", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        addWhitelist(v.domain.length() > 0 ? v.domain : v.host);
                        safeApproved.add(url);
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        leaveRiskyPage();
                    }
                })
                );
    }

    /** 整页拦截：盖住 WebView，先让用户停一下，再给「仍然访问」的出口。 */
    private void showBlockPage(final SafeBrowse.Verdict v, final String url) {
        if (root == null) return;
        removeBlockPage();
        blockedUrl = url;
        try {
            if (web != null) web.stopLoading();
        } catch (Throwable ignored) {
        }

        final FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF16090F);
        box.setClickable(true);
        box.setZ(Z_OVERLAY + 5f);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(26), dp(52), dp(26), dp(28));

        TextView mark = new TextView(this);
        mark.setText("⛔");
        mark.setTextSize(44);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable mg = new GradientDrawable();
        mg.setShape(GradientDrawable.OVAL);
        mg.setColor(0x33FF4D6A);
        mg.setStroke(dp(1), 0x66FF6B84);
        mark.setBackground(mg);
        col.addView(mark, new LinearLayout.LayoutParams(dp(86), dp(86)));

        TextView title = new TextView(this);
        title.setText(v.feed.length() > 0 ? "已拦截这个网站" : "这个网站风险很高");
        title.setTextColor(0xFFFF8A9B);
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, dp(6));
        col.addView(title);

        TextView host = new TextView(this);
        host.setText(v.host);
        host.setTextColor(0xFFE7D9E2);
        host.setTextSize(14);
        host.setGravity(Gravity.CENTER);
        host.setTextIsSelectable(true);
        col.addView(host);

        TextView kind = new TextView(this);
        kind.setText(v.feed.length() > 0
                ? "本地情报库命中 · " + v.feed
                : "本地风险评分 " + v.score + "/100");
        kind.setTextColor(0xFF9C8794);
        kind.setTextSize(11.5f);
        kind.setGravity(Gravity.CENTER);
        kind.setPadding(0, dp(6), 0, dp(16));
        col.addView(kind);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(0x1FFFFFFF);
        lb.setCornerRadius(dp(14));
        lb.setStroke(dp(1), 0x2EFFFFFF);
        list.setBackground(lb);
        list.setPadding(dp(14), dp(12), dp(14), dp(12));
        for (int i = 0; i < v.reasons.size(); i++) {
            TextView r = new TextView(this);
            r.setText("· " + v.reasons.get(i));
            r.setTextColor(0xFFD8CBD5);
            r.setTextSize(12.5f);
            r.setLineSpacing(dp(3), 1f);
            r.setPadding(0, dp(3), 0, dp(3));
            list.addView(r);
        }
        col.addView(list, new LinearLayout.LayoutParams(MP, WC));

        TextView tip = new TextView(this);
        tip.setText("这个页面没有加载。网址里的内容可能伪装成银行、支付或快递页面，"
                + "用来骗取账号、短信验证码或诱导安装应用。");
        tip.setTextColor(0xFF9C8794);
        tip.setTextSize(11.5f);
        tip.setLineSpacing(dp(3), 1f);
        tip.setPadding(dp(2), dp(16), dp(2), dp(18));
        col.addView(tip);

        final TextView back = new TextView(this);
        back.setText("返回上一页");
        back.setTextColor(0xFFFFFFFF);
        back.setTextSize(15);
        back.setTypeface(null, Typeface.BOLD);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, dp(13), 0, dp(13));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF7A4BE0, 0xFF4B2E9E});
        bg.setCornerRadius(dp(13));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), bg, null));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                removeBlockPage();
                leaveRiskyPage();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(MP, WC);
        blp.topMargin = dp(2);
        col.addView(back, blp);

        final TextView go = new TextView(this);
        go.setText("仍然访问（5）");
        go.setTextColor(0xFFB8A6B2);
        go.setTextSize(12.5f);
        go.setGravity(Gravity.CENTER);
        go.setPadding(0, dp(13), 0, dp(13));
        go.setEnabled(false);
        col.addView(go, new LinearLayout.LayoutParams(MP, WC));

        final TextView wl = new TextView(this);
        wl.setText("加入白名单");
        wl.setTextColor(0xFF9B8AA6);
        wl.setTextSize(12);
        wl.setGravity(Gravity.CENTER);
        wl.setPadding(0, dp(6), 0, 0);
        wl.setClickable(true);
        wl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                addWhitelist(v.domain.length() > 0 ? v.domain : v.host);
                safeApproved.add(url);
                removeBlockPage();
                if (web != null) web.loadUrl(url);
            }
        });
        col.addView(wl, new LinearLayout.LayoutParams(MP, WC));

        sv.addView(col, new FrameLayout.LayoutParams(MP, WC));
        box.addView(sv, new FrameLayout.LayoutParams(MP, MP));
        root.addView(box, new FrameLayout.LayoutParams(MP, MP));
        blockView = box;

        // 给 5 秒冷静期：拦截页如果一秒钟就能点穿，等于没拦。
        final int[] left = {5};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (blockView == null) return;
                left[0]--;
                if (left[0] > 0) {
                    go.setText("仍然访问（" + left[0] + "）");
                    handler.postDelayed(this, 1000);
                } else {
                    go.setText("仍然访问（不推荐）");
                    go.setTextColor(0xFFE0CFD9);
                    go.setEnabled(true);
                    go.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View x) {
                            safeApproved.add(url);
                            removeBlockPage();
                            if (web != null) web.loadUrl(url);
                        }
                    });
                }
            }
        };
        handler.postDelayed(tick, 1000);
    }

    private void removeBlockPage() {
        if (blockView != null && blockView.getParent() != null) {
            ((ViewGroup) blockView.getParent()).removeView(blockView);
        }
        blockView = null;
    }

    private void leaveRiskyPage() {
        try {
            if (web != null && web.canGoBack()) web.goBack();
            else goHome();
        } catch (Throwable ignored) {
        }
    }

    /** 顶部提示条：只提示，不夺走点击。点一下才展开详情。 */
    private void showSafeBanner(final SafeBrowse.Verdict v, final String url) {
        if (root == null || !v.risky()) return;
        hideSafeBanner();
        final TextView t = new TextView(this);
        String first = v.reasons.isEmpty() ? "地址特征可疑" : v.reasons.get(0);
        t.setText("⚠ " + v.headline() + "\n" + first + " · 点开细看");
        t.setTextColor(0xFFFFE3D6);
        t.setTextSize(11.5f);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF22A1512);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), 0x66FF9A76);
        t.setBackground(g);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                hideSafeBanner();
                showRiskDialog(v, url);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MP, WC);
        lp.gravity = Gravity.TOP;
        lp.topMargin = dp(62);
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        t.setZ(Z_OVERLAY + 4f);
        root.addView(t, lp);
        safeBanner = t;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSafeBanner();
            }
        }, 9000);
    }

    private void hideSafeBanner() {
        if (safeBanner != null && safeBanner.getParent() != null) {
            ((ViewGroup) safeBanner.getParent()).removeView(safeBanner);
        }
        safeBanner = null;
    }

    private String blockedUrl;

    // ---- 页面侧特征（VirusDetector 式本地评分） ----

    /** 注入脚本报上来的页面结构特征。 */
    private void handlePageRisk(String url, SafeBrowse.PageSignals ps) {
        // WebView 报的 URL 和 location.href 可能差一个锚点或末尾斜杠，按主机名对齐。
        if (!url.equals(currentUrl)) {
            String ha = SafeBrowse.hostFromUrl(url);
            String hb = SafeBrowse.hostFromUrl(currentUrl);
            if (ha == null || !ha.equals(hb)) return;
            currentUrl = url;
        }
        lastSignals = ps;
        lastSignalsUrl = url;
        SafeBrowse.Verdict v = evaluateUrl(url);
        Long reg = safeAgeCache.get(v.domain);
        if (reg != null && reg > 0) {
            v.ageDays = (int) ((System.currentTimeMillis() - reg) / 86400000L);
        }
        SafeBrowse.applyPage(v, ps);
        if (pendingPageReport) {
            pendingPageReport = false;
            showPageReport(v, ps, url);
            return;
        }
        if (v.risky() && !url.equals(lastRiskUrl)) {
            lastRiskUrl = url;
            showSafeBanner(v, url);
        }
        // 只有「页面在收密码」这种明确信号才值得去查域名年龄，
        // 而且只把注册域名发出去，不带路径、参数、Referer 和 Cookie。
        if (safeRdap && ps.passwords > 0 && v.ageDays < 0) {
            lookupDomainAge(v.domain, url, ps);
        }
    }

    private SafeBrowse.PageSignals lastSignals;
    private String lastSignalsUrl;

    private void lookupDomainAge(final String domain, final String url,
                                 final SafeBrowse.PageSignals ps) {
        if (!safeRdap || domain == null || domain.length() < 4) return;
        if (SafeBrowse.isIp(domain)) return;
        if (safeAgeCache.containsKey(domain)) return;
        safeAgeCache.put(domain, -1L);   // 占位，避免同一页重复发起
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long reg = fetchRegistrationTime(domain);
                safeAgeCache.put(domain, reg);
                saveAgeCache();
                if (reg > 0) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!url.equals(currentUrl)) return;
                            SafeBrowse.Verdict v = evaluateUrl(url);
                            v.ageDays = (int) ((System.currentTimeMillis() - reg) / 86400000L);
                            SafeBrowse.applyPage(v, ps);
                            if (v.risky()) {
                                lastRiskUrl = url;
                                showSafeBanner(v, url);
                            }
                        }
                    });
                }
            }
        }, "huandou-rdap").start();
    }

    /**
     * 向 RDAP 查域名注册时间。RDAP 是域名注册局的公开协议，不需要 Key。
     * 这里只发送「注册域名」本身（例如 example.com），不带完整网址。
     */
    private long fetchRegistrationTime(String domain) {
        HttpURLConnection c = null;
        try {
            String api = "https://rdap.org/domain/" + java.net.URLEncoder.encode(domain, "UTF-8");
            String target = api;
            if (safeProxy != null && safeProxy.trim().length() > 6) {
                target = safeProxy.trim() + java.net.URLEncoder.encode(api, "UTF-8");
            }
            c = (HttpURLConnection) new java.net.URL(target).openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Huandou/" + appVersion());
            c.setRequestProperty("Accept", "application/rdap+json,application/json");
            if (c.getResponseCode() != 200) return -1;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 512 * 1024) break;
            }
            in.close();
            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            JSONArray ev = o.optJSONArray("events");
            if (ev == null) return -1;
            for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e == null) continue;
                if ("registration".equalsIgnoreCase(e.optString("eventAction", ""))) {
                    return parseIsoUtc(e.optString("eventDate", ""));
                }
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "1995-05-04T04:00:00Z" → 毫秒。不引入 SimpleDateFormat 的时区坑，手动按 UTC 算。 */
    private long parseIsoUtc(String s) {
        try {
            if (s == null || s.length() < 19) return -1;
            int y = Integer.parseInt(s.substring(0, 4));
            int mo = Integer.parseInt(s.substring(5, 7));
            int d = Integer.parseInt(s.substring(8, 10));
            int h = Integer.parseInt(s.substring(11, 13));
            int mi = Integer.parseInt(s.substring(14, 16));
            int se = Integer.parseInt(s.substring(17, 19));
            java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(y, mo - 1, d, h, mi, se);
            return c.getTimeInMillis();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void loadAgeCache() {
        try {
            JSONObject o = new JSONObject(pref.getString(KEY_SAFE_AGE, "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                safeAgeCache.put(k, o.optLong(k, -1));
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveAgeCache() {
        try {
            JSONObject o = new JSONObject();
            int n = 0;
            for (Map.Entry<String, Long> e : safeAgeCache.entrySet()) {
                if (n++ >= 300) break;
                o.put(e.getKey(), e.getValue());
            }
            pref.edit().putString(KEY_SAFE_AGE, o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private void addWhitelist(String host) {
        if (host == null || host.length() < 3) return;
        String cur = pref.getString(KEY_WHITELIST, "");
        for (String s : cur.split("[,\\s]+")) {
            if (host.equalsIgnoreCase(s.trim())) {
                toast("已经在白名单里了");
                return;
            }
        }
        String next = cur.trim().length() == 0 ? host : cur.trim() + "\n" + host;
        pref.edit().putString(KEY_WHITELIST, next).apply();
        toast("已加入白名单：" + host);
    }

    // ---------- 安全浏览设置面板 ----------

    private void showSafeSettings() {
        final Dialog d = new Dialog(this, R.style.GlassDialogTheme);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE8261836, 0xE6120C1C});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x40FFFFFF);
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(12), dp(12));
        TextView logo = new TextView(this);
        logo.setText("盾");
        logo.setTextColor(0xFFFFFFFF);
        logo.setTextSize(14);
        logo.setTypeface(null, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF56C596, 0xFF2C7A63});
        lg.setCornerRadius(dp(11));
        logo.setBackground(lg);
        head.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(11), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText("安全浏览");
        t1.setTextColor(0xFFF3EFFA);
        t1.setTextSize(17);
        t1.setTypeface(null, Typeface.BOLD);
        titles.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("本地情报 " + (phishStore == null ? 0 : phishStore.size()
                + (malwareStore == null ? 0 : malwareStore.size()))
                + " 条 · 不联网查询你的浏览记录");
        t2.setTextColor(0xFFA99FB8);
        t2.setTextSize(10);
        titles.addView(t2);
        head.addView(titles, new LinearLayout.LayoutParams(0, WC, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFC9BEDD);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        card.addView(head, new LinearLayout.LayoutParams(MP, WC));

        View line = new View(this);
        line.setBackgroundColor(0x22FFFFFF);
        card.addView(line, new LinearLayout.LayoutParams(MP, Math.max(1, dp(1))));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(4), dp(8), dp(10));

        rows.addView(safeRow("拦截开关", safeBrowse ? "开 · 正在保护" : "关 · 只显示不拦截",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        safeBrowse = !safeBrowse;
                        pref.edit().putBoolean(KEY_SAFE, safeBrowse).apply();
                        toast(safeBrowse ? "安全浏览已开启" : "安全浏览已关闭");
                        d.dismiss();
                        showSafeSettings();
                    }
                }));

        rows.addView(safeRow("拦截强度", safeStrict
                        ? "严格 · 高危站点直接拦截页"
                        : "提示 · 高危站点先问一句",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        safeStrict = !safeStrict;
                        pref.edit().putBoolean(KEY_SAFE_STRICT, safeStrict).apply();
                        d.dismiss();
                        showSafeSettings();
                    }
                }));

        rows.addView(safeRow("本地威胁情报库", SafeBrowseFeed.describe(this)
                        + (SafeBrowseFeed.isRunning() ? " · 更新中…" : ""),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        confirmRefreshFeed();
                    }
                }));

        rows.addView(safeRow("域名年龄（RDAP）", (safeRdap ? "开" : "关")
                        + " · 只发送注册域名，不带网址与 Cookie",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        safeRdap = !safeRdap;
                        pref.edit().putBoolean(KEY_SAFE_RDAP, safeRdap).apply();
                        toast(safeRdap ? "已开启域名年龄查询" : "已关闭，查询完全不联网");
                        d.dismiss();
                        showSafeSettings();
                    }
                }));

        rows.addView(safeRow("查当前站点域名年龄", "手动查一次，结果缓存在本机",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String u = web == null ? null : web.getUrl();
                        String h = SafeBrowse.hostFromUrl(u);
                        if (h == null) {
                            toast("当前页不是 http 页面");
                            return;
                        }
                        String dom = SafeBrowse.registrableDomain(h);
                        d.dismiss();
                        queryAgeManually(dom);
                    }
                }));

        rows.addView(safeRow("检查当前页面", "看这一页具体哪里不对劲",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        checkCurrentPage();
                    }
                }));

        rows.addView(safeRow("情报查询代理（可选）",
                        safeProxy == null || safeProxy.trim().length() == 0
                                ? "未设置 · 直连情报源"
                                : "已设置 · 源站只看到代理 IP",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        promptProxy();
                    }
                }));

        final String wl = pref.getString(KEY_WHITELIST, "");
        int wlCount = 0;
        for (String s : wl.split("[,\\s]+")) if (s.trim().length() > 2) wlCount++;
        rows.addView(safeRow("白名单", wlCount == 0 ? "暂无 · 长按列表可管理" : wlCount + " 个站点永不拦截",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        showWhitelistManager();
                    }
                }));

        String bl = pref.getString(KEY_BLACKLIST, "");
        rows.addView(safeRow("自定义黑名单",
                        bl.trim().length() == 0 ? "空 · 填域名关键词，命中即拦" : "已配置 " + bl.trim().length() + " 个字符",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        promptBlacklist();
                    }
                }));

        TextView note = new TextView(this);
        note.setText("情报库是把公开的钓鱼 / 恶意域名列表整包下载到手机，"
                + "比对在本地完成——没有任何一家情报提供方知道你访问了什么。\n\n"
                + "域名年龄来自 RDAP（域名注册局的公开协议），只在页面出现密码框时才查，"
                + "且只发送注册域名。要连这一条也不想暴露，可以在上面填一个自建代理前缀。");
        note.setTextColor(0xFF8E839C);
        note.setTextSize(10.5f);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(dp(16), dp(14), dp(16), dp(6));
        rows.addView(note);

        sv.addView(rows, new FrameLayout.LayoutParams(MP, WC));
        card.addView(sv, new LinearLayout.LayoutParams(MP, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("关闭");
        foot.setTextColor(0xFFE6DDF5);
        foot.setTextSize(13);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(0xFF2A1C3A);
        fg.setCornerRadius(dp(12));
        fg.setStroke(dp(1), 0x33FFFFFF);
        foot.setBackground(fg);
        foot.setClickable(true);
        foot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(MP, WC);
        flp.setMargins(dp(18), dp(6), dp(18), dp(16));
        card.addView(foot, flp);

        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (dm.widthPixels * 0.90f);
            lp.height = (int) (dm.heightPixels * 0.78f);
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        attachGlass(d);
    }

    // ---------- 用户脚本（油猴）----------

    private void showUserscripts() {
        final Dialog d = new Dialog(this, R.style.GlassDialogTheme);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE8261836, 0xE6120C1C});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x40FFFFFF);
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(16), dp(12), dp(12));
        TextView logo = new TextView(this);
        logo.setText("猴");
        logo.setTextColor(0xFF1B1020);
        logo.setTextSize(14);
        logo.setTypeface(null, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFC46B, 0xFFE08A3C});
        lg.setCornerRadius(dp(11));
        logo.setBackground(lg);
        head.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        ArrayList<Userscript> list = userscripts == null
                ? new ArrayList<Userscript>() : userscripts.all();
        int on = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).enabled) on++;

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(11), 0, 0, 0);
        TextView t1 = new TextView(this);
        t1.setText("用户脚本");
        t1.setTextColor(0xFFF3EFFA);
        t1.setTextSize(17);
        t1.setTypeface(null, Typeface.BOLD);
        titles.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(list.size() == 0 ? "还没有脚本 · 兼容 .user.js"
                : ("已装 " + list.size() + " 个 · 启用 " + on + " 个"));
        t2.setTextColor(0xFFA99FB8);
        t2.setTextSize(10);
        titles.addView(t2);
        head.addView(titles, new LinearLayout.LayoutParams(0, WC, 1f));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFC9BEDD);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        card.addView(head, new LinearLayout.LayoutParams(MP, WC));

        View line = new View(this);
        line.setBackgroundColor(0x22FFFFFF);
        card.addView(line, new LinearLayout.LayoutParams(MP, Math.max(1, dp(1))));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(4), dp(8), dp(10));

        rows.addView(safeRow("脚本总开关", userscriptsOn ? "开 · 匹配到页面就注入" : "关 · 全部暂停",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        userscriptsOn = !userscriptsOn;
                        pref.edit().putBoolean(KEY_US, userscriptsOn).apply();
                        toast(userscriptsOn ? "用户脚本已开启" : "用户脚本已关闭，刷新生效");
                        d.dismiss();
                        showUserscripts();
                    }
                }));

        rows.addView(safeRow("首帧注入（document-start）",
                userscriptsStart
                        ? "开 · 站点脚本执行前注入，每页多一次 HTML 请求"
                        : "关 · 只在页面就绪后注入，个别反广告脚本可能失效",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        userscriptsStart = !userscriptsStart;
                        pref.edit().putBoolean(KEY_US_START, userscriptsStart).apply();
                        toast(userscriptsStart ? "已开启首帧注入" : "已关闭首帧注入，刷新生效");
                        d.dismiss();
                        showUserscripts();
                    }
                }));

        if (list.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有装脚本。下面任意一种方式都行，"
                    + "装好后会在匹配的页面上自动运行。");
            empty.setTextColor(0xFF9C90AC);
            empty.setTextSize(11);
            empty.setLineSpacing(dp(2), 1f);
            empty.setPadding(dp(16), dp(12), dp(16), dp(12));
            rows.addView(empty);
        } else {
            for (int i = 0; i < list.size(); i++) {
                final Userscript u = list.get(i);
                StringBuilder sub = new StringBuilder();
                sub.append(u.enabled ? "● 启用" : "○ 停用");
                if (u.version.length() > 0) sub.append(" · v").append(u.version);
                sub.append(" · ").append(u.ruleCount()).append(" 条匹配规则");
                if (Userscript.RUN_START.equals(u.runAt)) sub.append(" · 首帧");
                String here = web == null ? null : web.getUrl();
                if (here != null && u.appliesTo(here)) sub.append(" · 本页生效");
                rows.addView(safeRow(u.displayName(), sub.toString(),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                showUserscriptDetail(u);
                            }
                        }));
            }
        }

        rows.addView(safeRow("从链接安装…", "贴脚本页地址或 .user.js 直链",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        promptUsInstallUrl();
                    }
                }));

        rows.addView(safeRow("粘贴脚本代码…", "把 ==UserScript== 开头的全文粘进来",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        promptUsPaste();
                    }
                }));

        rows.addView(safeRow("从当前网页提取", "在脚本安装页一键读取源码",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        extractUsFromPage();
                    }
                }));

        rows.addView(safeRow("本页脚本菜单", "脚本自己注册的设置与快捷命令",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        showScriptCommands();
                    }
                }));

        rows.addView(safeRow("逛 GreasyFork", "在幻豆里打开脚本库，点安装即可",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        d.dismiss();
                        // 就在自己的 WebView 里逛：脚本页的「安装此脚本」会被拦下来
                        // 直接走安装流程，不用再跳出去。
                        web.loadUrl("https://greasyfork.org/zh-CN/scripts");
                    }
                }));

        TextView note = new TextView(this);
        note.setText("脚本与页面共享 window，只装信得过的作者。GM 存储独立存放，清站点数据不会丢。");
        note.setTextColor(0xFF8E839C);
        note.setTextSize(10.5f);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(dp(16), dp(12), dp(16), dp(6));
        rows.addView(note);

        sv.addView(rows, new FrameLayout.LayoutParams(MP, WC));
        card.addView(sv, new LinearLayout.LayoutParams(MP, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("关闭");
        foot.setTextColor(0xFFE6DDF5);
        foot.setTextSize(13);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(0xFF2A1C3A);
        fg.setCornerRadius(dp(12));
        fg.setStroke(dp(1), 0x33FFFFFF);
        foot.setBackground(fg);
        foot.setClickable(true);
        foot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(MP, WC);
        flp.setMargins(dp(18), dp(6), dp(18), dp(16));
        card.addView(foot, flp);

        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (dm.widthPixels * 0.90f);
            lp.height = (int) (dm.heightPixels * 0.80f);
            lp.gravity = Gravity.CENTER;
            w.setAttributes(lp);
        }
        attachGlass(d);
    }

    private static String runAtLabel(String r) {
        if (Userscript.RUN_START.equals(r)) return "document-start（页面脚本之前）";
        if (Userscript.RUN_IDLE.equals(r)) return "document-idle（页面空闲）";
        return "document-end（DOM 就绪）";
    }

    private void showUserscriptDetail(final Userscript u) {
        StringBuilder b = new StringBuilder();
        if (u.description.length() > 0) b.append(u.description).append("\n\n");
        b.append("版本：").append(u.version.length() == 0 ? "未标注" : u.version).append("\n");
        if (u.namespace.length() > 0) b.append("命名空间：").append(u.namespace).append("\n");
        b.append("运行时机：").append(runAtLabel(u.runAt)).append("\n");
        b.append("体积：").append(Math.max(1, u.bytes / 1024)).append(" KB\n");
        String here = web == null ? null : web.getUrl();
        if (here != null) {
            b.append("当前页：").append(u.appliesTo(here) ? "命中，正在生效" : "不命中")
                    .append("\n");
        }
        b.append("匹配规则：\n");
        for (int i = 0; i < u.matches.size() && i < 6; i++) {
            b.append("  @match ").append(u.matches.get(i)).append("\n");
        }
        for (int i = 0; i < u.includes.size() && i < 6; i++) {
            b.append("  @include ").append(u.includes.get(i)).append("\n");
        }
        if (u.excludes.size() > 0) {
            b.append("  @exclude ").append(u.excludes.get(0));
            if (u.excludes.size() > 1) b.append(" 等 ").append(u.excludes.size()).append(" 条");
            b.append("\n");
        }
        if (u.requires.size() > 0) {
            b.append("\n依赖库（已缓存）：\n");
            for (int i = 0; i < u.requires.size() && i < 4; i++) {
                b.append("  ").append(u.requires.get(i)).append("\n");
            }
        }
        b.append("\n申请的能力：");
        if (u.grants.size() == 0) {
            b.append("无（只在页面内操作）");
        } else {
            for (int i = 0; i < u.grants.size() && i < 8; i++) {
                if (i > 0) b.append("、");
                b.append(u.grants.get(i).replace("GM_", ""));
            }
        }
        if (u.sourceUrl.length() > 0) {
            b.append("\n\n来源：").append(u.sourceUrl);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));

        TextView tv = new TextView(this);
        tv.setText(b.toString());
        tv.setTextColor(0xFFD9D1E6);
        tv.setTextSize(11.5f);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextIsSelectable(true);
        box.addView(tv, new LinearLayout.LayoutParams(MP, WC));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(14), 0, dp(2));

        TextView toggle = new TextView(this);
        toggle.setText(u.enabled ? "停用" : "启用");
        toggle.setTextColor(0xFFE6DDF5);
        toggle.setTextSize(12);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(0xFF2A1C3A);
        tg.setCornerRadius(dp(10));
        tg.setStroke(dp(1), 0x33FFFFFF);
        toggle.setBackground(tg);
        toggle.setClickable(true);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                userscripts.setEnabled(u.id, !u.enabled);
                toast(!u.enabled ? "已启用：" + u.displayName() : "已停用：" + u.displayName());
                if (usDetailDlg != null) usDetailDlg.dismiss();
                showUserscripts();
            }
        });
        bar.addView(toggle, new LinearLayout.LayoutParams(0, WC, 1f));

        TextView copy = new TextView(this);
        copy.setText("复制源码");
        copy.setTextColor(0xFFE6DDF5);
        copy.setTextSize(12);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0xFF2A1C3A);
        cg.setCornerRadius(dp(10));
        cg.setStroke(dp(1), 0x33FFFFFF);
        copy.setBackground(cg);
        copy.setClickable(true);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("脚本",
                            userscripts.readCode(u)));
                    toast("源码已复制");
                } catch (Throwable t) {
                    toast("复制失败");
                }
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, WC, 1f);
        clp.setMargins(dp(8), 0, dp(8), 0);
        bar.addView(copy, clp);

        TextView del = new TextView(this);
        del.setText("删除");
        del.setTextColor(0xFFFF9A9A);
        del.setTextSize(12);
        del.setGravity(Gravity.CENTER);
        del.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(0xFF3A1C24);
        dg.setCornerRadius(dp(10));
        dg.setStroke(dp(1), 0x44FF8888);
        del.setBackground(dg);
        del.setClickable(true);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGlass(new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除脚本？")
                        .setMessage(u.displayName() + "\n\n脚本正文和它存的 GM 数据都会删掉，"
                                + "站点页面上已经注入的内容要刷新才会消失。")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface x, int w) {
                                userscripts.remove(u.id);
                                toast("已删除");
                                if (usDetailDlg != null) usDetailDlg.dismiss();
                                showUserscripts();
                            }
                        })
                        .setNegativeButton("取消", null));
            }
        });
        bar.addView(del, new LinearLayout.LayoutParams(0, WC, 1f));
        box.addView(bar, new LinearLayout.LayoutParams(MP, WC));

        final Dialog dlg = showGlassD(new AlertDialog.Builder(this)
                .setTitle(u.displayName())
                .setView(box)
                .setNegativeButton("关闭", null));
        // 上面两个按钮的回调需要关掉这个面板，先把引用挂出去。
        usDetailDlg = dlg;
    }

    /** 列出当前页面里脚本用 GM_registerMenuCommand 注册的命令。 */
    private void showScriptCommands() {
        if (web == null) {
            toast("当前没有页面");
            return;
        }
        try {
            web.evaluateJavascript(
                    "(function(){try{var o=window.__hdUsMenus||{},r=[];"
                            + "for(var k in o){if(!o[k])continue;"
                            + "for(var i=0;i<o[k].length;i++)"
                            + "{r.push([k,i,String(o[k][i].name||'')]);}}"
                            + "return JSON.stringify(r);}catch(e){return '[]';}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            String json = "[]";
                            try {
                                Object o = new JSONTokener(v == null ? "\"[]\"" : v).nextValue();
                                json = o == null ? "[]" : String.valueOf(o);
                            } catch (Throwable ignored) {
                            }
                            showUsCommandList(json);
                        }
                    });
        } catch (Throwable t) {
            toast("读取失败");
        }
    }

    private void showUsCommandList(String json) {
        JSONArray a = null;
        try {
            a = new JSONArray(json);
        } catch (Throwable ignored) {
        }
        if (a == null || a.length() == 0) {
            toast("这一页没有脚本注册菜单命令");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(2), dp(4), dp(2));
        for (int i = 0; i < a.length() && i < 20; i++) {
            JSONArray it = a.optJSONArray(i);
            if (it == null) continue;
            final String sid = it.optString(0, "");
            final int idx = it.optInt(1, 0);
            String name = it.optString(2, "");
            TextView row = new TextView(this);
            row.setText(name.length() == 0 ? "命令 " + (i + 1) : name);
            row.setTextColor(0xFFE6DDF5);
            row.setTextSize(13);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF),
                    null, new ColorDrawable(0xFFFFFFFF)));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        web.evaluateJavascript("try{window.__hdUsRunMenu("
                                + JSONObject.quote(sid) + "," + idx + ");}catch(e){}", null);
                        toast("已执行");
                    } catch (Throwable ignored) {
                    }
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(MP, WC));
        }
        showGlass(new AlertDialog.Builder(this)
                .setTitle("本页脚本菜单")
                .setMessage("这些是当前页面脚本自己注册的入口，点一下执行。")
                .setView(box)
                .setNegativeButton("关闭", null));
    }

    private void promptUsInstallUrl() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("https://greasyfork.org/zh-CN/scripts/12345");
        et.setPadding(dp(18), dp(12), dp(18), dp(12));
        showGlass(new AlertDialog.Builder(this)
                .setTitle("从链接安装")
                .setMessage("脚本页地址会自动从网页里抠出源码；"
                        + "填 .user.js 直链则直接下载。")
                .setView(et)
                .setPositiveButton("抓取", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        String u = et.getText().toString().trim();
                        if (u.length() < 8) {
                            toast("地址太短了");
                            return;
                        }
                        if (!u.startsWith("http")) u = "https://" + u;
                        installUsFromUrl(u);
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        showUserscripts();
                    }
                }));
    }

    private void promptUsPaste() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setHint("// ==UserScript==\n// @name 我的脚本\n// @match *://*/*\n// ==/UserScript==");
        et.setMinLines(8);
        et.setPadding(dp(18), dp(12), dp(18), dp(12));
        showGlass(new AlertDialog.Builder(this)
                .setTitle("粘贴脚本")
                .setMessage("整段源码粘进来即可，头部元数据会被自动解析。")
                .setView(et)
                .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        installUsFromText(et.getText().toString(), "");
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        showUserscripts();
                    }
                }));
    }

    private void extractUsFromPage() {
        if (web == null) return;
        final String u = web.getUrl();
        if (u == null) {
            toast("当前没有页面");
            return;
        }
        toast("正在读取本页脚本…");
        try {
            web.evaluateJavascript(
                    "(function(){try{"
                            + "var e=document.querySelector('#script-source,pre.script-source,pre');"
                            + "return e?e.textContent:'';}catch(x){return '';}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            String text = "";
                            try {
                                Object o = new JSONTokener(v == null ? "\"\"" : v).nextValue();
                                text = o == null ? "" : String.valueOf(o);
                            } catch (Throwable t) {
                                text = v == null ? "" : v;
                            }
                            if (text.indexOf("==UserScript==") < 0) {
                                toast("这一页没找到脚本源码，试试脚本的安装页");
                                showUserscripts();
                                return;
                            }
                            installUsFromText(text, u);
                        }
                    });
        } catch (Throwable t) {
            toast("读取失败");
        }
    }

    private void installUsFromUrl(final String url) {
        toast("正在抓取…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String body = UserscriptStore.fetch(url, 2 * 1024 * 1024);
                final String code;
                if (body == null) {
                    code = null;
                } else if (body.indexOf("==UserScript==") >= 0
                        && body.toLowerCase(Locale.US).indexOf("<html") >= 0) {
                    code = Userscript.extractFromHtml(body);
                } else if (body.indexOf("==UserScript==") >= 0) {
                    code = body;
                } else {
                    code = null;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (code == null) {
                            toast("没抓到脚本。GreasyFork 请用脚本页地址，别用列表页");
                            showUserscripts();
                        } else {
                            installUsFromText(code, url);
                        }
                    }
                });
            }
        }).start();
    }

    private void installUsFromText(String text, String src) {
        final String code = text == null ? "" : text;
        if (code.length() < 20) {
            toast("内容太短，不像脚本");
            return;
        }
        if (code.length() > 1024 * 1024) {
            toast("脚本超过 1 MB，太大了");
            return;
        }
        final Userscript u = Userscript.parse(code, src);
        if (u == null) {
            toast("没找到 ==UserScript== 头，不是油猴脚本");
            return;
        }
        StringBuilder b = new StringBuilder();
        if (u.description.length() > 0) b.append(u.description).append("\n\n");
        b.append("版本：").append(u.version.length() == 0 ? "未标注" : u.version).append("\n");
        b.append("时机：").append(runAtLabel(u.runAt)).append("\n");
        b.append("规则：\n");
        for (int i = 0; i < u.matches.size() && i < 5; i++) {
            b.append("  ").append(u.matches.get(i)).append("\n");
        }
        for (int i = 0; i < u.includes.size() && i < 5; i++) {
            b.append("  ").append(u.includes.get(i)).append("\n");
        }
        if (u.ruleCount() == 0) {
            b.append("  （没有 @match / @include，装了也不会运行）\n");
        }
        if (u.requires.size() > 0) {
            b.append("\n会顺带下载 ").append(u.requires.size()).append(" 个依赖库。");
        }
        if (u.grants.size() > 0) {
            b.append("\n申请能力：");
            for (int i = 0; i < u.grants.size() && i < 6; i++) {
                if (i > 0) b.append("、");
                b.append(u.grants.get(i));
            }
        }
        b.append("\n\n脚本会在匹配的网页里以你的登录身份运行。确认来源可信再装。");

        final String summary = b.toString();
        showGlass(new AlertDialog.Builder(this)
                .setTitle("安装 " + u.displayName())
                .setMessage(summary)
                .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        doInstallUs(u, code);
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface x, int w) {
                        showUserscripts();
                    }
                }));
    }

    private void doInstallUs(final Userscript u, final String code) {
        try {
            userscripts.add(u, code);
            toast("已安装：" + u.displayName() + "，刷新生效");
        } catch (Throwable t) {
            toast("安装失败：" + t.getMessage());
            return;
        }
        showUserscripts();
        // 装完就刷新，省得用户还要自己按一下。
        final String cur = web == null ? null : web.getUrl();
        if (cur != null && u.appliesTo(cur)) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (web != null) web.reload();
                    } catch (Throwable ignored) {
                    }
                }
            }, 600);
        }
        if (u.requires.size() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<String> failed = userscripts.fetchRequires(u);
                if (failed.isEmpty()) return;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("有 " + failed.size() + " 个依赖库没下载成功，脚本可能缺函数");
                    }
                });
            }
        }).start();
    }

    private View safeRow(String title, String sub, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(11), dp(14), dp(11));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28FFFFFF),
                null, new ColorDrawable(0xFFFFFFFF)));
        row.setClickable(true);
        row.setOnClickListener(l);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFF0EAF8);
        t.setTextSize(14);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0xFF9C92AA);
        s.setTextSize(11);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(s);
        return row;
    }

    private void queryAgeManually(final String domain) {
        if (domain == null || domain.length() < 4) return;
        if (SafeBrowse.isIp(domain)) {
            toast("IP 地址没有注册时间");
            return;
        }
        toast("正在查 " + domain + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long reg = fetchRegistrationTime(domain);
                safeAgeCache.put(domain, reg);
                saveAgeCache();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (reg <= 0) {
                            toast("这个后缀没有公开的 RDAP 记录（或网络不通）");
                            return;
                        }
                        int days = (int) ((System.currentTimeMillis() - reg) / 86400000L);
                        String when = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                .format(new java.util.Date(reg));
                        showGlass(new AlertDialog.Builder(MainActivity.this)
                                .setTitle("域名年龄")
                                .setMessage(domain + "\n\n注册于 " + when + "，距今 "
                                        + days + " 天。"
                                        + (days < 30 ? "\n\n刚注册的域名做正经生意的可能性很低，"
                                        + "遇到要你登录、付款、装应用的，直接关掉。" : ""))
                                .setPositiveButton("知道了", null));
                    }
                });
            }
        }, "huandou-rdap-manual").start();
    }

    private void promptProxy() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(safeProxy == null ? "" : safeProxy);
        et.setHint("https://你的代理/?u=");
        et.setSingleLine(true);
        et.setPadding(dp(18), dp(12), dp(18), dp(12));
        showGlass(new AlertDialog.Builder(this)
                .setTitle("情报查询代理前缀")
                .setMessage("填一个自建的转发地址，下载情报和查域名年龄时会拼成"
                        + "「前缀 + 编码后的目标地址」。情报源与 RDAP 只能看到代理的 IP。\n\n"
                        + "留空 = 直连。没有自建代理就不要填，乱填会导致更新失败。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString().trim();
                        safeProxy = v;
                        pref.edit().putString(KEY_SAFE_PROXY, v).apply();
                        toast(v.length() == 0 ? "已改为直连" : "代理前缀已保存");
                        showSafeSettings();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showSafeSettings();
                    }
                }));
    }

    private void promptBlacklist() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setText(pref.getString(KEY_BLACKLIST, ""));
        et.setHint("一行一个，可以是域名或关键词\n例如：example.com");
        et.setMinLines(3);
        et.setPadding(dp(18), dp(12), dp(18), dp(12));
        showGlass(new AlertDialog.Builder(this)
                .setTitle("自定义黑名单")
                .setMessage("命中的地址直接整页拦截，优先于其他判断。")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pref.edit().putString(KEY_BLACKLIST,
                                et.getText().toString().trim()).apply();
                        toast("黑名单已保存");
                        showSafeSettings();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showSafeSettings();
                    }
                }));
    }

    /** 手动体检当前页：地址评分 + 页面结构，一起摊开给用户看。 */
    private void checkCurrentPage() {
        if (web == null) return;
        final String u = web.getUrl();
        if (u == null || SafeBrowse.hostFromUrl(u) == null) {
            toast("当前页不是网页");
            return;
        }
        pendingPageReport = true;
        toast("正在检查当前页面…");
        try {
            web.evaluateJavascript("(function(){window.__hdForce=1;})();" + SafeBrowse.PAGE_JS, null);
        } catch (Throwable ignored) {
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pendingPageReport) return;
                pendingPageReport = false;
                showPageReport(evaluateUrl(u), null, u);
            }
        }, 4000);
    }

    private void showPageReport(SafeBrowse.Verdict v, SafeBrowse.PageSignals ps, String url) {
        StringBuilder b = new StringBuilder(SafeBrowse.describe(v));
        if (ps != null) {
            b.append("\n\n页面特征：密码框 ").append(ps.passwords)
                    .append(" 个 · 跨域表单 ").append(ps.crossForms)
                    .append(" 个 · iframe ").append(ps.iframes)
                    .append(" 个（隐藏 ").append(ps.hiddenIframes).append("）");
            if (ps.icp) b.append("\n备案号：").append(ps.icpNo);
            if (ps.downloadLure) b.append("\n有诱导下载的文案");
            if (ps.metaRefresh) b.append("\n打开会自动跳转");
            if (ps.obfuscated) b.append("\n脚本经过混淆");
        }
        if (v.reasons.isEmpty()) {
            b.append("\n\n没发现明显问题。规则是本地启发式，不能保证 100% 准确——"
                    + "真正的钓鱼页也可能长得很干净。");
        }
        showGlass(new AlertDialog.Builder(this)
                .setTitle(v.level >= SafeBrowse.WARN ? "⚠ " + v.headline() : "页面检查")
                .setMessage(b.toString())
                .setPositiveButton("知道了", null)
                .setNegativeButton(v.risky() ? "返回上一页" : null,
                        v.risky() ? new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                leaveRiskyPage();
                            }
                        } : null));
    }

    private void showWhitelistManager() {
        final String cur = pref.getString(KEY_WHITELIST, "");
        final ArrayList<String> list = new ArrayList<String>();
        for (String s : cur.split("[,\\s]+")) {
            if (s.trim().length() > 2) list.add(s.trim());
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), dp(4));

        if (list.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("还没有白名单。\n\n在拦截页或风险提示里点「加入白名单」，"
                    + "那个域名以后就不会再被拦。");
            t.setTextColor(0xFFB7ABC9);
            t.setTextSize(12.5f);
            t.setLineSpacing(dp(3), 1f);
            box.addView(t);
        } else {
            for (int i = 0; i < list.size(); i++) {
                final String host = list.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));
                TextView t = new TextView(this);
                t.setText(host);
                t.setTextColor(0xFFE9E2F5);
                t.setTextSize(13);
                row.addView(t, new LinearLayout.LayoutParams(0, WC, 1f));
                TextView del = new TextView(this);
                del.setText("移除");
                del.setTextColor(0xFFFF8A9B);
                del.setTextSize(12);
                del.setPadding(dp(10), dp(4), dp(6), dp(4));
                del.setClickable(true);
                del.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        removeWhitelist(host);
                        showWhitelistManager();
                    }
                });
                row.addView(del);
                box.addView(row);
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box);
        showGlass(new AlertDialog.Builder(this)
                .setTitle("白名单")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pref.edit().putString(KEY_WHITELIST, "").apply();
                        toast("白名单已清空");
                    }
                }));
    }

    private void removeWhitelist(String host) {
        String cur = pref.getString(KEY_WHITELIST, "");
        StringBuilder b = new StringBuilder();
        for (String s : cur.split("[,\\s]+")) {
            String v = s.trim();
            if (v.length() == 0 || v.equalsIgnoreCase(host)) continue;
            if (b.length() > 0) b.append('\n');
            b.append(v);
        }
        pref.edit().putString(KEY_WHITELIST, b.toString()).apply();
    }


    // ---------- 阅读模式 ----------

    private void openReaderMode() {
        try {
            if (web == null) return;
            toast("正在提取正文…");
            web.evaluateJavascript(Extras.READER_JS, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String raw) {
                    showReader(raw);
                }
            });
        } catch (Throwable t) {
            toast("提取失败：" + t.getMessage());
        }
    }

    private void showReader(String raw) {
        String title = "";
        String text = "";
        boolean ok = false;
        try {
            Object first = new JSONTokener(raw == null ? "null" : raw).nextValue();
            if (first instanceof String) {
                JSONObject o = new JSONObject((String) first);
                ok = o.optBoolean("ok", false);
                title = o.optString("title", "");
                text = o.optString("text", "");
            }
        } catch (Throwable ignored) {
        }
        if (!ok || text.length() < 80) {
            toast("这一页没提取到正文，往下滑一滑再试");
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new ViewGroup.LayoutParams(MP, WC));
        box.setPadding(dp(16), dp(4), dp(16), dp(4));

        if (title.length() > 0) {
            TextView h = new TextView(this);
            h.setText(title);
            h.setTextColor(0xFFF3ECFF);
            h.setTextSize(16f);
            h.setTypeface(null, Typeface.BOLD);
            h.setPadding(dp(2), 0, dp(2), dp(10));
            box.addView(h);
        }

        final TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(0xFFE6DFF2);
        body.setTextSize(15.5f);
        body.setLineSpacing(dp(6), 1f);
        body.setTextIsSelectable(true);
        body.setPadding(dp(2), 0, dp(2), dp(8));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body, new LinearLayout.LayoutParams(MP, WC));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        box.addView(sv, new LinearLayout.LayoutParams(MP, maxH));

        final String bodyText = text;
        final String spoken = (title.length() > 0 ? title + "。" : "") + text;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(8), 0, dp(2));
        bar.addView(makeSmallButton("🔊 朗读", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speak(spoken);
            }
        }), weightParams());
        bar.addView(makeSmallButton("⏹ 停止", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSpeaking();
            }
        }), weightParams());
        bar.addView(makeSmallButton("⧉ 复制全文", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(bodyText);
                toast("正文已复制");
            }
        }), weightParams());
        box.addView(bar);

        showGlass(new AlertDialog.Builder(this)
                .setTitle("阅读模式")
                .setView(box)
                .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        stopSpeaking();
                    }
                })
                );
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, WC, 1f);
        lp.rightMargin = dp(6);
        return lp;
    }

    private View makeSmallButton(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFF0E9FA);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(10), dp(8), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x2EFFFFFF);
        g.setCornerRadius(dp(11));
        g.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    /** 中文 TTS。系统没装语音引擎时只提示，不抛异常。 */
    private void speak(final String text) {
        ttsText = text;
        try {
            if (tts == null) {
                tts = new TextToSpeech(getApplicationContext(),
                        new TextToSpeech.OnInitListener() {
                            @Override
                            public void onInit(int status) {
                                if (status != TextToSpeech.SUCCESS || tts == null) {
                                    toast("系统没有可用的语音引擎");
                                    return;
                                }
                                try {
                                    int r = tts.setLanguage(Locale.CHINA);
                                    if (r == TextToSpeech.LANG_MISSING_DATA
                                            || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                        toast("缺少中文语音包，可在系统设置里安装");
                                        return;
                                    }
                                    tts.speak(ttsText == null ? text : ttsText,
                                            TextToSpeech.QUEUE_FLUSH, null, "huandou");
                                } catch (Throwable ignored) {
                                }
                            }
                        });
            } else {
                int r = tts.setLanguage(Locale.CHINA);
                if (r == TextToSpeech.LANG_MISSING_DATA
                        || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    toast("缺少中文语音包，可在系统设置里安装");
                    return;
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "huandou");
            }
            toast("开始朗读，可用「停止」中断");
        } catch (Throwable t) {
            toast("朗读失败：" + t.getMessage());
        }
    }

    private void stopSpeaking() {
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    // ---------- 书签 ----------

    private AlertDialog bookmarkDialog;

    private java.util.List<String[]> bookmarks() {
        return Extras.decodeBookmarks(pref.getString(KEY_BOOKMARKS, "[]"));
    }

    private void saveBookmarks(java.util.List<String[]> list) {
        pref.edit().putString(KEY_BOOKMARKS, Extras.encodeBookmarks(list)).apply();
    }

    private void showBookmarks() {
        if (bookmarkDialog != null) {
            try {
                bookmarkDialog.dismiss();
            } catch (Throwable ignored) {
            }
            bookmarkDialog = null;
        }
        final java.util.List<String[]> list = bookmarks();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new ViewGroup.LayoutParams(MP, WC));
        box.setPadding(dp(16), dp(2), dp(16), dp(2));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 0, 0, dp(10));
        bar.addView(makeSmallButton("＋ 收藏当前页", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addCurrentBookmark();
            }
        }), weightParams());
        bar.addView(makeSmallButton("导入", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBookmarkFile();
            }
        }), weightParams());
        bar.addView(makeSmallButton("导出", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportBookmarks();
            }
        }), weightParams());
        box.addView(bar);

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有书签。\n\n"
                    + "点「＋ 收藏当前页」把正在看的页面存下来；\n"
                    + "「导出」会写一个 bookmarks.json 到 下载/Huandou，"
                    + "换设备后用「导入」读回来（浏览器导出的书签 HTML 也认）。");
            empty.setTextColor(0xFF9C93AC);
            empty.setTextSize(12.5f);
            empty.setLineSpacing(dp(3), 1f);
            empty.setPadding(dp(4), dp(6), dp(4), dp(14));
            box.addView(empty);
        } else {
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < list.size(); i++) {
                rows.addView(makeBookmarkRow(list.get(i), list, i));
            }
            ScrollView sv = new ScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(rows, new LinearLayout.LayoutParams(MP, WC));
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.48f);
            box.addView(sv, new LinearLayout.LayoutParams(MP, Math.min(dp(400), maxH)));
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("书签（" + list.size() + "）")
                .setView(box)
                .setPositiveButton("关闭", null)
                .create();
        bookmarkDialog = dlg;
        attachGlass(dlg);
    }

    private View makeBookmarkRow(final String[] it, final java.util.List<String[]> all,
                                 final int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(6), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1FFFFFFF);
        g.setCornerRadius(dp(12));
        g.setStroke(Math.max(1, dp(1)), 0x26FFFFFF);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        String t = it[0] == null ? "" : it[0].trim();
        if (t.length() == 0) t = hostOf(it[1]);
        name.setText(t);
        name.setTextColor(0xFFF1ECF8);
        name.setTextSize(13.5f);
        name.setMaxLines(2);
        left.addView(name);

        TextView meta = new TextView(this);
        meta.setText(hostOf(it[1]));
        meta.setTextColor(0xFF9C93AC);
        meta.setTextSize(11f);
        meta.setPadding(0, dp(3), 0, 0);
        left.addView(meta);

        left.setClickable(true);
        left.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bookmarkDialog != null) {
                    try {
                        bookmarkDialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                    bookmarkDialog = null;
                }
                hideError();
                web.loadUrl(it[1]);
            }
        });
        row.addView(left, new LinearLayout.LayoutParams(0, WC, 1f));

        TextView del = new TextView(this);
        del.setText("✕");
        del.setTextColor(0xFF9C93AC);
        del.setTextSize(14f);
        del.setGravity(Gravity.CENTER);
        del.setPadding(dp(12), dp(8), dp(12), dp(8));
        del.setClickable(true);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                all.remove(index);
                saveBookmarks(all);
                toast("已删除");
                showBookmarks();
            }
        });
        row.addView(del);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
        lp.bottomMargin = dp(7);
        row.setLayoutParams(lp);
        return row;
    }

    private void addCurrentBookmark() {
        final String url = web == null ? null : web.getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("当前页面不能收藏");
            return;
        }
        java.util.List<String[]> all = bookmarks();
        for (String[] it : all) {
            if (url.equals(it[1])) {
                toast("这一页已经收藏过了");
                return;
            }
        }
        final EditText et = new EditText(this);
        String guess = currentPageTitle();
        et.setText(guess == null ? "" : guess);
        et.setHint("给这个书签起个名字");
        et.setSingleLine(true);
        et.setPadding(dp(18), dp(12), dp(18), dp(12));
        et.setTextSize(15f);
        et.setTextColor(0xFFF1ECF8);
        et.setHintTextColor(0xFF8C82A0);
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(0x1FFFFFFF);
        ebg.setCornerRadius(dp(10));
        ebg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        et.setBackground(ebg);

        showGlass(new AlertDialog.Builder(this)
                .setTitle("收藏当前页")
                .setMessage(shortUrl(url))
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) name = hostOf(url);
                        java.util.List<String[]> cur = bookmarks();
                        cur.add(0, new String[]{name, url});
                        saveBookmarks(cur);
                        toast("已收藏");
                        showBookmarks();
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    private void pickBookmarkFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_BOOKMARK_IN);
        } catch (Throwable t) {
            toast("没有可用的文件选择器");
        }
    }

    private void importBookmarks(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                toast("读不到这个文件");
                return;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String raw = new String(bo.toByteArray(), "UTF-8");
            java.util.List<String[]> add = Extras.decodeBookmarks(raw);
            if (add.isEmpty()) {
                toast("这个文件里没读到书签");
                return;
            }
            java.util.List<String[]> cur = bookmarks();
            int before = cur.size();
            for (String[] it : add) {
                boolean dup = false;
                for (String[] c : cur) {
                    if (c[1].equals(it[1])) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) cur.add(it);
            }
            saveBookmarks(cur);
            toast("导入完成：新增 " + (cur.size() - before) + " 条");
            showBookmarks();
        } catch (Throwable t) {
            toast("导入失败：" + t.getMessage());
        }
    }

    private void exportBookmarks() {
        java.util.List<String[]> list = bookmarks();
        if (list.isEmpty()) {
            toast("还没有书签可以导出");
            return;
        }
        saveTextToDownloads("bookmarks.json", Extras.encodeBookmarks(list),
                "application/json");
    }

    /** 往 下载/Huandou 写一个文本文件：10+ 走 MediaStore，9 及以下直接写。 */
    private void saveTextToDownloads(String name, String content, String mime) {
        try {
            byte[] data = content.getBytes("UTF-8");
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Huandou");
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("无法创建文件");
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(data);
                os.close();
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "Huandou");
                dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(data);
                fo.close();
            }
            toast("已导出到 下载/Huandou/" + name);
        } catch (Throwable t) {
            toast("导出失败：" + t.getMessage());
        }
    }

    // ---------------- Media sniffer & download ----------------

    private void showMediaSniffer() {
        try {
            web.evaluateJavascript(MEDIA_SCAN_JS, null);
        } catch (Throwable ignored) {
        }
        toast("正在扫描页面已加载的资源…");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                showMediaList();
            }
        }, 700);
    }

    /** 当前嗅探面板看的分类：视频 / 音频 / 图片。 */
    private int snifferCat = MediaSniffer.CAT_VIDEO;
    private AlertDialog snifferDialog;

    private void showMediaList() {
        // 切分类时重建面板：先关掉旧的，避免叠出两层对话框。
        if (snifferDialog != null) {
            try {
                snifferDialog.dismiss();
            } catch (Throwable ignored) {
            }
            snifferDialog = null;
        }
        final List<MediaSniffer.Item> items = MediaSniffer.items(snifferCat);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new ViewGroup.LayoutParams(MP, WC));
        box.setPadding(dp(14), dp(2), dp(14), dp(2));

        // ---- 分类筛选：视频 / 音频 / 图片 ----
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabName = {"视频", "音频", "图片"};
        for (int i = 0; i < 3; i++) {
            final int cat = i;
            int n = MediaSniffer.count(cat);
            boolean on = (cat == snifferCat);
            TextView tab = new TextView(this);
            tab.setText(tabName[i] + (n > 0 ? " " + n : ""));
            tab.setTextSize(13f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(12), dp(8), dp(12), dp(8));
            tab.setTextColor(on ? 0xFF1A1024 : 0xFFC9BFD8);
            GradientDrawable tg = new GradientDrawable();
            tg.setCornerRadius(dp(20));
            tg.setColor(on ? 0xFFF3E9FF : 0x1AFFFFFF);
            tg.setStroke(Math.max(1, dp(1)), on ? 0x00FFFFFF : 0x2EFFFFFF);
            tab.setBackground(tg);
            LinearLayout.LayoutParams tlp =
                    new LinearLayout.LayoutParams(WC, WC);
            tlp.rightMargin = dp(8);
            tab.setLayoutParams(tlp);
            tab.setClickable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cat == snifferCat) return;
                    snifferCat = cat;
                    showMediaList();
                }
            });
            tabs.addView(tab);
        }
        box.addView(tabs);

        // ---- 说明行 ----
        TextView sub = new TextView(this);
        String what = snifferCat == MediaSniffer.CAT_IMAGE ? "图片"
                : snifferCat == MediaSniffer.CAT_AUDIO ? "音频" : "视频";
        sub.setText("共 " + items.size() + " 个" + what
                + (snifferCat == MediaSniffer.CAT_VIDEO
                   ? " · 优先选「整集」（M3U8 / MP4）" : " · 点一下可保存"));
        sub.setTextColor(0xFFB7ABC9);
        sub.setTextSize(12.5f);
        sub.setPadding(dp(4), dp(10), dp(4), dp(10));
        if (!items.isEmpty()) box.addView(sub);

        // ---- 列表 ----
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(emptyHint(snifferCat));
            empty.setTextColor(0xFF9C93AC);
            empty.setTextSize(12.5f);
            empty.setLineSpacing(dp(3), 1f);
            empty.setPadding(dp(4), dp(4), dp(4), dp(12));
            box.addView(empty);
        } else {
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < items.size(); i++) {
                rows.addView(makeMediaRow(items.get(i)));
            }
            if (items.size() <= 5) {
                box.addView(rows);
            } else {
                ScrollView sv = new ScrollView(this);
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(rows, new LinearLayout.LayoutParams(MP, WC));
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.46f);
                box.addView(sv, new LinearLayout.LayoutParams(MP, Math.min(dp(380), maxH)));
            }
        }

        String title = snifferCat == MediaSniffer.CAT_IMAGE ? "图片嗅探"
                : snifferCat == MediaSniffer.CAT_AUDIO ? "音频嗅探" : "视频嗅探";
        if (!items.isEmpty()) title = title + "（" + items.size() + "）";
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNeutralButton("重新扫描", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showMediaSniffer();
                    }
                })
                .setNegativeButton("清空本类", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        MediaSniffer.clear(snifferCat);
                        toast("已清空");
                        showMediaList();
                    }
                })
                .setPositiveButton("关闭", null)
                .create();
        snifferDialog = dlg;
        attachGlass(dlg);
    }

    private String emptyHint(int cat) {
        if (cat == MediaSniffer.CAT_IMAGE) {
            return "这一页还没有加载图片地址。\n\n"
                    + "往下滑一滑让图片进入屏幕，再点「重新扫描」；"
                    + "站点的图标、封面、截图都会被收进来。";
        }
        if (cat == MediaSniffer.CAT_AUDIO) {
            return "这一页还没有加载音频地址。\n\n"
                    + "音乐、有声书这类站点在开始播放后才会把音频地址交给播放器；"
                    + "先播几秒，再点「重新扫描」。";
        }
        return "先把这一集播放几秒，再回来点这里。\n\n"
                + "网页只有在真正开始播放后，才会把视频地址交给播放器；"
                + "没播放过的集数，应用里是看不到地址的。";
    }

    /** 嗅探列表里的一行。视频多显示格式与集数，图片 / 音频显示域名与类型。 */
    private View makeMediaRow(final MediaSniffer.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1FFFFFFF);
        g.setCornerRadius(dp(12));
        g.setStroke(Math.max(1, dp(1)), 0x26FFFFFF);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
        row.setClickable(true);

        TextView name = new TextView(this);
        String base = nameFor(it);
        if (base.length() == 0) {
            base = snifferCat == MediaSniffer.CAT_IMAGE ? "未命名图片"
                    : snifferCat == MediaSniffer.CAT_AUDIO ? "未命名音频" : "未命名视频";
        }
        if (base.length() > 40) base = base.substring(0, 40) + "…";
        name.setText(base);
        name.setTextColor(0xFFF1ECF8);
        name.setTextSize(13.5f);
        name.setMaxLines(2);
        row.addView(name);

        TextView meta = new TextView(this);
        String seg = "TS".equals(it.type) ? MediaName.segmentNo(it.url) : "";
        meta.setText(kindLabel(it.type)
                + (seg.length() > 0 ? " 第" + seg + "段" : "")
                + " · " + hostOf(it.url));
        meta.setTextColor(0xFF9C93AC);
        meta.setTextSize(11f);
        meta.setPadding(0, dp(3), 0, 0);
        row.addView(meta);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMediaActions(it);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
        lp.bottomMargin = dp(7);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 一条嗅探结果该叫什么名字。
     *
     * <p>顺序：条目录下时的页面标题 → 当前页面标题（DOM 抓到的剧名）→ URL 推断。
     * 都拿不到时退化成「第N集」，而不是 URL 里那种 v1 / index。</p>
     */
    private String nameFor(MediaSniffer.Item it) {
        // 嗅探时记下的标题可能只是站点名（「幻豆短剧」），和当前页面抓到的
        // 「床第 第1集」放一起比一下，谁更像剧名用谁。
        String cur = currentPageTitle();
        String title = MediaName.bestPageTitle(
                it.title == null ? "" : it.title,
                cur == null ? "" : cur);
        if (title.length() == 0 && cur != null) title = cur;

        // 图片和音频是页面上的「附属资源」：一集里可能几十张图、几段配乐。
        // 沿用剧集命名会让它们全叫「床第 第1集」，只能靠 (2)(3) 排队。
        // 优先用地址里的文件名（cover / theme），拿不到再退化成「剧名 图片」。
        if (isImageItem(it) || isAudioItem(it)) {
            String show = MediaName.titleOnly(title);
            if (show.length() == 0) show = title == null ? "" : title.trim();
            String fb = MediaName.fileBase(it.url);
            if (fb.length() > 0) return show.length() > 0 ? show + " " + fb : fb;
            return (show.length() > 0 ? show : "未命名")
                    + (isImageItem(it) ? " 图片" : " 音频");
        }

        String base = MediaName.guess(it.url, title);
        if (base.length() > 0) return applyMemory(base, title);
        String ep = MediaName.episodeOf(title);
        return ep == null ? "" : "视频 第" + ep + "集";
    }

    /**
     * 沿用上次的剧名。
     *
     * <p>用户手动把「点过你爸 第1集」改成「床笫 第1集」之后，下一集应当自动叫
     * 「床笫 第2集」，而不是又退回站点原名。这里只在「确认还是同一部剧」时生效：
     * 当前页面抓到的原始剧名要和上次记录的一致，否则换剧就会串名。</p>
     *
     * <p>集数优先用页面识别到的；页面识别不到时，用上次的集数 +1。</p>
     */
    private String applyMemory(String base, String srcTitle) {
        try {
            String last = pref.getString(KEY_LAST_NAME, "");
            if (last.length() == 0) return base;
            String lastSrc = pref.getString(KEY_LAST_SRC, "");
            String src = MediaName.titleOnly(srcTitle == null ? "" : srcTitle);
            // 只有两边都拿到了剧名、而且不一样，才判定为换了剧。
            // 抓不到剧名时宁可沿用，否则用户改好的名字会莫名其妙失效。
            if (src.length() > 0 && lastSrc.length() > 0 && !src.equals(lastSrc)) return base;
            String ep = MediaName.episodeOf(base);
            if (ep == null) {
                int n = pref.getInt(KEY_LAST_EP, 0) + 1;
                if (n <= 0 || n > 999) return base;
                ep = String.valueOf(n);
            }
            return last + " 第" + ep + "集";
        } catch (Throwable t) {
            return base;
        }
    }

    /** 记下这次用的剧名与集数，供下一集沿用。 */
    private void rememberName(String base, String pageTitle) {
        try {
            String show = MediaName.titleOnly(base);
            if (show.length() == 0) return;
            String src = MediaName.titleOnly(pageTitle == null ? "" : pageTitle);
            String ep = MediaName.episodeOf(base);
            int n = 0;
            if (ep != null) {
                try {
                    n = Integer.parseInt(ep);
                } catch (Throwable ignored) {
                }
            }
            pref.edit()
                    .putString(KEY_LAST_NAME, show)
                    .putString(KEY_LAST_SRC, src)
                    .putInt(KEY_LAST_EP, n)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private String shortUrl(String u) {
        try {
            Uri p = Uri.parse(u);
            String host = p.getHost();
            String last = p.getLastPathSegment();
            String s = (host == null ? "" : host) + (last == null ? "" : "/" + last);
            if (s.length() < 4) s = u;
            return s.length() > 62 ? s.substring(0, 62) + "…" : s;
        } catch (Throwable t) {
            return u.length() > 62 ? u.substring(0, 62) + "…" : u;
        }
    }

    private void confirmMediaDownload(final MediaSniffer.Item item) {
        confirmMediaDownload(item, null);
    }

    /**
     * 之前这里有一张「视频下载说明」的八段长文，把「TS / HLS / AES-128 / Widevine」
     * 全塞进一个弹窗，实际效果是用户看不懂、还多一次点击。现在说明只留一句
     * 「保存到哪、进度在哪看」，其余交给操作面板自己解释。
     */
    private void confirmMediaDownload(final MediaSniffer.Item item,
                                      final String customName) {
        startDownloadService(item.url, item.type, customName);
    }

    /**
     * 自绘按钮面板。
     *
     * <p>不能用 {@code AlertDialog.setItems()}：本应用给对话框换了自定义 windowBackground
     * 和内边距后，"setMessage() + setItems()" 组合在不少 ROM 上会把列表压成 0 高 ——
     * 屏幕上只剩标题、说明和一个「关闭」，用户根本点不到「下载」，于是表现为
     * 「点了下载没反应、也没通知」。这里把每个操作当成普通 TextView 自己画，
     * 高度、颜色和点击热区都由代码保证。</p>
     */
    private void showSheet(String title, String body, String[] labels,
                           final Runnable[] actions, boolean[] primary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(2), dp(14), dp(2));
        // 显式给出布局参数：AlertDialog 的 custom panel 是 FrameLayout，
        // 默认 MATCH_PARENT/MATCH_PARENT 会让内容在 wrap_content 的弹窗里量得很怪。
        box.setLayoutParams(new ViewGroup.LayoutParams(MP, WC));
        if (body != null && body.length() > 0) {
            TextView tv = new TextView(this);
            tv.setText(body);
            tv.setTextColor(0xFFB7ABC9);
            tv.setTextSize(12.5f);
            tv.setLineSpacing(dp(2), 1f);
            tv.setPadding(dp(4), 0, dp(4), dp(12));
            box.addView(tv, new LinearLayout.LayoutParams(MP, WC));
        }
        for (int i = 0; i < labels.length; i++) {
            final Runnable r = actions != null && i < actions.length ? actions[i] : null;
            boolean isPrimary = primary != null && i < primary.length && primary[i];
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(15f);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(null, Typeface.BOLD);
            b.setTextColor(isPrimary ? 0xFF150E22 : 0xFFEDE7F7);
            b.setPadding(dp(10), dp(14), dp(10), dp(14));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            if (isPrimary) {
                g.setColor(0xFF9B7BFF);
            } else {
                g.setColor(0x22FFFFFF);
                g.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
            }
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null));
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (glassTop != null && glassTop.isShowing()) glassTop.dismiss();
                    if (r != null) {
                        // 让关闭动画先走一帧，避免「上一个弹窗的淡出」盖住新弹窗
                        handler.postDelayed(r, 60);
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
            lp.bottomMargin = dp(8);
            box.addView(b, lp);
        }
        attachGlass(new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("取消", null)
                .create());
    }

    /** 用日常说法描述格式，界面上不再出现 TS / M3U8 这类术语。 */
    private String kindLabel(String type) {
        if ("M3U8".equals(type)) return "整集（推荐）";
        if ("MPD".equals(type)) return "整集（暂不支持下载）";
        if ("MP4".equals(type)) return "整集 MP4";
        if ("TS".equals(type)) return "单个片段";
        if ("MAYBE".equals(type)) return "可能是整集";
        if ("IMG".equals(type)) return "图片";
        if ("AUDIO".equals(type)) return "音频";
        return type + " 文件";
    }

    private String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 这条资源属于哪一类，决定面板上的用词。 */
    private boolean isImageItem(MediaSniffer.Item it) {
        return "IMG".equals(it.type);
    }

    private boolean isAudioItem(MediaSniffer.Item it) {
        return "AUDIO".equals(it.type);
    }

    /** 保存时要用的扩展名。图片和音频按地址后缀判断，认不出就给常见值。 */
    private String extFor(MediaSniffer.Item it) {
        String low = it.url.toLowerCase(java.util.Locale.US);
        int q = low.indexOf('?');
        String path = q > 0 ? low.substring(0, q) : low;
        if (isImageItem(it)) {
            String[] exts = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif", ".svg"};
            for (String e : exts) {
                if (path.endsWith(e)) return ".jpeg".equals(e) ? ".jpg" : e;
            }
            return ".jpg";
        }
        if (isAudioItem(it)) {
            if (path.endsWith(".mp3")) return ".mp3";
            if (path.endsWith(".m4a")) return ".m4a";
            if (path.endsWith(".aac")) return ".aac";
            if (path.endsWith(".flac")) return ".flac";
            if (path.endsWith(".ogg") || path.endsWith(".opus")) return ".ogg";
            if (path.endsWith(".wav")) return ".wav";
            return ".m4a";
        }
        return ".mp4";
    }

    private void showMediaActions(final MediaSniffer.Item item) {
        final String preset = nameFor(item);
        final String ext = extFor(item);
        final boolean img = isImageItem(item);
        final boolean aud = isAudioItem(item);
        final String what = img ? "图片" : aud ? "音频" : "视频";

        String name = preset.length() == 0 ? "自动命名" : preset + ext;
        StringBuilder body = new StringBuilder();
        body.append("格式：").append(kindLabel(item.type));
        body.append("\n文件名：").append(name);
        body.append("\n保存到：手机「下载 / Huandou」文件夹");
        body.append("\n下载时可退出应用，进度显示在通知栏。");
        if ("TS".equals(item.type)) {
            body.append("\n\n注意：单个片段通常只有几秒画面。要整集，请先点播放，"
                    + "再回来选「整集（推荐）」那条。");
        }
        if ("MPD".equals(item.type)) {
            body.append("\n\n这种格式需要专用加密授权，暂不支持保存，只能在线观看。");
        }
        showSheet("保存这个" + what, body.toString(),
                new String[]{"保存到本地", "改个文件名再保存",
                        img ? "复制图片地址" : "复制" + what + "地址",
                        img ? "用系统应用打开" : "用别的播放器试播"},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                confirmMediaDownload(item, null);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                promptRenameDownload(item, preset);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                copyText(item.url);
                                toast(what + "地址已复制");
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                openInPlayer(item);
                            }
                        }
                },
                new boolean[]{true, false, false, false});
    }

    /** Lets the user override the auto-generated drama/episode name. */
    private void promptRenameDownload(final MediaSniffer.Item item, final String preset) {
        final EditText et = new EditText(this);
        et.setText(preset);
        et.setSelection(et.getText().length());
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("输入文件名，不用写扩展名");
        et.setPadding(dp(18), dp(12), dp(18), dp(12));
        et.setTextSize(15f);
        et.setTextColor(0xFFF1ECF8);
        et.setHintTextColor(0xFF8C82A0);
        // 玻璃主题下 EditText 默认没有底，看起来像一行普通文字；
        // 给个浅色描边框，一眼能看出这里可以改。
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(0x1FFFFFFF);
        ebg.setCornerRadius(dp(10));
        ebg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        et.setBackground(ebg);
        showGlass(new AlertDialog.Builder(this)
                .setTitle("重命名后下载")
                .setMessage("网址：" + shortUrl(item.url)
                        + "\n\n下载后会记住这个名字；下次还是这部剧时，集数自动往上加一集。")
                .setView(et)
                .setPositiveButton("开始下载", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String raw = et.getText().toString().trim();
                        if (raw.length() == 0) {
                            toast("文件名不能为空");
                            return;
                        }
                        int dot = raw.lastIndexOf('.');
                        if (dot > 0 && raw.length() - dot <= 6) raw = raw.substring(0, dot);
                        String clean = MediaName.sanitize(raw);
                        if (clean.length() == 0) {
                            toast("文件名不合法");
                            return;
                        }
                        confirmMediaDownload(item, clean);
                    }
                })
                .setNegativeButton("取消", null)
                );
    }

    private void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("huandou", s));
        } catch (Throwable ignored) {
        }
    }

    private void openInPlayer(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(url), "video/*");
            startActivity(i);
        } catch (Throwable t) {
            toast("没有可用的播放器，可复制链接后自行打开");
        }
    }

    /** 图片交给图库、音频交给音乐播放器，视频交给播放器。 */
    private void openInPlayer(MediaSniffer.Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(it.url), mimeFor(it.type));
            startActivity(i);
        } catch (Throwable t) {
            toast("没有能打开它的应用，可复制链接后自行打开");
        }
    }

    private String mimeFor(String type) {
        if ("MP4".equals(type)) return "video/mp4";
        if ("TS".equals(type)) return "video/mp2t";
        if ("WEBM".equals(type)) return "video/webm";
        if ("MKV".equals(type)) return "video/x-matroska";
        if ("MOV".equals(type)) return "video/quicktime";
        if ("AUDIO".equals(type)) return "audio/*";
        if ("IMG".equals(type)) return "image/*";
        return "video/*";
    }

    /** WebView title at the moment a resource was captured. */
    private String currentPageTitle() {
        // DOM 抓到的剧名最准（含「第几集」），优先用它。
        String hint = pageTitleHint;
        if (hint != null) {
            hint = hint.trim();
            if (hint.length() >= 3) return hint;
        }
        try {
            String t = web == null ? null : web.getTitle();
            if (t == null) return null;
            t = t.trim();
            if (t.length() == 0 || t.contains("://") || t.startsWith("www.")) return null;
            return t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 让页面把「剧名 / 第几集」报上来；单页应用切集时也靠它更新。 */
    private void grabPageMeta() {
        try {
            if (web != null) web.evaluateJavascript(PAGE_META_JS, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Smart file name: drama name + episode from the URL / page title.
     * The extension is taken from URLUtil so attachments keep their real type
     * while HLS playlists (index.m3u8) become .mp4.
     */
    private String guessFileName(String url, String cd, String mime) {
        String old = "downloadfile.bin";
        try {
            old = URLUtil.guessFileName(url, cd, mime);
        } catch (Throwable ignored) {
        }
        String ext = ".bin";
        int d = old.lastIndexOf('.');
        if (d >= 0 && d < old.length() - 1 && old.length() - d <= 6) {
            String candidate = old.substring(d);
            if (candidate.matches("^\\.[A-Za-z0-9]{1,5}$")) ext = candidate;
        }
        String base = MediaName.guess(url, currentPageTitle());
        if (base.length() == 0) {
            base = "huandou_" + System.currentTimeMillis();
        }
        return base + ext;
    }

    private String guessMediaName(String url, String forcedExt) {
        String name = guessFileName(url, null, "video/mp4");
        if (forcedExt != null && !name.endsWith(forcedExt)) {
            int d = name.lastIndexOf('.');
            if (d > 0) name = name.substring(0, d);
            name += forcedExt;
        }
        return name;
    }

    private void startDownloadService(String url, String type) {
        startDownloadService(url, type, null);
    }

    private void startDownloadService(String url, String type, String presetBase) {
        if (url == null || url.length() == 0) return;
        if ("MPD".equals(type)) {
            toast("DASH (.mpd) 流暂不支持，请用「复制链接」");
            return;
        }
        // 通知是这套后台下载唯一的进度出口。先确保它真的可用，再启动服务，
        // 否则用户会看到「点了下载，却什么反应都没有」。
        if (!hasNotificationPermission()) {
            pendingDlUrl = url;
            pendingDlType = type;
            pendingDlBase = presetBase;
            // 系统只在「首次请求 / 用户仍未拒绝」时才弹权限框；被永久拒绝后
            // requestPermissions 会立刻回调失败，只剩系统设置页这条路。
            boolean canAsk = !pref.getBoolean(KEY_NOTI_ASKED, false);
            try {
                canAsk = canAsk || shouldShowRequestPermissionRationale(
                        Manifest.permission.POST_NOTIFICATIONS);
            } catch (Throwable ignored) {
            }
            final boolean askable = canAsk;
            showGlass(new AlertDialog.Builder(this)
                    .setTitle("需要通知权限")
                    .setMessage("后台下载的进度、取消按钮和完成提醒都通过通知显示。\n\n"
                            + "不开通知权限，下载仍会进行，但你切出去后看不到进度，"
                            + "只能回到应用里看结果。")
                    .setPositiveButton(askable ? "去开启" : "去系统设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            if (!askable) {
                                openNotificationSettings();
                                return;
                            }
                            try {
                                requestPermissions(
                                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                        REQ_PERMISSION);
                            } catch (Throwable ignored) {
                                openNotificationSettings();
                            }
                        }
                    })
                    .setNegativeButton("仍然下载", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            String u = pendingDlUrl, t = pendingDlType, b = pendingDlBase;
                            pendingDlUrl = null;
                            doStartDownload(u, t, b);
                        }
                    }));
            return;
        }
        if (!notificationsEnabled()) {
            showGlass(new AlertDialog.Builder(this)
                    .setTitle("通知已被系统关闭")
                    .setMessage("「幻豆」的通知权限被关闭了，所以下载开始后你看不到任何进度提醒。\n\n"
                            + "可以到系统设置里重新打开；也可以直接下载，进度和结果会在应用内提示。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            openNotificationSettings();
                        }
                    })
                    .setNegativeButton("直接下载", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            doStartDownload(url, type, presetBase);
                        }
                    })
                    .setCancelable(true));
            return;
        }
        doStartDownload(url, type, presetBase);
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean notificationsEnabled() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 通知链路可用时，进度只走通知栏，界面不再重复显示。 */
    private boolean canShowNotification() {
        return hasNotificationPermission() && notificationsEnabled();
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            toast("请到 系统设置 → 通知 里允许「幻豆」");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERMISSION) return;
        pref.edit().putBoolean(KEY_NOTI_ASKED, true).apply();
        boolean granted = res != null && res.length > 0
                && res[0] == PackageManager.PERMISSION_GRANTED;
        String u = pendingDlUrl, t = pendingDlType, b = pendingDlBase;
        if (u == null) return;
        pendingDlUrl = null;
        if (!granted) {
            toast("未开启通知：下载继续，但只能在应用内看进度");
        }
        doStartDownload(u, t, b);
    }

    private void doStartDownload(String url, String type, String presetBase) {
        if (url == null || url.length() == 0) return;
        // 1.6.9 起不再提供开关：HLS 的 ts 分片很多播放器打不开，转 MP4 是下载的
        // 必要收尾而不是「选项」。老版本存档里可能留着 false，这里直接忽略。
        final boolean toMp4 = true;
        final String pageTitle = currentPageTitle();
        String nm = presetBase == null || presetBase.length() == 0
                ? MediaName.guess(url, pageTitle) : MediaName.sanitize(presetBase);
        if (nm.length() > 0) {
            // 沿用上次手改的名字，并在页面识别不到集数时自动 +1。
            nm = applyMemory(nm, pageTitle);
            rememberName(nm, pageTitle);
        }
        final String base = nm;
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            Intent i = new Intent(this, DownloadService.class);
            i.setAction(DownloadService.ACTION_START);
            i.putExtra(DownloadService.EXTRA_URL, url);
            i.putExtra(DownloadService.EXTRA_UA, profile.ua);
            i.putExtra(DownloadService.EXTRA_COOKIE, cookie);
            i.putExtra(DownloadService.EXTRA_REFERER,
                    currentUrl == null ? home : currentUrl);
            i.putExtra(DownloadService.EXTRA_NAME,
                    base.length() == 0
                            ? guessMediaName(url, null)
                            : base + ".mp4");
            i.putExtra(DownloadService.EXTRA_TO_MP4, toMp4);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            // 有通知时不再弹「已开始下载」：通知本身就在通知栏里立刻出现，
            // 再加一条 toast 就是同一件事显示两遍。只有通知不可用时才补一句。
            if (!canShowNotification()) updateDlHud("已开始下载，正在连接…", false);
            // 3 秒后自检：服务若没起来，界面必须说清楚，而不是静默失败。
            final long t0 = System.currentTimeMillis();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (DownloadService.lastEventMs() <= t0 && !DownloadService.isAlive()) {
                        updateDlHud("后台下载服务没能启动：可能被系统的后台限制拦住了", true);
                        toast("下载服务未响应，请检查系统是否限制「幻豆」后台运行");
                    }
                }
            }, 3000);
            if (!canShowNotification()) toast("已开始下载：可切出去，进度看通知栏");
        } catch (Throwable t) {
            toast("无法启动后台下载：" + t.getMessage());
        }
    }

    // ---------------- Download ----------------

    private void downloadViaManager(String url, String ua, String cd, String mime) {
        try {
            String name = guessFileName(url, cd, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (ua != null) req.addRequestHeader("User-Agent", ua);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (mime != null && mime.length() > 0) req.setMimeType(mime);
            req.setTitle(name);
            req.setDescription("幻豆 · 正在下载");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Huandou/" + name);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            toast("已开始下载：" + name);
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable ignored) {
            }
        }
    }

    private void downloadViaJs(String url, String mime, String cd) {
        String name = guessFileName(url, cd, mime);
        String js = "(function(){"
                + "var u=" + quoteJs(url) + ",m=" + quoteJs(mime) + ",n=" + quoteJs(name) + ";"
                + "function save(b64,type){try{HuandouNative.saveBase64(b64,type,n);}catch(e){}}"
                + "if(u.indexOf('data:')===0){var i=u.indexOf(',');"
                + "save(u.substring(i+1),u.substring(5,i).split(';')[0]);return;}"
                + "fetch(u).then(function(r){return r.blob();}).then(function(b){"
                + "var f=new FileReader();f.onloadend=function(){var s=f.result;"
                + "save(s.substring(s.indexOf(',')+1),b.type||m);};f.readAsDataURL(b);"
                + "}).catch(function(e){save('',m);});})();";
        web.evaluateJavascript(js, null);
        toast("正在准备下载…");
    }

    private void writeBase64ToDownloads(String b64, String mime, String name) {
        if (b64 == null || b64.length() == 0) {
            toast("下载失败：内容为空");
            return;
        }
        OutputStream os = null;
        Uri pendingUri = null;
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            if (name == null || name.trim().length() == 0) {
                name = "huandou_" + System.currentTimeMillis() + ".bin";
            }
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String type = (mime == null || mime.length() == 0) ? "application/octet-stream" : mime;

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, type);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Huandou");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                pendingUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (pendingUri == null) throw new Exception("无法创建下载文件");
                os = getContentResolver().openOutputStream(pendingUri);
                if (os == null) throw new Exception("无法写入下载文件");
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "Huandou");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
                os = new FileOutputStream(new File(dir, name));
            }
            os.write(data);
            os.flush();
            os.close();
            os = null;

            if (pendingUri != null) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(pendingUri, done, null, null);
            }
            toast("已保存到 下载/Huandou/" + name);
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String quoteJs(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append("\"").toString();
    }

    // ---------------- Misc ----------------

    private void updateProgress(int p) {
        if (progressLine == null || mainBox == null) return;
        int w = mainBox.getWidth();
        if (w <= 0) return;
        int nw = (int) (w * Math.max(0, Math.min(100, p)) / 100f);
        ViewGroup.LayoutParams lp = progressLine.getLayoutParams();
        if (lp == null) return;
        lp.width = nw;
        progressLine.setLayoutParams(lp);
        progressLine.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
    }

    /**
     * 签名对不上时用它顶掉整个界面。
     *
     * <p>此刻 pref / web / handler 都还没建，所以只能用系统控件从零搭，也不能碰
     * buildUi 里的任何东西。配色写死不跟随主题：深底浅字，在亮色和暗色系统下都读得清。</p>
     */
    /**
     * 准入页：先显示「正在校验」，失败后原地变成拒绝页。
     *
     * <p>为什么不直接退出：退出等于用户不知道为什么打不开，只会去点卸载。
     * 这里把原因写清楚，能重试的给按钮，不能重试的给一句「怎么解决」。</p>
     */
    private void showGateScreen() {
        gateAttempt = 0;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        scroll.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(72), dp(24), dp(36));
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView title = new TextView(this);
        title.setText("正在校验");
        title.setTextColor(0xFFE9E2F5);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        final TextView body = new TextView(this);
        body.setTextSize(14);
        body.setTextColor(0xFFC9C2D6);
        body.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        box.addView(body, blp);

        // 原因码：默认空着，失败时才有内容。可选中复制，用户报障时不用手抄。
        final TextView note = new TextView(this);
        note.setTextSize(11);
        note.setTextColor(0xFF6E6680);
        note.setTextIsSelectable(true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(20);
        box.addView(note, nlp);

        final TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextColor(0xFF121212);
        retry.setTextSize(15);
        retry.setGravity(Gravity.CENTER);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0xFFE0E0E0);
        rbg.setCornerRadius(dp(10));
        retry.setBackground(rbg);
        retry.setClickable(true);
        retry.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        rlp.topMargin = dp(26);
        box.addView(retry, rlp);

        setContentView(scroll);

        body.setText("幻豆在向服务端确认这个安装包。只需要一次，"
                + "通过之后 7 天内不再联网校验，断网也能正常用。\n\n"
                + "第一次会慢一点（生成设备证书要一两秒），请稍等。");

        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                title.setText("正在重试");
                body.setText("正在重新向服务端确认，请稍等。");
                note.setText("");
                runGate(title, body, note, retry);
            }
        });

        runGate(title, body, note, retry);
    }

    /**
     * 跑一次准入校验，回来就地改文案。
     *
     * <p>成功就 {@link #recreate()}：凭据已落盘，重建后 onCreate 里那道
     * {@code Gate.allowed} 会直接放行，不用在这里复制一遍后面的初始化代码。</p>
     */
    private void runGate(final TextView title, final TextView body,
                         final TextView note, final TextView retry) {
        retry.setVisibility(View.GONE);

        // 校验最坏要走两次网络往返（取码 + 提交），网络差的时候能拖到三十秒。
        // 干等三十秒会让人以为卡死，十秒后换一句能解释现状的话。
        final boolean[] done = {false};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (done[0] || isFinishing() || isDestroyed()) return;
                body.setText("还在连服务端…\n\n"
                        + "如果一直停在这里，多半是当前网络连不上校验服务器"
                        + "（也可能只是很慢）。\n\n"
                        + "可以退出，换个网络（比如从 Wi-Fi 切到移动数据）再打开。");
            }
        }, 10000);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Gate.Verdict v = Gate.verify(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        done[0] = true;
                        if (isFinishing()) return;
                        if (v.allow && Gate.allowed(MainActivity.this)) {
                            recreate();
                            return;
                        }
                        // 校验通过但凭据没写进 SharedPreferences：理论上不会发生。
                        // 真发生了也不能 recreate，那会变成无限重启。
                        if (v.allow) {
                            title.setText("校验没有完成");
                            body.setText("服务端给了凭据，但没能保存到本地。\n\n"
                                    + "请检查存储空间是否已满，然后重试。");
                            note.setText("原因码：credential_not_saved");
                            retry.setVisibility(View.VISIBLE);
                            return;
                        }
                        // 限流自动重试一次。用户看不懂 429，也不知道该等多久，
                        // 让他自己点只会越点越堵（CDN 是按频率限的）。
                        if (v.kind == Gate.DENY_BUSY && gateAttempt == 0) {
                            gateAttempt = 1;
                            final int waitS = v.retryAfter > 0 ? v.retryAfter : 3;
                            title.setText("校验服务器正忙");
                            title.setTextColor(0xFFE9E2F5);
                            body.setText("请求太多，暂时排不上队。"
                                    + waitS + " 秒后自动重试一次。\n\n"
                                    + "这不是你的设备或安装包的问题。");
                            note.setText(v.raw.length() > 0
                                    ? "原因码：" + AttestClient.clip(v.raw, 140) : "");
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (isFinishing() || isDestroyed()) return;
                                    title.setText("正在重试");
                                    runGate(title, body, note, retry);
                                }
                            }, Math.min(waitS, 10) * 1000L);
                            return;
                        }

                        title.setText(v.title);
                        // 颜色按「是谁的问题」分：红=包被改过，橙=设备/系统，
                        // 浅色=网络或服务端。让人扫一眼就知道该不该紧张。
                        title.setTextColor(v.kind == Gate.DENY_REPACK ? 0xFFFF6B6B
                                : (v.kind == Gate.DENY_OFFLINE || v.kind == Gate.DENY_OTHER
                                        || v.kind == Gate.DENY_BUSY)
                                        ? 0xFFE9E2F5 : 0xFFFFB74D);
                        body.setText(v.body);
                        note.setText(v.raw.length() > 0
                                ? "原因码：" + AttestClient.clip(v.raw, 140) : "");
                        retry.setVisibility(v.retryable ? View.VISIBLE : View.GONE);
                    }
                });
            }
        }, "hd-gate").start();
    }

    private void showTamperScreen(String actual) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        scroll.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(64), dp(24), dp(36));
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("安装包签名不一致");
        title.setTextColor(0xFFFF6B6B);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText("这个幻豆不是用原始密钥签名的，可能被人重新打包过。\n\n"
                + "重签过的包可以塞进任何东西——它照样能读你存下的 Cookie、"
                + "照样能往「下载」目录写文件。所以这里停下了，没有继续启动。\n\n"
                + "请从原来的渠道重新下载安装包。如果你就是打包的人，"
                + "用原始密钥重新构建后再装。");
        body.setTextColor(0xFFD6D6D6);
        body.setTextSize(14);
        body.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        box.addView(body, blp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E1E1E);
        cardBg.setCornerRadius(dp(12));
        card.setBackground(cardBg);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.addView(fpRow("内置（期望）", SignatureGuard.expectedShort(), 0xFF9CCC65));
        TextView gap = new TextView(this);
        gap.setText("");
        gap.setHeight(dp(10));
        card.addView(gap);
        card.addView(fpRow("实际（当前安装）", SignatureGuard.shortOf(actual), 0xFFFF8A80));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(22);
        box.addView(card, clp);

        TextView quit = new TextView(this);
        quit.setText("退出");
        quit.setTextColor(0xFF121212);
        quit.setTextSize(15);
        quit.setGravity(Gravity.CENTER);
        GradientDrawable qbg = new GradientDrawable();
        qbg.setColor(0xFFE0E0E0);
        qbg.setCornerRadius(dp(10));
        quit.setBackground(qbg);
        quit.setClickable(true);
        quit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        qlp.topMargin = dp(28);
        box.addView(quit, qlp);

        setContentView(scroll);
    }

    /** 指纹对比里的一行：小号灰标签 + 等宽值。 */
    private LinearLayout fpRow(String label, String value, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFF8A8A8A);
        l.setTextSize(12);
        row.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(valueColor);
        v.setTextSize(15);
        v.setTypeface(Typeface.MONOSPACE);
        row.addView(v);
        return row;
    }

    private void hideSplash() {
        if (splashDone || splash == null) return;
        splashDone = true;
        final View v = splash;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                v.animate().alpha(0f).setDuration(320).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (v.getParent() != null) root.removeView(v);
                    }
                });
            }
        }, 400);
    }

    /** 加载失败页的彩蛋：像素恐龙，点一下跳，撞到仙人掌结束。 */
    private final class DinoView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final java.util.ArrayList<float[]> obs = new java.util.ArrayList<float[]>();
        private float groundY;
        private float dinoY, vy, speed, gap, groundShift, scoreF;
        private int score, best;
        private boolean over, running;
        private long lastMs;

        DinoView(Context c) {
            super(c);
            setBackgroundColor(BRAND);
            txt.setTypeface(Typeface.MONOSPACE);
            stroke.setStrokeWidth(Math.max(1, dp(1)));
            best = pref.getInt("dino_best", 0);
        }

        void begin() {
            obs.clear();
            dinoY = 0;
            vy = 0;
            speed = dp(4.2f);
            gap = dp(200);
            groundShift = 0;
            scoreF = 0;
            score = 0;
            over = false;
            running = true;
            lastMs = 0;
            removeCallbacks(tick);
            post(tick);
        }

        void halt() {
            running = false;
            removeCallbacks(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            halt();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            groundY = h * 0.72f;
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                long now = System.currentTimeMillis();
                float dt = lastMs == 0 ? 1f : Math.min(2.2f, (now - lastMs) / 16f);
                lastMs = now;
                step(dt);
                invalidate();
                if (running) postDelayed(this, 16);
            }
        };

        private float dinoLeft() {
            return dp(38);
        }

        private void spawn() {
            float h = dp(20) + (float) Math.random() * dp(14);
            obs.add(new float[]{getWidth() + dp(10), dp(12), h});
        }

        private void step(float dt) {
            if (over) return;
            if (dinoY > 0 || vy > 0) {
                vy -= dp(0.62f) * dt;
                dinoY += vy * dt;
                if (dinoY <= 0) {
                    dinoY = 0;
                    vy = 0;
                }
            }
            speed = Math.min(dp(10), speed + dp(0.0016f) * dt);
            groundShift = (groundShift + speed * dt) % dp(24);
            for (int i = obs.size() - 1; i >= 0; i--) {
                float[] o = obs.get(i);
                o[0] -= speed * dt;
                if (o[0] + o[1] < -dp(30)) obs.remove(i);
            }
            gap -= speed * dt;
            if (gap <= 0) {
                spawn();
                // 间距按「时间」给，不是按距离：速度越快间隔越宽，
                // 难度只体现在反应时间上，不会变成躲不掉的连环刺。
                gap = speed * (dp(36) + (float) Math.random() * dp(26));
            }
            scoreF += dt * 0.18f;
            score = (int) scoreF;

            float left = dinoLeft();
            float right = left + dp(26);
            float bottom = groundY - dinoY;
            for (int i = 0; i < obs.size(); i++) {
                float[] o = obs.get(i);
                // 右边界多算一帧的位移：掉帧时一帧走十几 dp，否则会从障碍里穿过去。
                boolean hx = right > o[0] && left < o[0] + o[1] + speed * dt;
                // 恐龙底部低于障碍顶部就算撞上，留 2dp 余量避免擦边误判。
                boolean hy = bottom > groundY - o[2] + dp(2);
                if (hx && hy) {
                    die();
                    return;
                }
            }
        }

        private void die() {
            over = true;
            running = false;
            removeCallbacks(tick);
            if (score > best) {
                best = score;
                pref.edit().putInt("dino_best", best).apply();
            }
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                if (over) {
                    begin();
                } else if (dinoY <= 0.5f) {
                    vy = dp(8.4f);
                }
                return true;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (groundY <= 0) groundY = h * 0.72f;

            // 地面与跑动的小刻线
            stroke.setColor(0xFF3B2C52);
            c.drawLine(0, groundY, w, groundY, stroke);
            stroke.setColor(0xFF2A2040);
            for (float x = -groundShift; x < w; x += dp(24)) {
                c.drawLine(x, groundY + dp(7), x + dp(10), groundY + dp(7), stroke);
            }

            // 仙人掌
            for (int i = 0; i < obs.size(); i++) {
                float[] o = obs.get(i);
                drawCactus(c, o[0], o[1], o[2]);
            }

            drawDino(c, dinoLeft(), groundY - dinoY);

            txt.setTextAlign(Paint.Align.RIGHT);
            txt.setTextSize(dp(11));
            txt.setColor(0xFF8E839C);
            c.drawText(String.format(Locale.US, "HI %05d   %05d", best, score),
                    w - dp(16), dp(30), txt);

            if (score < 6 && !over) {
                txt.setTextSize(dp(10));
                txt.setColor(0xFF5F5573);
                txt.setTextAlign(Paint.Align.LEFT);
                c.drawText("点一下跳", dp(16), dp(30), txt);
            }

            if (over) {
                fill.setColor(0x7A120C1C);
                c.drawRect(0, 0, w, h, fill);
                txt.setTextAlign(Paint.Align.CENTER);
                txt.setColor(0xFFEDE7F6);
                txt.setTextSize(dp(16));
                c.drawText("撞上了 · " + score + " 分", w / 2f, h / 2f - dp(4), txt);
                txt.setTextSize(dp(12));
                txt.setColor(0xFFB7AACB);
                c.drawText("点一下重来", w / 2f, h / 2f + dp(20), txt);
            }
        }

        private void drawCactus(Canvas c, float left, float w, float hh) {
            fill.setColor(0xFF6F9B7A);
            float bottom = groundY;
            c.drawRect(left, bottom - hh, left + w, bottom, fill);
            float armW = w * 0.55f;
            c.drawRect(left - armW, bottom - hh * 0.70f, left, bottom - hh * 0.52f, fill);
            c.drawRect(left + w, bottom - hh * 0.80f, left + w + armW, bottom - hh * 0.60f, fill);
        }

        private void drawDino(Canvas c, float x, float bottom) {
            float u = dp(1);
            fill.setColor(0xFFE8E2F5);
            // 尾巴
            c.drawRect(x - 7 * u, bottom - 15 * u, x + 1 * u, bottom - 9 * u, fill);
            // 身体
            c.drawRect(x, bottom - 18 * u, x + 19 * u, bottom - 5 * u, fill);
            // 头
            c.drawRect(x + 13 * u, bottom - 28 * u, x + 29 * u, bottom - 14 * u, fill);
            // 嘴部缺口
            fill.setColor(BRAND);
            c.drawRect(x + 28 * u, bottom - 20 * u, x + 30 * u, bottom - 17 * u, fill);
            // 眼睛
            c.drawRect(x + 23 * u, bottom - 25 * u, x + 26 * u, bottom - 22 * u, fill);
            // 腿：跑动时交替抬起
            fill.setColor(0xFFE8E2F5);
            boolean up = ((int) (System.currentTimeMillis() / 130)) % 2 == 0;
            c.drawRect(x + 2 * u, bottom - 5 * u, x + 6 * u, bottom - (up ? 1 : 0) * u, fill);
            c.drawRect(x + 12 * u, bottom - 5 * u, x + 16 * u,
                    bottom - (up ? 0 : 1) * u, fill);
        }
    }

    private boolean isNetworkAvailable() {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        if (Build.VERSION.SDK_INT >= 23) {
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 应用内下载指示条：即使系统通知完全不可用，用户也能看见任务在跑。 */
    private void updateDlHud(String text, boolean done) {
        if (root == null || text == null) return;
        try {
            if (dlHud == null) {
                TextView t = new TextView(this);
                t.setTextColor(0xFFFFFFFF);
                t.setTextSize(12f);
                t.setMaxLines(3);
                t.setPadding(dp(14), dp(9), dp(14), dp(9));
                GradientDrawable g = new GradientDrawable();
                g.setColor(0xE6171021);
                g.setCornerRadius(dp(16));
                g.setStroke(dp(1), 0x669B7BFF);
                t.setBackground(g);
                t.setVisibility(View.GONE);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WC, WC,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                lp.bottomMargin = dp(104);
                root.addView(t, lp);
                dlHud = t;
            }
            dlHud.setText(text);
            dlHud.setVisibility(View.VISIBLE);
            if (dlHudHide != null) handler.removeCallbacks(dlHudHide);
            if (done) {
                dlHudHide = new Runnable() {
                    @Override
                    public void run() {
                        if (dlHud != null) dlHud.setVisibility(View.GONE);
                    }
                };
                handler.postDelayed(dlHudHide, 6000);
            }
        } catch (Throwable ignored) {
        }
    }

    private void goHome() {
        hideError();
        web.loadUrl(home);
    }

    /**
     * 顶栏刷新。出错页面时 WebView 自身的 reload() 有时不会重新发起请求
     * （加载已经被 abort），所以这里统一走 loadUrl(当前地址)，失败页能真正重试。
     */
    private void refreshPage() {
        if (web == null) return;
        hideError();
        String u = web.getUrl();
        if (u == null || u.length() == 0 || u.startsWith("about:")) {
            web.loadUrl(home);
        } else {
            web.loadUrl(u);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE_CHOOSER && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(res, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        if (req == REQ_BOOKMARK_IN && res == RESULT_OK && data != null
                && data.getData() != null) {
            importBookmarks(data.getData());
        }
    }

    @Override
    public void onBackPressed() {
        if (blockView != null) {
            // 拦截页上的返回 = 放弃这个网址，回到上一页。
            removeBlockPage();
            leaveRiskyPage();
            return;
        }
        if (immersive) {
            applyImmersive(false);
            toast("已退出沉浸模式");
            return;
        }
        if (findBar != null && findBar.getParent() != null) {
            hideFindBar();
            return;
        }
        if (customView != null) {
            exitFullscreen();
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000) {
            super.onBackPressed();
        } else {
            lastBackTime = now;
            toast("再按一次退出幻豆");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) setFullscreenFlags(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (immersive) setFullscreenFlags(true);
        // 通知能正常显示时，界面上不再重复进度：底部小条和「已下载 15/22（52%）」这类
        // 弹窗式提示都会和通知栏打架，用户会看到两种形式的同一件事。
        // 只有通知被系统拦掉（没权限 / 关了通知）时，才退回应用内提示。
        DownloadService.setUiCallback(new DownloadService.UiCallback() {
            @Override
            public void onDownloadEvent(String text, int pct, boolean finished) {
                if (canShowNotification()) {
                    if (finished) DownloadService.clearPending();
                    return;
                }
                updateDlHud(text == null ? "下载中" : text, finished);
                if (finished) toast(text);
            }
        });
        String pend = DownloadService.takePendingMsg();
        if (pend != null && !canShowNotification()) toast(pend);
    }

    @Override
    protected void onPause() {
        captureCurrentVault(true);
        DownloadService.setUiCallback(null);
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopSpeaking();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        // 无痕模式：退出应用时把这一会留下的痕迹擦掉。
        if (incognito) wipeBrowsingData();
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
