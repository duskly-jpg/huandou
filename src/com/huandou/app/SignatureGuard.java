package com.huandou.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;

/**
 * 安装包签名自校验。
 *
 * <p>要解决的问题只有一个：别人把 APK 反编译、塞进广告或木马、再用他自己的密钥重新签名
 * 分发出去时，那个包应该停下来，而不是照常跑。</p>
 *
 * <p><b>这不是加固，别高估它。</b>判断跑在 Java 层，把 {@link #check(Context)} 改成
 * {@code return null} 就绕过去了。它挡住的是「随手改包就发」的人，挡不住专门逆向的人。
 * 真要改不掉，得上 native 层或第三方加固。它和中途的混淆、多点校验叠起来才有意义。</p>
 *
 * <p>校验口径与 {@code apksigner verify --print-certs} 一致：把证书 DER 取 SHA-256。
 * 已经在 1.7.5 的包上对过，两边都是 9a126803…ee057。</p>
 */
public final class SignatureGuard {

    /**
     * 预期指纹，64 个 hex 字符，拆成 8 段在运行时拼。
     *
     * <p>拆开的原因：dex 里直接放一整串，任何人 {@code strings} 一下就能搜到，
     * 顺手把它改成自己的指纹比改逻辑还容易。拆成 8 段之后完整串不存在于任何位置，
     * 搜索「9a1268033a798c2b」这种十六位前缀也搜不到。挡不住认真的人，但成本是零。</p>
     *
     * <p>换密钥重新构建时，改这里。新指纹用
     * {@code keytool -exportcert ... | sha256sum} 或者 {@code apksigner verify --print-certs} 拿。</p>
     */
    private static final String[] FP = {
            "9a126803", "3a798c2b", "b4dada49", "b4cd1a2e",
            "05ee96d0", "7ab6e1d5", "0aa391b4", "2f2ee057"
    };

    private SignatureGuard() {
    }

