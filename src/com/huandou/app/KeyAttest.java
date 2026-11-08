package com.huandou.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * Key Attestation 探针：向设备要一张「由硬件密钥背书的证书」，看它肯不肯说实话。
 *
 * <p>这不是加固，是<b>取证</b>。它回答一个问题：这台设备的 Keystore 能不能签发一张
 * 能向服务器证明「这个包确实是这个包、跑在真机上」的证书。能，就说明后面的云端校验
 * 有据可依；不能，就说明只能退回 HMAC 那套弱一点的方案。</p>
 *
 * <p>原理很短：往 AndroidKeyStore 里生成一把 EC P-256 密钥，生成时带一个随机
 * challenge。如果设备接的是真正的 Keymaster/TEE，返回的证书链里会多出一个扩展
 * （OID 1.3.6.1.4.1.11129.2.1.17），里面写着三件我们关心的事——</p>
 *
 * <ul>
 *   <li><b>安全等级</b>：0 软件 / 1 TEE / 2 StrongBox。0 是纸糊的，谁都能伪造。</li>
 *   <li><b>challenge</b>：我们刚塞进去的 32 字节随机数。回显说明这张证书是现造的，
 *       不是从别处抄来的旧证书。</li>
 *   <li><b>APK 签名摘要</b>：系统把「当前安装包的签名证书 SHA-256」写进了
 *       attestationApplicationId 里。这一条最要命——重签过的包，这里对不上。
 *       它比 Play Integrity 还严格，而且不需要 GMS、不需要联网、不需要谷歌账号。</li>
 * </ul>
 *
 * <p>所以本类只做字节级判断，不引第三方 ASN.1 库：在叶子证书的 DER 里搜 OID、
 * 搜 challenge、搜签名摘要。搜到了就是有，搜不到就是没有——没有中间态，也没有
 * 「解析器 bug 导致的假阴性」这种可能。精确字段（rootOfTrust、osVersion、
 * verifiedBootState）留给服务端用完整解析器读。</p>
 *
 * <p>这个类<b>不进 VMP 白名单</b>。原因：它全是数组创建和 64 位运算，
 * 正是 1.8.4 那次翻车踩过的坑；而且它不含任何需要保护的秘密——
 * 探针逻辑被人看光也无所谓，它证明的东西在证书里，不在代码里。</p>
 */
public final class KeyAttest {

    /**
     * KeyDescription 的扩展 OID：1.3.6.1.4.1.11129.2.1.17。
     *
     * <p>编码拆解，免得以后有人以为是抄错的：1.3 → 0x2B（前两段合并成一个字节）；
     * 然后 6 / 1 / 4 / 1 各一字节；11129 = 0x2B79，按 base-128 拆成 0xD6 0x79
     * （0x2B79 的低 7 位是 0x79，高位 0x56 补上延续位 0x80 得 0xD6）；
     * 再 2 / 1 / 17。合起来 10 字节，所以长度是 0x0A。</p>
     *
     * <p><b>这几个字节写错不会报错，只会让结论永远是「没有认证扩展」。</b>
     * 所以 build.sh 里有一道 {@code tools/check_oid.py} 会把它复算一遍，
     * 对不上就不让打包。</p>
     */
    private static final byte[] OID_KA = {
            0x06, 0x0A, 0x2B, 0x06, 0x01, 0x04, 0x01, (byte) 0xD6, 0x79, 0x02, 0x01, 0x11
    };

    /** KeyStore 里的别名。每次生成前先删，避免旧密钥残留导致 challenge 不复现。 */
    private static final String ALIAS = "huandou_attest_probe";

    /** 上次探测结果。诊断页第二次打开时直接显示，不必重新生成密钥。 */
    private static volatile Result last;

    private KeyAttest() {
    }

    /** 一次探测的结论。字段全部是给界面和剪贴板用的，不含任何需要保密的中间量。 */
    public static final class Result {
        /** 是否成功拿到证书链（哪怕不含 attestation 扩展）。 */
        public boolean ok;
        /** 失败原因，人话。ok=true 时为空。 */
        public String err = "";
        /** 证书链长度。1 表示只有自签叶子，没有任何背书。 */
        public int chainLen;
        /** attestationSecurityLevel：0 软件 / 1 TEE / 2 StrongBox / -1 未知。 */
        public int level = -1;
        /** 叶子证书里是否存在 Key Attestation 扩展。 */
        public boolean hasExt;
        /** 扩展里是否回显了我们这次设的 challenge。 */
        public boolean challengeOk;
        /** 扩展里是否包含本包的签名证书摘要（tag 709 signatureDigests）。 */
        public boolean sigDigestOk;
        /** challenge 从哪来：「ext」= 服务端下发，「local」= 本次随机生成。 */
        public String challengeSource = "local";
        /** challenge 的十六进制，便于和服务端比对（不是秘密，就是随机数）。 */
        public String challengeHex = "";
        /** 整条链的 PEM 文本，可直接粘给服务端。 */
        public String pem = "";
        /** 叶子证书的 SHA-256，展示用。 */
        public String leafFp = "";

