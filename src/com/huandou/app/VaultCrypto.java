package com.huandou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 本地敏感数据的加解密：AES-256-GCM。
 *
 * <p>为什么是 GCM 而不是 CBC：CBC 只管保密，不管完整性——密文被改动一个字节，
 * 解密照样吐出一段（错误的）明文，调用方无从判断。GCM 是 AEAD，密文尾部带 128 位
 * 认证标签，任何一个 bit 被动过，{@code doFinal} 直接抛异常。这样「读出来的数据」
 * 和「当初写进去的数据」是同一份，不会出现被篡改的 Cookie 被当成真的灌回网页。
 *
 * <p>密钥不写死。派生链条：
 * <pre>
 *   deviceSeed(32B, 首次运行随机生成, 存本机)
 *        +
 *   certFp(64 hex, 当前安装包的签名指纹)
 *        ↓  HKDF-SHA256(salt = 分段常量混合, info = "huandou-local-vault/v2")
 *   K(32B) = AES-256 密钥
 * </pre>
 *
 * <p>两个后果：把 APK 拷到另一台机器上，deviceSeed 不同，解不开；把包重签后分发，
 * 证书指纹不同，也解不开。所以「把加密文件连同 App 一起打包带走」这条路走不通。
 *
 * <p>每条数据用独立的 12 字节随机 IV，并把「这条数据叫什么」当 AAD 绑定进去。
 * 于是把 A 条目的密文搬到 B 条目下也会解密失败——重放和换位都被挡住。
 *
 * <p>边界要说清楚：密钥最终在进程内存里是明文的，root + hook 仍可 dump。这一层解决的是
 * 「手机丢了、备份被拿走、数据文件被单独拷走」，不是「设备已被完全控制」。
 */
public final class VaultCrypto {

    /** 密文前缀兼版本号。以后换算法就抬到 g3:，老数据还能按 g2: 读。 */
    private static final String PREFIX = "g2:";
    private static final String SP = "hd_kx";
    private static final String KEY_SEED = "seed";
    private static final String INFO = "huandou-local-vault/v2";

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LEN = 32;
    private static final int SEED_LEN = 32;

    /** 盐的原料。8 段随机 32 位常量，单看任何一段都没有意义，必须走完混合才算出来。 */
    private static final int[] SEG = {
            0x7C51713A, 0x1C51BC93, 0x8EC3D0A3, 0x30E436F4,
            0xC159B2A5, 0x5808A30F, 0xBEF0E8E3, 0x44A6BC90
    };

    private static byte[] cachedKey;
    private static String cachedFp;

    private VaultCrypto() {
    }

    // ---------------- 对外 ----------------

    public static boolean isSealed(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    /**
     * 加密。失败返回 null，调用方应当把明文原样存下去——加密失败绝不能变成数据丢失。
     *
     * @param aad 这条数据的身份（如 "vault:id_3"、"us:1712.js"），解密时必须一模一样
     */
    public static String seal(Context c, String plain, String aad) {
        if (plain == null) return null;
        try {
            byte[] k = key(c);
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) ci.updateAAD(aad.getBytes("UTF-8"));
            byte[] ct = ci.doFinal(plain.getBytes("UTF-8"));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解密。不是密文、密钥不对、AAD 不对、被改过——一律返回 null。 */
    public static String open(Context c, String sealed, String aad) {
        if (!isSealed(sealed)) return null;
        try {
            byte[] all = Base64.decode(sealed.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length < IV_LEN + TAG_BITS / 8) return null;
            byte[] k = key(c);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
            byte[] body = Arrays.copyOfRange(all, IV_LEN, all.length);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) ci.updateAAD(aad.getBytes("UTF-8"));
            return new String(ci.doFinal(body), "UTF-8");
        } catch (Throwable t) {
            // AEADBadTagException 就走这里：认证标签对不上，说明数据被动过或密钥不对。
            return null;
        }
    }

    /** 诊断信息用。返回算法描述，不泄露密钥。 */
    public static String describe(Context c) {
        try {
            key(c);
            return "AES-256-GCM · 密钥绑定本机 + 签名";
        } catch (Throwable t) {
            return "未就绪";
        }
    }

    // ---------------- 密钥派生 ----------------

    private static synchronized byte[] key(Context c) throws Exception {
        String fp = SignatureGuard.expectedHex();
        if (cachedKey != null && fp.equals(cachedFp)) return cachedKey;

        byte[] seed = deviceSeed(c);
        byte[] fpBytes = fp.getBytes("UTF-8");
        byte[] ikm = new byte[seed.length + fpBytes.length];
        System.arraycopy(seed, 0, ikm, 0, seed.length);
        System.arraycopy(fpBytes, 0, ikm, seed.length, fpBytes.length);

        cachedKey = hkdf(ikm, salt(), INFO.getBytes("UTF-8"), KEY_LEN);
        cachedFp = fp;
        // ikm 里有设备种子，用完立刻抹掉引用，少留一份在堆上。
        Arrays.fill(ikm, (byte) 0);
        return cachedKey;
    }

    /** 32 字节设备种子，首次运行时随机生成并落地。 */
    private static byte[] deviceSeed(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_SEED, null);
        if (s != null) {
            byte[] b = Base64.decode(s, Base64.NO_WRAP);
            if (b.length == SEED_LEN) return b;
        }
        byte[] b = new byte[SEED_LEN];
        new SecureRandom().nextBytes(b);
        sp.edit().putString(KEY_SEED, Base64.encodeToString(b, Base64.NO_WRAP)).apply();
        return b;
    }