    /** 拼出完整预期指纹。运行时拼，避免字符串常量被折叠回一整串。 */
    private static String expected() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < FP.length; i++) {
            sb.append(FP[i]);
        }
        return sb.toString();
    }

    /**
     * 给密钥派生用：正版包应当有的证书指纹（64 位小写 hex）。
     *
     * <p>注意这里给的是「期望值」而不是「实际值」。正常安装两者一致；重签的包
     * 走不到这里（{@link #check} 更早就拦了），万一被 patch 掉，它也只能拿到
     * 期望值——用这个值派生出的密钥解不开别人设备上生成的密文。
     */
    static String expectedHex() {
        return expected();
    }

    /**
     * 校验当前安装包。
     *
     * @return {@code null} 表示通过；否则返回实际指纹（可能为空串，表示读不出来）。
     *         调用方拿非 null 就应当停止一切工作。
     */
    public static String check(Context ctx) {
        String want = expected();
        String got = actual(ctx);
        if (got == null || got.length() == 0) {
            // 读不出签名：可能是 ROM 的 PackageManager 有问题，也可能是被做了手脚。
            // 不放过——放过的口子比误拦的代价大得多。
            return "";
        }
        return got.equalsIgnoreCase(want) ? null : got;
    }

    /** 进程内结果缓存：-1 未算 / 0 不通过 / 1 通过。xhr 这类高频调用不能每次都问系统。 */
    private static volatile int cached = -1;

    /**
     * 便捷判断，带进程内缓存。用于高频路径（脚本桥的每次请求）。
     * 不给缓存的话每次 {@code getPackageInfo} 都要走一次 PackageManager，卡片顿。
     */
    public static boolean ok(Context ctx) {
        int c = cached;
        if (c == 1) return true;
        if (c == 0) return false;
        boolean good = check(ctx) == null;
        cached = good ? 1 : 0;
        return good;
    }

    /** 预期指纹的展示形式：前 8 位 + 后 8 位，够辨认，又不至于把整串摆在界面上。 */
    public static String expectedShort() {
        return shortOf(expected());
    }

    /** 把 64 位指纹压成 {@code 9a126803…2f2ee057}。读不出来时给一句人话。 */
    public static String shortOf(String fp) {
        if (fp == null) return "读取失败";
        if (fp.length() == 0) return "读取失败";
        if (fp.length() < 20) return fp;
        return fp.substring(0, 8) + "…" + fp.substring(fp.length() - 8);
    }

    /** 取当前包的实际证书 SHA-256。读不到返回 null。 */
    public static String actual(Context ctx) {
        String pkg = ctx.getPackageName();
        String apkPath = null;
        try {
            apkPath = ctx.getApplicationInfo().sourceDir;
        } catch (Throwable ignored) {
        }

        // 主路径：问系统。系统在安装时已经验过签名，这里拿到的就是「装的是谁」。
        Signature[] sigs = signaturesOf(ctx, pkg, apkPath, false);
        if (sigs == null || sigs.length == 0) {
            // 退路：直接解析 APK 文件本身。极少走到，走到说明 PackageManager 那边不对劲。
            sigs = signaturesOf(ctx, pkg, apkPath, true);
        }
        if (sigs == null || sigs.length == 0) return null;

        // 多签名时只要有一个对上就算通过（正常是单签；签名轮换期会出现新老并存）。
        String want = expected();
        String first = null;
        for (int i = 0; i < sigs.length; i++) {
            String h = sha256(sigs[i].toByteArray());
            if (first == null) first = h;
            if (h != null && h.equalsIgnoreCase(want)) return h;
        }
        return first;
    }

    /**
     * 当前包的签名证书 DER 的 SHA-256 原始字节。
     *
     * <p>给 Key Attestation 用：系统会把这张摘要写进证书的 attestationApplicationId，
     * 我们拿同一份摘要回证书里搜，搜到了就证明「TEE 认的包 == 现在跑的包」。
     * 多签时优先返回与预期指纹一致的那张，其次才是第一张。</p>
     *
     * @return 32 字节摘要；读不到返回 null。
     */
    public static byte[] actualDigest(Context ctx) {
        String apkPath = null;
        try {
            apkPath = ctx.getApplicationInfo().sourceDir;
        } catch (Throwable ignored) {
        }
        Signature[] sigs = signaturesOf(ctx, ctx.getPackageName(), apkPath, false);
        if (sigs == null || sigs.length == 0) {
            sigs = signaturesOf(ctx, ctx.getPackageName(), apkPath, true);
        }
        if (sigs == null || sigs.length == 0) return null;
        String want = expected();
        Signature pick = sigs[0];
        for (int i = 0; i < sigs.length; i++) {
            String h = sha256(sigs[i].toByteArray());
            if (h != null && h.equalsIgnoreCase(want)) {
                pick = sigs[i];
                break;
            }
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(pick.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @param fromApk true 时读 APK 文件里的签名而不是系统安装记录。
     */
    private static Signature[] signaturesOf(Context ctx, String pkg, String apkPath,
                                            boolean fromApk) {
        PackageManager pm = ctx.getPackageManager();
        try {
            PackageInfo pi;
            if (Build.VERSION.SDK_INT >= 28) {
                int flag = PackageManager.GET_SIGNING_CERTIFICATES;
                pi = fromApk
                        ? pm.getPackageArchiveInfo(apkPath, flag)
                        : pm.getPackageInfo(pkg, flag);
                if (pi != null && pi.signingInfo != null) {
                    Signature[] s = pi.signingInfo.getApkContentsSigners();
                    if (s != null && s.length > 0) return s;
                }
                // API 28 的 signingInfo 在多签轮换期可能只给历史签名，拿不到就往下退。
            }
            @SuppressWarnings("deprecation")
            int oldFlag = PackageManager.GET_SIGNATURES;
            pi = fromApk
                    ? pm.getPackageArchiveInfo(apkPath, oldFlag)
                    : pm.getPackageInfo(pkg, oldFlag);
            if (pi != null) return pi.signatures;
        } catch (Throwable ignored) {
            // 有些 ROM 在 GET_SIGNING_CERTIFICATES 上会抛，交给下一层兜。
        }
        return null;
    }

    private static String sha256(byte[] data) {
        if (data == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