        /** 一句话结论，界面直接用。 */
        public String summary() {
            if (!ok) return "不可用：" + err;
            if (!hasExt) {
                return chainLen <= 1
                        ? "不可用：设备未签发认证扩展（仅自签证书）"
                        : "不可用：证书链里没有认证扩展";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("可用 · ");
            if (level == 2) sb.append("StrongBox");
            else if (level == 1) sb.append("TEE");
            else if (level == 0) sb.append("软件模拟（不可信）");
            else sb.append("等级未知");
            sb.append(" · challenge ").append(challengeOk ? "匹配" : "不匹配");
            sb.append(" · 签名摘要 ").append(sigDigestOk ? "已写入" : "未写入");
            return sb.toString();
        }

        /** 三条判定压成一行，写进剪贴板头部。 */
        String header() {
            return "# KeyAttest ok=" + ok
                    + " chain=" + chainLen
                    + " level=" + level
                    + " ext=" + (hasExt ? 1 : 0)
                    + " challenge=" + (challengeOk ? 1 : 0)
                    + " sigdigest=" + (sigDigestOk ? 1 : 0)
                    + " source=" + challengeSource;
        }
    }

    /** 上次结果，可能为 null（还没探测过）。 */
    static Result lastResult() {
        return last;
    }

    /**
     * 生成一把带 challenge 的密钥，读完证书链就删掉私钥。
     *
     * <p>为什么要删：这把钥匙的用途只是「让系统签一张证书给我看」，
     * 签完就没用了。留在 Keystore 里既占硬件槽位（有些设备的槽位只有几十个），
     * 又给以后留了个来路不明的签名密钥。用完即删是唯一合理的做法。</p>
     *
     * <p><b>必须在子线程调用</b>：首次生成会走 Keymaster，冷启动能到一两秒。</p>
     */
    public static Result probe(Context ctx) {
        return probe(ctx, null);
    }

    /**
     * 带外部 challenge 的版本。
     *
     * <p>服务端校验的流程是「先下发随机数，客户端把它塞进证书」，不带外部 challenge
     * 生成的链，服务端没法证明这张证书是这一次现造的（可能被人拿旧的链重放）。
     * 传 null 或空数组就走本地随机，行为和以前一样，用于离线看结论。</p>
     *
     * <p><b>加了 synchronized</b>：1.9.2 起有两个入口会调它——用户点「一键验证」，
     * 以及启动 20 秒后的后台绑定。两者共用同一个 KeyStore 别名，并发时后到的那个
     * 会把前一个正在生成的密钥删掉，直接抛 KeyStoreException。串行化比加锁粒度
     * 更重要：一次生成最多两秒，排队可以接受。</p>
     *
     * @param external 服务端下发的 challenge，建议 32 字节；null/空 表示本地生成
     */
    public static synchronized Result probe(Context ctx, byte[] external) {
        Result r = new Result();
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
        } catch (Throwable t) {
            r.err = "系统没有 AndroidKeyStore";
            last = r;
            return r;
        }

        byte[] challenge;
        if (external != null && external.length > 0) {
            challenge = external;
            r.challengeSource = "ext";
        } else {
            challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            r.challengeSource = "local";
        }
        r.challengeHex = hex(challenge);

        try {
            try {
                if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
            } catch (Throwable ignored) {
            }

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            kpg.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    // 这一行是全部关键。不给 challenge，系统只会返回一张平平无奇的自签证书。
                    .setAttestationChallenge(challenge)
                    .build());
            kpg.generateKeyPair();
        } catch (Throwable t) {
            // 真机上见过三种：ROM 没接 Keymaster（ProviderException）、
            // 密钥槽位满（KeyStoreException）、参数被拒（InvalidAlgorithmParameterException）。
            // 一律归到「不可用」，不影响 App 其它功能。
            r.err = brief(t);
            last = r;
            try {
                if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
            } catch (Throwable ignored) {
            }
            return r;
        }

