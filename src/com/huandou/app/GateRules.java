package com.huandou.app;

/**
 * 准入失败的分型规则。
 *
 * <p>单独拎出来是为了能脱离 Android 编译和测试——分类错了，用户看到的就是
 * 错的文案，也拿不到该有的重试按钮，而这两种错误在真机上极难发现：
 * 现象只是「一直打不开」。</p>
 *
 * <p>分型的判据只有一个：<b>重试有没有意义</b>。服务端说「签名不对」，
 * 重试一百次还是同一句；服务端说「这个挑战码我没见过」，那多半是它自己
 * 刚重启或者请求超时了，重试就能过。</p>
 */
final class GateRules {

    static final int ALLOW = 0;
    /** 设备拿不出硬件证书。 */
    static final int DENY_DEVICE_CAP = 1;
    /** 证书里的包信息对不上 → 改包。 */
    static final int DENY_REPACK = 2;
    /** 认证链 / 挑战码不通过 → 设备或链路不可信。 */
    static final int DENY_DEVICE = 3;
    /** 版本过旧。 */
    static final int DENY_VERSION = 4;
    /** 连不上。 */
    static final int DENY_OFFLINE = 5;
    /** 其它。 */
    static final int DENY_OTHER = 6;
    /** 服务器正忙（CDN 限流 429、过载 5xx）。不是设备问题，也不是网络问题。 */
    static final int DENY_BUSY = 7;

    private GateRules() {
    }

    /**
     * @param reason 服务端 reason，或本地的 no_tee / net / no_token / bad_response
     * @param netErr 是否网络层异常（连不上、超时、DNS）
     */
    static int classify(String reason, boolean netErr) {
        String r = reason == null ? "" : reason;

        // 本地就失败的，优先。这类情况下根本没有 bind 请求，reason 一定是空的，
        // 但万一服务端码混进来了也不该盖掉「设备没证书」这个更准确的原因。
        if ("no_tee".equals(r)) return DENY_DEVICE_CAP;

        // 明确的包体不符。这两条是唯一能直接指向「有人重打包」的码。
        if ("sig_mismatch".equals(r) || "pkg_mismatch".equals(r)) return DENY_REPACK;

        // 设备的认证状态不可信：链断了、没有认证扩展、系统被解锁。
        if ("not_attested".equals(r) || "chain_broken".equals(r)) return DENY_DEVICE;

        // 证书里回显的挑战码和服务端下发的不一致 —— 这条链不是这次现造的，
        // 要么是旧链重放，要么有人在中间动了手脚。不可重试。
        if ("challenge_mismatch".equals(r)) return DENY_DEVICE;

        // 但「没见过这个码」和「这个码用过了」是服务端自己的状态问题：
        // 前者常见于服务端重启把 KV 清了、或者请求超时后客户端重试；
        // 后者每取一次码就用一次，正常流程撞不到。两者都该给重试按钮，
        // 卡在这里会让一个正常用户永久打不开。
        if ("challenge_not_found".equals(r) || "challenge_used".equals(r)) return DENY_OTHER;

        if ("version_too_old".equals(r) || "version_mismatch".equals(r)) return DENY_VERSION;

        // 请求送到了、服务端说「现在不行」。和网络不通是两回事：
        // 让用户「换个网络试试」在限流面前完全无效，他换十次也还是 429。
        if ("busy".equals(r)) return DENY_BUSY;
        if (r.startsWith("http_")) {
            try {
                int code = Integer.parseInt(r.substring(5));
                if (code == 429 || code >= 500) return DENY_BUSY;
            } catch (Throwable ignored) {
            }
            return DENY_OTHER;
        }

        if (netErr || "net".equals(r)) return DENY_OFFLINE;

        return DENY_OTHER;
    }

    /** 重试有没有意义。 */
    static boolean retryable(int kind) {
        return kind == DENY_OFFLINE || kind == DENY_OTHER || kind == DENY_BUSY;
    }
}
