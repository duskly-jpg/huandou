package com.huandou.app;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.webkit.WebSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

/**
 * A coherent device identity: UA plus every JS-readable fingerprint parameter.
 * Values are kept mutually consistent (device model / GPU / screen / DPR).
 */
public class DeviceProfile {

    public static final int KIND_ANDROID = 0;
    public static final int KIND_IOS = 1;
    public static final int KIND_DESKTOP = 2;
    public static final int KIND_SYSTEM = 3;

    // device | osVersion | screen.width | screen.height | dpr | cores | mem(GB) | glVendor | glRenderer
    private static final String[] ANDROID_PRESETS = {
            "Pixel 8 Pro|14|412|915|2.75|8|12|Qualcomm|Adreno (TM) 740",
            "Pixel 7|13|412|915|2.625|8|8|Google|Mali-G710",
            "Pixel 6|12|393|851|2.75|8|8|Google|Mali-G78",
            "Pixel 9 Pro XL|15|448|998|2.6|8|16|Google|Mali-G715",
            "SM-S918B|13|480|1029|3.0|8|12|Qualcomm|Adreno (TM) 740",
            "SM-S928B|14|480|1040|3.0|8|12|Qualcomm|Adreno (TM) 750",
            "SM-G991B|11|360|800|3.0|8|8|Qualcomm|Adreno (TM) 660",
            "SM-A546B|13|360|780|2.0|8|6|Samsung|Mali-G68",
            "23049RAD8C|13|393|873|3.0|8|12|Qualcomm|Adreno (TM) 740",
            "2201116SC|12|480|1067|3.0|8|12|MediaTek|Mali-G610",
            "V2309A|13|420|933|2.8|8|12|MediaTek|Mali-G715",
            "PJD110|14|480|1056|3.0|8|16|Qualcomm|Adreno (TM) 750",
            "LNA-AL00|12|436|964|2.8|8|8|Huawei|Maleoon 910",
            "LE2123|11|480|1072|3.0|8|12|Qualcomm|Adreno (TM) 660",
            "CPH2581|13|394|876|2.8|8|8|MediaTek|Mali-G610",
            "XT2241-1|11|393|873|2.75|8|6|Qualcomm|Adreno (TM) 642"
    };

    // device | iosVersion | screen.width | screen.height | dpr | cores | mem(GB)
    private static final String[] IOS_PRESETS = {
            "iPhone 15 Pro|17_2|393|852|3.0|6|6",
            "iPhone 14|16_6|390|844|3.0|6|4",
            "iPhone 13|17_1|390|844|3.0|6|4",
            "iPhone 12|16_3|390|844|3.0|6|4",
            "iPhone 15 Pro Max|18_0|430|932|3.0|6|8"
    };

    // device | UA platform | screen.width | screen.height | dpr | cores | mem(GB) | glRenderer
    private static final String[] DESKTOP_PRESETS = {
            "Windows PC|Windows NT 10.0; Win64; x64|1920|1080|1.0|8|8|ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Windows PC|Windows NT 10.0; Win64; x64|2560|1440|1.0|12|16|ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "MacBook|Macintosh; Intel Mac OS X 13_6_1|1440|900|2.0|8|8|ANGLE (Apple, Apple M1, OpenGL 4.1)"
    };

    public long id;
    public String name;
    public int kind;
    public String device, ua, platform, vendor, uaPlatform, chrome, chromeFull, av;
    public String glVendor, glRenderer, lang, timezone, arch;
    public int w, h, availH, cores, mem, touch, tzOffset, seed;
    /** 64 for every modern Android device; feeds UA-CH bitness. */
    public int bitness;
    /** Release year of the spoofed model (0 for system / non-Android). */
    public int deviceYear;
    public float dpr;
    public boolean mobile;
    public long createdAt;

    // Only used by the desktop branch; Android identities now derive every
    // dimension from UaFactory.Spec so all fields stay mutually consistent.
    private static final int[] CORES = {6, 8, 8, 8, 10, 12};
    private static final int[] MEMS = {4, 6, 8, 8, 12, 16, 24};

    private static String pick(String[] a, Random r) {
        return a[r.nextInt(a.length)];
    }

    public static DeviceProfile random(String label, int kind) {
        return random(label, kind, null);
    }