        try {
            Certificate[] chain = ks.getCertificateChain(ALIAS);
            if (chain == null || chain.length == 0) {
                r.err = "系统返回空证书链";
                return r;
            }
            r.ok = true;
            r.chainLen = chain.length;

            byte[] leaf = chain[0].getEncoded();
            r.leafFp = SignatureGuard.shortOf(sha256Hex(leaf));

            int p = indexOf(leaf, OID_KA, 0);
            r.hasExt = p >= 0;
            if (r.hasExt) {
                r.level = securityLevel(leaf, p);
                r.challengeOk = indexOf(leaf, challenge, 0) >= 0;
                byte[] sig = SignatureGuard.actualDigest(ctx);
                r.sigDigestOk = sig != null && sig.length > 0 && indexOf(leaf, sig, 0) >= 0;
            }
            r.pem = toPem(chain);
            return r;
        } catch (Throwable t) {
            r.ok = false;
            r.err = brief(t);
            return r;
        } finally {
            try {
                if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
            } catch (Throwable ignored) {
            }
            last = r;
        }
    }

    /**
     * 从扩展位置往后读 attestationSecurityLevel。
     *
     * <p>结构是固定的四层：{@code OID} → {@code OCTET STRING}（extnValue，内容是一段独立 DER）
     * → 里面的 {@code SEQUENCE}（KeyDescription）→ 第 1 个字段 {@code INTEGER attestationVersion}
     * → 第 2 个字段 {@code ENUMERATED attestationSecurityLevel}。</p>
     *
     * <p>第一版这里写错了：以为 SEQUENCE 里第一个就是 ENUMERATED，直接按 0x0A 判，
     * 结果永远是 -1（等级未知）。KeyDescription 的第一个字段是版本号，等级排在它后面。
     * 现在先把 INTEGER 整个跳过去，再看下一个是不是 0x0A 0x01。</p>
     *
     * @return 0/1/2，读不出来给 -1（不猜，猜错比没有更糟）
     */
    private static int securityLevel(byte[] der, int oidPos) {
        int p = oidPos + OID_KA.length;
        // 扩展里可能有 critical 标记（BOOLEAN，长度 1）。Key Attestation 一般不带，防一手。
        if (p + 2 <= der.length && (der[p] & 0xff) == 0x01) {
            p += 2 + (der[p + 1] & 0xff);
        }
        if (p >= der.length || (der[p] & 0xff) != 0x04) return -1;
        int[] v = readLen(der, p + 1);
        if (v == null) return -1;
        int q = v[0];
        if (q >= der.length || (der[q] & 0xff) != 0x30) return -1;
        int[] s = readLen(der, q + 1);
        if (s == null) return -1;

        // 第 1 个字段：INTEGER attestationVersion，整段跳过。
        int e = s[0];
        if (e >= der.length || (der[e] & 0xff) != 0x02) return -1;
        int[] v1 = readLen(der, e + 1);
        if (v1 == null) return -1;
        int e2 = v1[0] + v1[1];

        // 第 2 个字段：ENUMERATED，长度必须为 1。
        if (e2 + 3 <= der.length && (der[e2] & 0xff) == 0x0A && (der[e2 + 1] & 0xff) == 0x01) {
            return der[e2 + 2] & 0xff;
        }
        return -1;
    }

    /** 读 DER 长度字段，返回 {内容起始下标, 内容长度}。支持短格式和 1–3 字节长格式。 */
    private static int[] readLen(byte[] d, int p) {
        if (p >= d.length) return null;
        int b = d[p] & 0xff;
        if (b < 0x80) return new int[]{p + 1, b};
        int n = b & 0x7f;
        if (n == 0 || n > 3 || p + n >= d.length) return null;
        int len = 0;
        for (int i = 0; i < n; i++) len = (len << 8) | (d[p + 1 + i] & 0xff);
        return new int[]{p + 1 + n, len};
    }

    /** 朴素子串搜索。32 字节的 challenge 撞车概率可以忽略，不需要 KMP。 */
    private static int indexOf(byte[] hay, byte[] needle, int from) {
        if (hay == null || needle == null || needle.length == 0) return -1;
        int limit = hay.length - needle.length;
        for (int i = Math.max(0, from); i <= limit; i++) {
            int j = 0;
            while (j < needle.length && hay[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }

    /** 证书链转 PEM。每行 64 字符，后端 openssl / 我们的 attest_parse.py 都能直接吃。 */
    private static String toPem(Certificate[] chain) throws Exception {
        StringBuilder sb = new StringBuilder(chain.length * 1200);
        for (int i = 0; i < chain.length; i++) {
            sb.append("-----BEGIN CERTIFICATE-----\n");
            String b = Base64.encodeToString(chain[i].getEncoded(), Base64.NO_WRAP);
            for (int k = 0; k < b.length(); k += 64) {
                sb.append(b, k, Math.min(k + 64, b.length())).append('\n');
            }
            sb.append("-----END CERTIFICATE-----\n");
        }
        return sb.toString();
    }

    static byte[] sha256(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(d);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 字节转小写十六进制，用于展示 challenge。 */
    private static String hex(byte[] d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] d) {
        byte[] h = sha256(d);
        if (h == null) return null;
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < h.length; i++) {
            int v = h[i] & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /**
     * 异常压成一句能读的话。
     *
     * <p>Keymaster 抛出来的 message 经常是空的或者是 native 层的一串英文，
     * 直接贴到界面上等于没给。这里只保留类型名 + 前 60 字，够定位就行。</p>
     */
    private static String brief(Throwable t) {
        String n = t.getClass().getSimpleName();
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = "";
        else if (m.length() > 60) m = m.substring(0, 60) + "…";
        // 最常见的两种给成人话，别的保留原文方便我按日志查。
        if (n.contains("ProviderException") || n.contains("KeyStoreException")) {
            return "该设备未接入硬件密钥认证（" + (m.length() > 0 ? m : n) + "）";
        }
        if (n.contains("InvalidAlgorithmParameter")) {
            return "系统不接受认证参数（" + (m.length() > 0 ? m : "API 或 Keymaster 版本过低") + "）";
        }
        return m.length() > 0 ? m : n;
    }
}