    /** 把 8 段常量过一遍移位/异或/乘法链，摊成 32 字节盐。 */
    private static byte[] salt() {
        byte[] s = new byte[32];
        int h = 0x5BE0CD19;
        for (int i = 0; i < 8; i++) {
            int v = SEG[i];
            h = Integer.rotateLeft(h ^ v, 7) * 0x85EBCA6B;
            v ^= h;
            v = Integer.rotateLeft(v, (i % 5) + 3);
            s[i * 4] = (byte) (v >>> 24);
            s[i * 4 + 1] = (byte) (v >>> 16);
            s[i * 4 + 2] = (byte) (v >>> 8);
            s[i * 4 + 3] = (byte) v;
        }
        return s;
    }

    /** RFC 5869。Android 的 javax.crypto 没有现成 HKDF，用 HMAC 手搓一遍。 */
    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] t = new byte[0];
        for (int i = 1; out.size() < len; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            out.write(t, 0, t.length);
        }
        byte[] r = out.toByteArray();
        Arrays.fill(prk, (byte) 0);
        return r.length == len ? r : Arrays.copyOf(r, len);
    }

    /** 给需要「这条数据的名字」当 AAD 的地方统一拼串，避免各写各的拼错。 */
    public static String aad(String kind, String name) {
        return kind + ":" + name;
    }

    /** 只在自检里用：确认同一明文两次加密结果不同（IV 随机），且能互相解回。 */
    static boolean selfTest(Context c) {
        String aad = aad("selftest", "1");
        String a = seal(c, "幻豆-selftest", aad);
        String b = seal(c, "幻豆-selftest", aad);
        if (a == null || b == null || a.equals(b)) return false;
        if (!"幻豆-selftest".equals(open(c, a, aad))) return false;
        if (!"幻豆-selftest".equals(open(c, b, aad))) return false;
        // 换 AAD 必须解不开
        if (open(c, a, aad("selftest", "2")) != null) return false;
        // 改一个 bit 必须解不开（认证标签生效）。
        //
        // 这里必须翻字节，不能去改 Base64 字符串的字符。密文是 12(IV) + 15(明文) + 16(tag)
        // = 43 字节，Base64 之后末尾是 "xx=="，倒数第三个字符只有高 2 位有效、低 4 位是填充位。
        // 而 'A' 和 'B' 的高 2 位都是 00——把那个字符在 A/B 之间换，解码结果一个字节都不变，
        // 密文没被改动，自然解得开，自检就随机地判失败（实测 200 次里 55 次）。
        // 1.7.7 / 1.7.8 诊断页偶尔报「自检未通过」就是这个，加密本身一直是好的。
        byte[] raw = Base64.decode(a.substring(PREFIX.length()), Base64.NO_WRAP);
        raw[raw.length - 1] ^= 0x01;
        String tampered = PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP);
        return open(c, tampered, aad) == null;
    }
}
