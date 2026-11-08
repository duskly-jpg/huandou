package com.huandou.app;

/**
 * GateRules 的用例。纯 Java，不碰 Android，编译即可跑：
 *
 * <pre>
 *   javac -d /tmp/gr src/com/huandou/app/GateRules.java test/GateRulesTest.java
 *   java -cp /tmp/gr com.huandou.app.GateRulesTest
 * </pre>
 */
public final class GateRulesTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        // ── 设备能力 ──────────────────────────────────────────────
        eq("无硬件证书", GateRules.DENY_DEVICE_CAP, GateRules.classify("no_tee", false));

        // ── 改包 ─────────────────────────────────────────────────
        eq("签名摘要不符", GateRules.DENY_REPACK, GateRules.classify("sig_mismatch", false));
        eq("包名不符", GateRules.DENY_REPACK, GateRules.classify("pkg_mismatch", false));

        // ── 设备/链路不可信 ────────────────────────────────────────
        eq("链断裂", GateRules.DENY_DEVICE, GateRules.classify("chain_broken", false));
        eq("无认证扩展", GateRules.DENY_DEVICE, GateRules.classify("not_attested", false));
        eq("挑战码回显不符", GateRules.DENY_DEVICE, GateRules.classify("challenge_mismatch", false));

        // ── 服务端自身状态问题：必须可重试 ─────────────────────────
        // 这两条最容易分错。分到「不可重试」的话，一个正常用户会因为
        // 服务端重启过一次而永久打不开，且看不到任何重试入口。
        eq("挑战码不存在→可重试", GateRules.DENY_OTHER, GateRules.classify("challenge_not_found", false));
        eq("挑战码已用→可重试", GateRules.DENY_OTHER, GateRules.classify("challenge_used", false));
        yes("challenge_not_found 可重试", GateRules.retryable(GateRules.classify("challenge_not_found", false)));
        yes("challenge_used 可重试", GateRules.retryable(GateRules.classify("challenge_used", false)));

        // ── 版本 ─────────────────────────────────────────────────
        eq("版本过旧", GateRules.DENY_VERSION, GateRules.classify("version_too_old", false));
        eq("版本不符", GateRules.DENY_VERSION, GateRules.classify("version_mismatch", false));
        no("版本过旧不可重试", GateRules.retryable(GateRules.classify("version_too_old", false)));

        // ── 网络 ─────────────────────────────────────────────────
        eq("网络异常", GateRules.DENY_OFFLINE, GateRules.classify("net", true));
        eq("空原因+网络异常", GateRules.DENY_OFFLINE, GateRules.classify("", true));
        eq("null原因+网络异常", GateRules.DENY_OFFLINE, GateRules.classify(null, true));
        yes("断网可重试", GateRules.retryable(GateRules.classify("net", true)));

        // ── 兜底 ─────────────────────────────────────────────────
        eq("空原因", GateRules.DENY_OTHER, GateRules.classify("", false));
        eq("null原因", GateRules.DENY_OTHER, GateRules.classify(null, false));
        eq("未知码", GateRules.DENY_OTHER, GateRules.classify("something_new", false));

        // ── 限流：请求送到了，服务端说现在不行 ─────────────────────
        // 这一类如果归成「网络」，用户会照着提示去换网络，换十次还是 429。
        eq("CDN 限流", GateRules.DENY_BUSY, GateRules.classify("busy", false));
        eq("HTTP 429", GateRules.DENY_BUSY, GateRules.classify("http_429", false));
        eq("HTTP 503", GateRules.DENY_BUSY, GateRules.classify("http_503", false));
        eq("HTTP 502", GateRules.DENY_BUSY, GateRules.classify("http_502", false));
        yes("限流可重试", GateRules.retryable(GateRules.classify("busy", false)));
        no("429 不算网络问题", GateRules.classify("http_429", false) == GateRules.DENY_OFFLINE);
        eq("HTTP 400 归其它", GateRules.DENY_OTHER, GateRules.classify("http_400", false));
        yes("未知码可重试", GateRules.retryable(GateRules.classify("something_new", false)));

        // ── 关键不变量：任何输入都不许返回 ALLOW ──────────────────
        // 分类器只处理「已经失败」的情况。如果哪天有人在这里加一个
        // 「reason 不认识就放行」的分支，整套门禁就废了。
        String[] codes = {"", "no_tee", "sig_mismatch", "pkg_mismatch", "chain_broken",
                "not_attested", "challenge_mismatch", "challenge_not_found", "challenge_used",
                "version_too_old", "version_mismatch", "net", "bad_response", "no_token",
                "busy", "http_429", "http_503",
                "ok", "true", "ALLOW"};
        int allowLeaks = 0;
        for (int i = 0; i < codes.length; i++) {
            for (int j = 0; j < 2; j++) {
                if (GateRules.classify(codes[i], j == 1) == GateRules.ALLOW) {
                    allowLeaks++;
                    System.out.println("  漏放行: reason=" + codes[i] + " netErr=" + (j == 1));
                }
            }
        }
        eq("失败分型从不返回放行", 0, allowLeaks);

        // 重试策略和分型必须自洽：可重试的只能落在「网络/其它」两类里，
        // 否则会出现「按钮点了也白点」或者「明明该重试却没按钮」。
        int inconsistent = 0;
        for (int k = 0; k <= 7; k++) {
            boolean shouldRetry = (k == GateRules.DENY_OFFLINE || k == GateRules.DENY_OTHER
                    || k == GateRules.DENY_BUSY);
            if (GateRules.retryable(k) != shouldRetry) {
                inconsistent++;
                System.out.println("  策略不一致: kind=" + k);
            }
        }
        eq("重试策略自洽", 0, inconsistent);

        System.out.println();
        System.out.println(fail == 0
                ? "全部通过：" + pass + " 项"
                : "失败 " + fail + " 项，通过 " + pass + " 项");
        if (fail != 0) System.exit(1);
    }

    private static void eq(String name, int want, int got) {
        if (want == got) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  期望 " + want + " 实际 " + got);
        }
    }

    private static void yes(String name, boolean v) {
        if (v) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  期望 true");
        }
    }

    private static void no(String name, boolean v) {
        if (!v) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name + "  期望 false");
        }
    }
}
