package com.huandou.app;

import java.util.Arrays;

/**
 * 全局 TLS 伪装配置（单例）。
 *
 * 存放「当前要伪装成哪一代 Chrome」、GREASE、以及 ALPN 等 ClientHello 参数。
 * 由 MainActivity 在切换身份 / 开关时写入，SpoofSSLSocketFactory 读取它来
 * 构造伪造的 ClientHello。
 *
 * 路线说明（v1.6 起）：
 *  不再依赖本地 MITM 代理 / VPNService。直接把「手搓 TLS」封装成
 *  javax.net.ssl.SSLSocket，插进 HttpsURLConnection 的 SSLSocketFactory，
 *  在应用层（OkHttp / HttpURLConnection 同义）替换 TLS 参数即可。
 *
 * 注意：本类只描述「纸面指纹」；TLS 握手本身由 SpoofTlsClient 手搓。
 */
public final class TlsSpoofConfig {

    private static final TlsSpoofConfig INSTANCE = new TlsSpoofConfig();

    private volatile boolean enabled = true;     // 默认开启：装上一开即走自搓 TLS 伪装（失败自动回退系统 TLS）
    private volatile int targetChromeMajor = 130;   // 默认对齐一个较新的稳定版
    private volatile int grease = 0x0A0A;            // 本次连接统一使用的 GREASE 值
    // 默认只协商 http/1.1：HttpURLConnection 只能说 HTTP/1.1，若暴露 h2 会让
    // 服务器选 h2 而我们不会 h2 帧，导致连接错乱。需要 h2  fingerprint 时再切 OkHttp。
    private volatile String[] alpn = {"http/1.1"};
    private final Object lock = new Object();

    public static TlsSpoofConfig get() {
        return INSTANCE;
    }

    /** 是否启用 TLS 指纹伪装（仅随机身份模式下有意义）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 目标 Chrome 大版本，用于决定构建号范围与 UA-CH 一致性。 */
    public int getTargetChromeMajor() {
        return targetChromeMajor;
    }

    /** 本次会话统一使用的 GREASE 值（必须在整个 ClientHello 中保持一致）。 */
    public int getGrease() {
        return grease;
    }

    /** ALPN 协商列表（顺序即优先级）。 */
    public String[] getAlpn() {
        return alpn;
    }

    /** 由 MainActivity 调用：设置伪装参数。enabled=false 时上游回退到系统 TLS。 */
    public void apply(boolean enabled, int targetChromeMajor) {
        synchronized (lock) {
            this.enabled = enabled;
            if (targetChromeMajor >= 100 && targetChromeMajor <= 145) {
                this.targetChromeMajor = targetChromeMajor;
            }
            // 每次应用都重新随机一个 GREASE（从 Chrome 常用的 16 个值里挑）
            int[] greases = {0x0A0A, 0x1A1A, 0x2A2A, 0x3A3A, 0x4A4A, 0x5A5A,
                    0x6A6A, 0x7A7A, 0x8A8A, 0x9A9A, 0xAAAA, 0xBABA, 0xCACA, 0xDADA, 0xEAEA, 0xFAFA};
            this.grease = greases[(int) (System.currentTimeMillis() % greases.length)];
        }
    }

    /** 仅供测试 / 特殊场景：覆盖 ALPN 列表。 */
    public void setAlpn(String[] a) {
        if (a != null && a.length > 0) this.alpn = Arrays.copyOf(a, a.length);
    }

    /** 重置为关闭（兼容模式 / 进程退出时）。 */
    public void reset() {
        synchronized (lock) {
            this.enabled = false;
        }
    }

    /** 仅用于诊断信息展示，无副作用。 */
    public String describe() {
        synchronized (lock) {
            return enabled ? ("开启（伪装 Chrome " + targetChromeMajor + " · GREASE 0x"
                    + Integer.toHexString(grease).toUpperCase()
                    + " · ALPN " + Arrays.toString(alpn) + "）")
                    : "关闭（走系统原生 TLS）";
        }
    }
}
