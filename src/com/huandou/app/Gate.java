package com.huandou.app;

import android.content.Context;

/**
 * 启动准入：没有服务端签发的有效凭据，就不进主界面。
 *
 * <p><b>为什么放在服务端而不是客户端。</b>
 * 客户端里的 {@code if (ok)} 谁都能改，服务端的 403 改不了。所以这里做的是
 * 「向服务端申请一次许可」，而不是「本地算一下对不对」。凭据是服务端在验完
 * TEE 证书链之后签的，客户端伪造不出来。</p>
 *
 * <p><b>fail-closed 的真实含义。</b>
 * 不是「每次启动都要联网」，而是「手上必须有一张没过期的凭据」。凭据有效期 7 天，
 * 所以正常用户断网、地铁、服务器抖动都不受影响；而改包版从来没有一次成功，
 * 手里永远是空的，于是每次启动都被挡在这里。</p>
 *
 * <p>这样处理之后，「失败就禁止」和「别拿正版用户当人质」并不冲突：
 * 失败禁止的是<b>没有凭据</b>，不是<b>这一次没联上网</b>。</p>
 */
public final class Gate {

    /** 放行。 */
    public static final int ALLOW = GateRules.ALLOW;
    /** 设备拿不出硬件证书：系统太老、厂商没实现、或系统被改过。 */
    public static final int DENY_DEVICE_CAP = GateRules.DENY_DEVICE_CAP;
    /** 证书里的安装包信息与当前运行的包对不上：改包。 */
    public static final int DENY_REPACK = GateRules.DENY_REPACK;
    /** 设备认证链没通过：解锁、模拟器、改机工具。 */
    public static final int DENY_DEVICE = GateRules.DENY_DEVICE;
    /** 服务端要求更高版本。 */
    public static final int DENY_VERSION = GateRules.DENY_VERSION;
    /** 连不上校验服务器。 */
    public static final int DENY_OFFLINE = GateRules.DENY_OFFLINE;
    /** 服务器正忙（CDN 限流 429、过载 5xx）。 */
    public static final int DENY_BUSY = GateRules.DENY_BUSY;
    /** 其它（服务端异常、返回缺字段等）。 */
    public static final int DENY_OTHER = GateRules.DENY_OTHER;

    private Gate() {
    }

    /** 本地快速判断：手上有没过期的凭据就放行，一次网络都不发。 */
    public static boolean allowed(Context c) {
        return DeviceToken.usable(c) != null;
    }

    /**
     * 联网校验一次。<b>必须在子线程调用</b>：一次 TEE 密钥生成加两次网络往返。
     *
     * <p>成功时凭据已经写盘，调用方直接重建界面即可。</p>
     */
    public static Verdict verify(Context c) {
        DeviceToken.Verify v = DeviceToken.refreshDetail(c);
        Verdict d = new Verdict();
        d.allow = v.ok;
        d.raw = v.oneLine();
        d.att = v.attSummary;
        d.retryAfter = v.retryAfter;
        d.level = v.level;
        d.exp = v.exp;
        if (v.ok) {
            d.kind = ALLOW;
            return d;
        }
        d.kind = GateRules.classify(v.reason, v.netErr);
        d.retryable = GateRules.retryable(d.kind);
        fill(d);
        return d;
    }

    /**
     * 文案。
     *
     * <p>写给三种人看：正版用户（要能看懂怎么办）、被误伤的正版用户
     * （要能判断不是自己的错）、改包的人（不需要客气，但也别给线索——
     * 「签名摘要不匹配」这种话等于告诉他改哪里）。</p>
     */
    private static void fill(Verdict d) {
        switch (d.kind) {
            case DENY_DEVICE_CAP:
                d.title = "这台设备无法通过安全校验";
                d.body = "幻豆需要读取设备内置的安全证书，用来确认装的是官方安装包。"
                        + "这台设备没有提供这样的证书。\n\n"
                        + "常见原因：系统版本过低（需要 Android 7 以上）、"
                        + "厂商没有实现该功能、系统被刷过或改过。\n\n"
                        + "如果你确认用的是官方安装包，可以点下面的按钮再试一次；"
                        + "仍然不行的话，只能换一台设备。";
                break;
            case DENY_REPACK:
                d.title = "这个安装包不是官方版本";
                d.body = "设备给出的证书里记录了安装包的信息，和当前正在运行的包对不上。\n\n"
                        + "这只说明一件事：包被重新打包过。重打包的包可以塞进任何东西，"
                        + "所以这里停下了，没有继续启动。\n\n"
                        + "请从原来的渠道重新下载安装官方版本。"
                        + "如果你就是打包的人，用原始密钥重新构建后再装。";
                break;
            case DENY_DEVICE:
                d.title = "设备未通过校验";
                d.body = "设备提供的证书没有通过服务端校验。\n\n"
                        + "常见原因：系统已解锁（root / Bootloader 解锁）、"
                        + "运行在模拟器里、安装了改机或注入类工具。\n\n"
                        + "如果这台设备是正常的官方系统，请点下面重试；"
                        + "仍然不通过的话，把最下方的原因码发给我们。";
                break;
            case DENY_VERSION:
                d.title = "版本过旧";
                d.body = "服务端要求更新的版本才能继续使用。\n\n"
                        + "请更新到最新版幻豆后再打开。";
                break;
            case DENY_BUSY:
                d.title = "校验服务器正忙";
                d.body = "请求太多了，服务器暂时排不上队。\n\n"
                        + "这不是你的设备或安装包的问题，也已经自动重试过一次。"
                        + "稍等一会儿再点下面重试。";
                break;
            case DENY_OFFLINE:
                d.title = "连不上校验服务器";
                d.body = "幻豆需要在启动时向服务端确认一次安装包的完整性，"
                        + "这次没能连上。\n\n"
                        + "可能是当前网络不通、或者服务器暂时不可用。"
                        + "换一个网络（比如从 Wi-Fi 切到移动数据）后点下面重试。\n\n"
                        + "校验通过后会记住 7 天，这段时间内断网也能正常使用。";
                break;
            default:
                d.title = "校验没有完成";
                d.body = "服务端返回了预期之外的结果。\n\n"
                        + "这通常是服务端的问题，不是你的设备。稍后重试；"
                        + "如果一直这样，把最下方的原因码发给我们。";
                break;
        }
    }

    /** 一次校验的结论，界面直接消费。 */
    public static final class Verdict {
        public boolean allow;
        public int kind;
        public String title = "";
        public String body = "";
        /** 值得给个「重试」按钮：网络问题和服务端异常会自愈，签名不对不会。 */
        public boolean retryable;
        /** 原因码，给诊断和报障用。 */
        public String raw = "";
        /** 本地那张证书的结论。 */
        public String att = "";
        public int level = -1;
        public long exp;
        /** 服务端建议的等待秒数，-1 表示没说。 */
        public int retryAfter = -1;
    }
}
