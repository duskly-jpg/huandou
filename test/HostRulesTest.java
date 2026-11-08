package com.huandou.app;

/**
 * HostRules 的边界测试。纯 JVM，不需要模拟器。
 *
 * <p>为什么值得写：这段逻辑是所有改动里唯一能把 App 拦到白屏的地方，
 * 而它的关键边界（{@code notexample.com} ≠ 子域）靠读代码很容易看漏。</p>
 *
 * <p>编译运行：</p>
 * <pre>
 *   javac -d /tmp/hr src/com/huandou/app/HostRules.java test/HostRulesTest.java
 *   java -cp /tmp/hr com.huandou.app.HostRulesTest
 * </pre>
 */
public class HostRulesTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean got, boolean want) {
        if (got == want) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL  " + name + "  want=" + want + " got=" + got);
        }
    }

    private static void checkArr(String name, String[] got, String[] want) {
        StringBuilder a = new StringBuilder();
        for (String s : got) a.append('[').append(s).append(']');
        StringBuilder b = new StringBuilder();
        for (String s : want) b.append('[').append(s).append(']');
        check(name + " -> " + a + " (want " + b + ")", a.toString().equals(b.toString()), true);
    }

    public static void main(String[] args) {
        String[] rules = HostRules.normalize(new String[]{"example.com"});

        // 精确
        check("exact", HostRules.match(rules, "example.com"), true);
        // 子域
        check("sub", HostRules.match(rules, "a.example.com"), true);
        check("deep sub", HostRules.match(rules, "b.a.example.com"), true);
        // 这一条是重点：endsWith 会误判
        check("not a subdomain", HostRules.match(rules, "notexample.com"), false);
        check("suffix only", HostRules.match(rules, "example.com.cn"), false);
        // 大小写
        check("upper host", HostRules.match(rules, "A.Example.COM"), true);
        // 空
        check("empty host", HostRules.match(rules, ""), false);
        check("null host", HostRules.match(rules, null), false);
        check("empty rules", HostRules.match(new String[0], "example.com"), false);

        // 规范化：剥协议、路径、端口、通配前缀
        checkArr("strip https+path",
                HostRules.normalize(new String[]{"https://ads.example.com/x?y=1"}),
                new String[]{"ads.example.com"});
        checkArr("strip wildcard",
                HostRules.normalize(new String[]{"*.tracker.net"}),
                new String[]{"tracker.net"});
        checkArr("strip port",
                HostRules.normalize(new String[]{"cdn.evil.io:8443"}),
                new String[]{"cdn.evil.io"});
        checkArr("upper -> lower",
                HostRules.normalize(new String[]{"ADS.Example.COM"}),
                new String[]{"ads.example.com"});

        // 危险输入必须被丢掉。这三条任意一条漏过去，App 就打不开了。
        checkArr("drop star", HostRules.normalize(new String[]{"*"}), new String[]{});
        checkArr("drop com", HostRules.normalize(new String[]{"com"}), new String[]{});
        checkArr("drop dotless", HostRules.normalize(new String[]{"localhost"}), new String[]{});
        checkArr("drop empty", HostRules.normalize(new String[]{"", "  ", null}), new String[]{});
        checkArr("drop wildcard mid", HostRules.normalize(new String[]{"a*.b.com"}), new String[]{});

        // 混合：好项保留，坏项丢掉
        checkArr("mixed",
                HostRules.normalize(new String[]{"*", "good.com", "com", "x.y"}),
                new String[]{"good.com", "x.y"});

        System.out.println();
        System.out.println("passed " + pass + ", failed " + fail);
        if (fail > 0) System.exit(1);
    }
}
