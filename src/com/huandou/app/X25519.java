package com.huandou.app;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 纯 Java 实现的 X25519（Curve25519 蒙哥马利曲线上的标量乘法）。
 *
 * <p>为什么不用 JCA 的 KeyPairGenerator("X25519") / KeyAgreement("X25519")：
 * 那是 Java 11+ / Android API 33 才稳定的 API，在 minSdk 24 的设备上
 * 基本不可用。这里手写蒙哥马利梯子（RFC 7748 §5），不依赖 BouncyCastle，
 * 也不给 APK 增加体积。</p>
 *
 * <p>字段模数 p = 2^255 - 19，a24 = 121665。u 坐标为 32 字节小端。</p>
 */
public final class X25519 {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    private X25519() {}

    /** 生成一个已 clamp 的 32 字节私钥（k[0] &= 248; k[31] &= 127 | 0x40）。 */
    public static byte[] generatePrivate(SecureRandom rng) {
        byte[] k = new byte[32];
        rng.nextBytes(k);
        k[0] &= 248;
        k[31] &= 127;
        k[31] |= 64;
        return k;
    }

    /** 由私钥算出公钥（与基点 9 的标量乘）。 */
    public static byte[] publicFromPrivate(byte[] clampedPrivate) {
        byte[] base = new byte[32];
        base[0] = 9;
        return x25519(clampedPrivate, base);
    }

    /** 计算共享密钥 = x25519(私钥, 对端原始 32 字节公钥)。 */
    public static byte[] shared(byte[] clampedPrivate, byte[] peerPublicRaw) {
        return x25519(clampedPrivate, peerPublicRaw);
    }

    /**
     * 标量乘法。k 为 32 字节（clamp 后）小端，u 为 32 字节小端 u 坐标。
     * 返回 32 字节小端的共享 u 坐标（或公钥）。
     */
    public static byte[] x25519(byte[] k, byte[] u) {
        k = clamp(Arrays.copyOf(k, 32));
        BigInteger x1 = decodeU(u);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = (k[t >>> 3] >>> (t & 7)) & 1;
            swap ^= kt;
            BigInteger[] s1 = cswap(swap, x2, x3);
            x2 = s1[0]; x3 = s1[1];
            BigInteger[] s2 = cswap(swap, z2, z3);
            z2 = s2[0]; z3 = s2[1];
            swap = kt;

            BigInteger A = x2.add(z2).mod(P);
            BigInteger AA = A.multiply(A).mod(P);
            BigInteger B = x2.subtract(z2).mod(P);
            BigInteger BB = B.multiply(B).mod(P);
            BigInteger E = AA.subtract(BB).mod(P);
            BigInteger C = x3.add(z3).mod(P);
            BigInteger D = x3.subtract(z3).mod(P);
            BigInteger DA = D.multiply(A).mod(P);
            BigInteger CB = C.multiply(B).mod(P);
            BigInteger dacb = DA.add(CB).mod(P);
            x3 = dacb.multiply(dacb).mod(P);
            BigInteger dacb2 = DA.subtract(CB).mod(P);
            z3 = x1.multiply(dacb2.multiply(dacb2)).mod(P);
            x2 = AA.multiply(BB).mod(P);
            z2 = E.multiply(AA.add(A24.multiply(E))).mod(P);
        }
        BigInteger[] sf = cswap(swap, x2, z2);
        x2 = sf[0]; z2 = sf[1];
        BigInteger inv = z2.modInverse(P);
        BigInteger x = x2.multiply(inv).mod(P);
        return encodeU(x);
    }

    /** 条件交换：swap 为 0 或 1。常数时间实现（按位）。 */
    private static BigInteger[] cswap(int swap, BigInteger a, BigInteger b) {
        BigInteger mask = BigInteger.valueOf(swap & 1);
        BigInteger d = mask.multiply(a.subtract(b));
        return new BigInteger[] { a.subtract(d), b.add(d) };
    }

    /** 标量 clamp（k[0] &= 248; k[31] &= 127 | 0x40），幂等。 */
    private static byte[] clamp(byte[] k) {
        k[0] &= 248;
        k[31] &= 127;
        k[31] |= 64;
        return k;
    }

    /** 32 字节小端 → 整数，并清除最高位（RFC 7748 decode）。 */
    private static BigInteger decodeU(byte[] u) {
        byte[] c = Arrays.copyOf(u, 32);
        c[31] &= 0x7F;                 // 忽略第 255 位
        // BigInteger 按大端解释，先把小端字节反转
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) be[i] = c[31 - i];
        return new BigInteger(1, be);
    }

    /** 整数 → 32 字节小端（高位不足补 0）。 */
    private static byte[] encodeU(BigInteger x) {
        byte[] b = x.toByteArray();
        byte[] out = new byte[32];
        int len = b.length;
        for (int i = 0; i < 32; i++) {
            int src = len - 1 - i;
            out[i] = src >= 0 ? b[src] : 0;
        }
        return out;
    }
}
