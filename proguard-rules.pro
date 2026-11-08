# ============================================================================
# 幻豆 · R8 keep 规则
#
# 这个工程没有 Gradle，build.sh 直接把本文件喂给 cmdline-tools 里的 r8.jar：
#   java -cp r8.jar com.android.tools.r8.R8 --release --min-api 24 \
#        --lib android.jar --pg-conf proguard-rules.pro --output dex/ obj/
#
# 原则：只 keep「名字被字符串或系统写死」的东西，其余一律交给 R8 改名、裁剪。
# 本项目没有反射（无 Class.forName / getMethod）、没有 JNI、没有 Serializable、
# 没有自定义注解，所以需要 keep 的面很窄，混淆可以开得比较狠。
# ============================================================================

# ---- 1. Manifest 里写死的组件名 -------------------------------------------
# 系统按字符串 "com.huandou.app.MainActivity" 反射加载，类名被改就启动不了。
# 只保类名和默认构造，成员照常参与混淆与裁剪。
-keep class com.huandou.app.MainActivity {
    <init>(...);
}
-keep class com.huandou.app.DownloadService {
    <init>(...);
}

# ---- 2. WebView 的 JS 桥 ----------------------------------------------------
# 网页里写的是 HuandouNative.get(...) 这样的字符串，方法名必须原样保留。
# 类名可以改（JS 只认 addJavascriptInterface 传的实例名）。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 桥对象本身：类级别 keep，避免 R8 把内部类合并掉导致反射找不到方法。
-keep class com.huandou.app.MainActivity$JsBridge
-keep class com.huandou.app.MainActivity$UserscriptBridge

# WebView 运行时靠反射读注解判断哪些方法暴露给 JS，注解不能丢。
# 同时保住 InnerClasses / EnclosingMethod：代码里有 getSimpleName() 的日志，
# 内部类元信息丢了会变成 a、b 这种名字。
-keepattributes *Annotation*,InnerClasses,EnclosingMethod

# ---- 3. WebView 回调 --------------------------------------------------------
# 这几个都是 override 系统方法，R8 通常能保住；但 shouldInterceptRequest /
# onJsAlert 之类有重载，显式再保一层，成本很低。
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}
-keepclassmembers class * extends android.webkit.DownloadListener {
    public *;
}

# ---- 4. 资源与系统交互 ------------------------------------------------------
# 布局/菜单里没有自定义 View 的全限定名（界面全部代码构建），不需要 keep。
# Toast / Dialog / Notification 用的都是系统类，不涉及。

# ---- 5. 别把异常类名也混淆了 ------------------------------------------------
# 失败页和日志会显示 e.getClass().getSimpleName()，保个可读性。
-keepnames class com.huandou.app.** extends java.lang.Throwable
-keepnames class * extends java.lang.Exception

# ---- 6. 裁剪提示 ------------------------------------------------------------
# libs/ 里的 BouncyCastle 三个 jar 是历史遗留，javac 根本没把它们放进 classpath，
# 代码也没 import org.bouncycastle。留着只为兼容，让 R8 别报缺失引用。
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

# 保留源文件名与行号，崩溃栈还能对上源码（代价是 dex 里多一点点字符串）。
-keepattributes SourceFile,LineNumberTable