    /**
     * @param alignChromeMajor when set, keep the UA's Chrome major within +/-1
     *                         of the real WebView version (TLS-consistency mode).
     */
    public static DeviceProfile random(String label, int kind, String alignChromeMajor) {
        Random r = new Random();
        DeviceProfile p = new DeviceProfile();
        p.id = System.currentTimeMillis() ^ r.nextLong();
        p.name = label;
        p.kind = kind;
        p.seed = r.nextInt(100000);
        p.createdAt = System.currentTimeMillis();
        p.lang = "zh-CN";
        p.timezone = "Asia/Shanghai";
        p.tzOffset = -480;
        p.arch = "arm";
        p.bitness = 64;
        p.deviceYear = 0;

        if (kind == KIND_IOS) {
            String[] a = pick(IOS_PRESETS, r).split("\\|");
            p.device = a[0];
            p.av = UaFactory.randomIosVersion(r);
            p.platform = "iPhone";
            p.vendor = "Apple Computer, Inc.";
            p.uaPlatform = "iOS";
            p.glVendor = "Apple Inc.";
            p.glRenderer = "Apple GPU";
            p.arch = "arm64";
            p.chromeFull = UaFactory.randomChromeFull(r);
            p.chrome = p.chromeFull.split("\\.")[0];
            p.mobile = true;
            p.touch = 5;
            p.ua = UaFactory.iosUa(p.av);
            p.w = Integer.parseInt(a[2]);
            p.h = Integer.parseInt(a[3]);
            p.availH = p.h - 28;
            p.dpr = Float.parseFloat(a[4]);
            p.cores = Integer.parseInt(a[5]);
            p.mem = Integer.parseInt(a[6]);
        } else if (kind == KIND_DESKTOP) {
            String[] a = pick(DESKTOP_PRESETS, r).split("\\|");
            String plat = UaFactory.randomDesktopPlatform(r);
            p.chromeFull = UaFactory.chromeFull(r, 120, alignChromeMajor);
            p.chrome = p.chromeFull.split("\\.")[0];
            p.device = a[0];
            p.av = "10.0";
            p.platform = plat.startsWith("Mac") ? "MacIntel" : "Win32";
            p.vendor = "Google Inc.";
            p.uaPlatform = p.platform.equals("MacIntel") ? "macOS" : "Windows";
            p.glVendor = "Google Inc.";
            p.glRenderer = a[7];
            p.arch = "x86";
            p.mobile = false;
            p.touch = 0;
            p.ua = UaFactory.desktopUa(p.chromeFull, plat);
            p.w = Integer.parseInt(a[2]);
            p.h = Integer.parseInt(a[3]);
            p.availH = p.h - 40;
            p.dpr = Float.parseFloat(a[4]);
            p.cores = CORES[r.nextInt(CORES.length)];
            p.mem = MEMS[r.nextInt(MEMS.length)];
        } else {
            // Every dimension is derived from the same 2022-2026 device spec,
            // so model / Android / Chrome / GPU / screen never contradict.
            UaFactory.Spec spec = UaFactory.randomSpec(r);
            p.device = spec.model;
            p.deviceYear = spec.year;
            p.av = spec.androidVersion(r);
            p.chromeFull = spec.chromeFull(r, alignChromeMajor);
            p.chrome = p.chromeFull.split("\\.")[0];
            // 64-bit ARM Chrome reports "Linux aarch64"; "Linux armv8l" is
            // what a 32-bit Chrome build shows and looks wrong on recent SoCs.
            p.platform = "Linux aarch64";
            p.vendor = "Google Inc.";
            p.uaPlatform = "Android";
            int[] sc = spec.screen(r);
            p.w = sc[0];
            p.h = sc[1];
            p.availH = p.h - (20 + r.nextInt(24));
            p.dpr = spec.dpr(r);
            p.cores = spec.cores(r);
            p.mem = spec.mem(r);
            String[] g = spec.gpu(r);
            p.glVendor = g[0];
            p.glRenderer = g[1];
            p.mobile = true;
            p.touch = 5;
            p.ua = UaFactory.androidUa(p.device, p.av, p.chromeFull, r);
        }
        return p;
    }

    /**
     * A plain, honest identity: the device's real WebView UA, no fingerprint overrides.
     * Sites with one-time identity codes bind the code to the device; a stable native
     * UA is far less likely to be treated as a cloned/replayed device than a spoofed one.
     */
    public static DeviceProfile system(Context c) {
        DeviceProfile p = new DeviceProfile();
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        p.id = System.currentTimeMillis();
        p.name = "系统默认（兼容模式）";
        p.kind = KIND_SYSTEM;
        p.ua = WebSettings.getDefaultUserAgent(c);
        p.device = "本机 WebView";
        p.av = "";
        boolean is64 = is64Bit();
        p.platform = is64 ? "Linux aarch64" : "Linux armv8l";
        p.bitness = is64 ? 64 : 32;
        p.vendor = "";
        p.uaPlatform = "";
        p.chrome = chromeMajorOf(p.ua);
        p.chromeFull = p.chrome.length() > 0 ? p.chrome + ".0.0.0" : "";
        p.glVendor = "";
        p.glRenderer = "";
        p.lang = "zh-CN";
        p.timezone = "Asia/Shanghai";
        p.arch = "arm";
        p.deviceYear = 0;
        p.w = dm.widthPixels;
        p.h = dm.heightPixels;
        p.availH = p.h;
        p.dpr = dm.density;
        p.cores = Runtime.getRuntime().availableProcessors();
        p.mem = 8;
        p.touch = 5;
        p.tzOffset = -480;
        p.seed = 0;
        p.mobile = true;
        p.createdAt = System.currentTimeMillis();
        return p;
    }

    private static boolean is64Bit() {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                String[] abis = Build.SUPPORTED_64_BIT_ABIS;
                return abis != null && abis.length > 0;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Chrome major from the real WebView UA, used by TLS-consistency mode. */
    public static String chromeMajorOf(String ua) {
        if (ua == null) return "";
        try {
            int i = ua.indexOf("Chrome/");
            if (i < 0) return "";
            String s = ua.substring(i + 7);
            int e = 0;
            while (e < s.length() && Character.isDigit(s.charAt(e))) e++;
            return e > 0 ? s.substring(0, e) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public String brief() {
        if (kind == KIND_SYSTEM) return "本机原生 UA · 不伪装";
        String os = kind == KIND_IOS ? "iOS" : (kind == KIND_DESKTOP ? "桌面" : "Android " + av);
        String year = deviceYear > 0 ? " (" + deviceYear + ")" : "";
        return device + year + " / " + os + " / " + w + "x" + h + " / Chrome " + chrome;
    }

    /** JS object literal used by the injected fingerprint script. */
    public String jsObject(boolean canvasNoise) {
        return "{ua:" + q(ua)
                + ",platform:" + q(platform)
                + ",vendor:" + q(vendor)
                + ",uaPlatform:" + q(uaPlatform)
                + ",chrome:" + q(chrome)
                + ",chromeFull:" + q(chromeFull)
                + ",device:" + q(device)
                + ",av:" + q(av)
                + ",arch:" + q(arch)
                + ",glVendor:" + q(glVendor)
                + ",glRenderer:" + q(glRenderer)
                + ",timezone:" + q(timezone)
                + ",lang:" + q(lang)
                + ",w:" + w + ",h:" + h + ",availH:" + availH
                + ",dpr:" + dpr + ",cores:" + cores + ",mem:" + mem
                + ",bitness:" + bitness + ",touch:" + touch + ",tz:" + tzOffset + ",seed:" + seed
                + ",mobile:" + mobile + ",canvas:" + canvasNoise + "}";
    }

    private static String q(String s) {
        if (s == null) return "''";
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\'') b.append("\\'");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '<') b.append("\\u003c");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append("'").toString();
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("kind", kind);
            o.put("device", device);
            o.put("ua", ua);
            o.put("platform", platform);
            o.put("vendor", vendor);
            o.put("uaPlatform", uaPlatform);
            o.put("chrome", chrome);
            o.put("chromeFull", chromeFull);
            o.put("av", av);
            o.put("glVendor", glVendor);
            o.put("glRenderer", glRenderer);
            o.put("lang", lang);
            o.put("timezone", timezone);
            o.put("arch", arch);
            o.put("w", w);
            o.put("h", h);
            o.put("availH", availH);
            o.put("cores", cores);
            o.put("mem", mem);
            o.put("touch", touch);
            o.put("tzOffset", tzOffset);
            o.put("seed", seed);
            o.put("dpr", (double) dpr);
            o.put("mobile", mobile);
            o.put("bitness", bitness);
            o.put("deviceYear", deviceYear);
            o.put("createdAt", createdAt);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static DeviceProfile fromJson(String s) {
        try {
            JSONObject o = new JSONObject(s);
            DeviceProfile p = new DeviceProfile();
            p.id = o.optLong("id", System.currentTimeMillis());
            p.name = o.optString("name", "身份");
            p.kind = o.optInt("kind", 0);
            p.device = o.optString("device", "");
            p.ua = o.optString("ua", "");
            p.platform = o.optString("platform", "");
            p.vendor = o.optString("vendor", "");
            p.uaPlatform = o.optString("uaPlatform", "");
            p.chrome = o.optString("chrome", "");
            p.chromeFull = o.optString("chromeFull", "");
            p.av = o.optString("av", "");
            p.glVendor = o.optString("glVendor", "");
            p.glRenderer = o.optString("glRenderer", "");
            p.lang = o.optString("lang", "zh-CN");
            p.timezone = o.optString("timezone", "Asia/Shanghai");
            p.arch = o.optString("arch", "arm");
            p.w = o.optInt("w", 393);
            p.h = o.optInt("h", 873);
            p.availH = o.optInt("availH", p.h - 28);
            p.cores = o.optInt("cores", 8);
            p.mem = o.optInt("mem", 8);
            p.touch = o.optInt("touch", 5);
            p.tzOffset = o.optInt("tzOffset", -480);
            p.seed = o.optInt("seed", 1);
            p.dpr = (float) o.optDouble("dpr", 3.0);
            p.mobile = o.optBoolean("mobile", true);
            p.bitness = o.optInt("bitness", 64);
            p.deviceYear = o.optInt("deviceYear", 0);
            // Old saved identities used the 32-bit-looking platform value.
            if ("Linux armv8l".equals(p.platform)) p.platform = "Linux aarch64";
            p.createdAt = o.optLong("createdAt", 0L);
            if (p.ua.length() == 0) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
