package com.huandou.app;

import java.util.Random;

/**
 * Generates coherent, combinatorially randomized User-Agent strings.
 *
 * <p>Every device is a 2022-2026 model and carries its release year, SoC family
 * and tier. Android version, Chrome version, GPU, screen, DPR, core count and
 * RAM are all derived from that metadata instead of being drawn independently,
 * so a profile never claims e.g. "2025 flagship / Android 9 / Chrome 110".
 */
public final class UaFactory {

    /** model | release year | SoC family | tier. All 2022-2026 (last five years). */
    private static final String[] DEVICE_SPECS = {
            // Google Pixel (Tensor)
            "Pixel 7|2022|tensor|flagship", "Pixel 7 Pro|2022|tensor|flagship",
            "Pixel 7a|2023|tensor|mid", "Pixel 8|2023|tensor|flagship",
            "Pixel 8 Pro|2023|tensor|flagship", "Pixel 8a|2024|tensor|mid",
            "Pixel 9|2024|tensor|flagship", "Pixel 9 Pro|2024|tensor|flagship",
            "Pixel 9 Pro XL|2024|tensor|flagship", "Pixel 9 Pro Fold|2024|tensor|flagship",
            "Pixel 9a|2025|tensor|mid", "Pixel 10|2025|tensor|flagship",
            "Pixel 10 Pro|2025|tensor|flagship", "Pixel 10 Pro XL|2025|tensor|flagship",
            "Pixel 10 Pro Fold|2025|tensor|flagship", "Pixel Tablet|2023|tensor|mid",
            // Samsung Galaxy S / Z / A / Tab
            "SM-S901B|2022|qcom|flagship", "SM-S906B|2022|qcom|flagship",
            "SM-S908B|2022|qcom|flagship", "SM-S911B|2023|qcom|flagship",
            "SM-S916B|2023|qcom|flagship", "SM-S918B|2023|qcom|flagship",
            "SM-S921B|2024|exynos|flagship", "SM-S926B|2024|exynos|flagship",
            "SM-S928B|2024|qcom|flagship", "SM-S931B|2025|qcom|flagship",
            "SM-S936B|2025|qcom|flagship", "SM-S937B|2025|qcom|flagship",
            "SM-S938B|2025|qcom|flagship", "SM-S941B|2026|qcom|flagship",
            "SM-S946B|2026|qcom|flagship", "SM-S948B|2026|qcom|flagship",
            "SM-F731B|2023|qcom|flagship", "SM-F741B|2024|qcom|flagship",
            "SM-F761B|2025|qcom|flagship", "SM-F946B|2023|qcom|flagship",
            "SM-F956B|2024|qcom|flagship", "SM-F966B|2025|qcom|flagship",
            "SM-A536B|2022|exynos|mid", "SM-A546B|2023|exynos|mid",
            "SM-A556B|2024|exynos|mid", "SM-A566B|2025|exynos|mid",
            "SM-A156B|2023|mtk|mid", "SM-A256E|2023|exynos|mid",
            "SM-A346B|2023|exynos|mid", "SM-X710|2023|qcom|flagship",
            "SM-X810|2023|qcom|flagship", "SM-X820|2024|mtk|flagship",
            // Xiaomi / Redmi / POCO (model codes, 2022+)
            "2201116SC|2022|mtk|flagship", "2201123C|2022|qcom|flagship",
            "22071219CG|2022|qcom|mid", "22041216C|2022|qcom|mid",
            "22101316C|2022|qcom|flagship", "2211133C|2022|qcom|flagship",
            "22127RK93C|2022|mtk|mid", "23021RAAEG|2023|qcom|mid",
            "23049RAD8C|2023|mtk|flagship", "23076RA4BR|2023|qcom|mid",
            "23127PN0CG|2023|qcom|flagship", "2311DRK48G|2023|qcom|flagship",
            "2312DRA50C|2023|mtk|mid", "24069RA21C|2024|qcom|mid",
            "24094RAD4C|2024|mtk|flagship", "24117RK2CC|2024|qcom|flagship",
            "24031PN0DC|2024|qcom|flagship", "24129PN74C|2024|qcom|flagship",
            "25010RP82C|2025|mtk|flagship", "25053RT47C|2025|qcom|flagship",
            "25054RT19C|2025|qcom|mid", "25060RK16C|2025|qcom|mid",
            // OPPO / OnePlus / realme
            "CPH2451|2022|mtk|mid", "CPH2487|2022|qcom|mid",
            "CPH2557|2023|mtk|mid", "CPH2581|2023|mtk|mid",
            "CPH2609|2024|qcom|mid", "CPH2621|2024|mtk|mid",
            "CPH2639|2024|qcom|flagship", "CPH2681|2025|qcom|flagship",
            "PJD110|2023|qcom|flagship", "PFFM10|2023|qcom|flagship",
            "PGP110|2023|mtk|mid", "PGT110|2023|qcom|flagship",
            "PJZ110|2024|qcom|flagship", "PKB110|2024|mtk|mid",
            "PGZ110|2024|qcom|flagship", "PJX110|2025|qcom|flagship",
            "NE2210|2022|qcom|flagship",
            "RMX3760|2023|qcom|flagship", "RMX3771|2023|qcom|mid",
            "RMX3820|2023|qcom|flagship", "RMX3888|2024|qcom|flagship",
            "RMX5010|2024|qcom|flagship", "RMX6688|2025|qcom|flagship",
            // vivo / iQOO
            "V2217A|2022|qcom|flagship", "V2241HA|2022|mtk|mid",
            "V2243A|2022|qcom|flagship", "V2247A|2022|mtk|mid",
            "V2307A|2023|qcom|flagship", "V2309A|2023|mtk|flagship",
            "V2312A|2023|mtk|mid", "V2324A|2023|qcom|flagship",
            "V2338A|2023|mtk|mid", "V2344A|2024|qcom|flagship",
            "V2352A|2024|mtk|mid", "V2359A|2024|qcom|flagship",
            "V2405A|2024|mtk|mid", "V2408A|2025|qcom|flagship",
            "V2425A|2025|mtk|flagship", "V2454A|2025|qcom|flagship",
            // Huawei / Honor
            "LNA-AL00|2022|kirin|flagship", "ALT-AL00|2023|kirin|flagship",
            "PGT-AN00|2023|kirin|flagship", "REA-AN00|2023|kirin|mid",
            "FNE-AN00|2024|kirin|flagship", "ELZ-AN00|2024|kirin|mid",
            "FRI-AN00|2024|kirin|flagship", "LGE-AN00|2024|kirin|flagship",
            "MAA-AN00|2025|kirin|flagship",
            "HUAWEI P60 Pro|2023|kirin|flagship", "HUAWEI Mate 60 Pro|2023|kirin|flagship",
            "HUAWEI Pura 70 Pro|2024|kirin|flagship", "HUAWEI Mate 70 Pro|2024|kirin|flagship",
            // Motorola / Sony / Nokia / ASUS
            "motorola edge 30|2022|qcom|mid", "motorola edge 40|2023|mtk|mid",
            "motorola edge 50|2024|qcom|mid", "XT2301-5|2023|qcom|mid",
            "XT2341-1|2023|qcom|mid", "XT2403-2|2024|mtk|mid",
            "XQ-DQ72|2023|qcom|flagship", "XQ-CT72|2023|qcom|flagship",
            "XQ-EC72|2024|qcom|flagship", "Nokia G42 5G|2023|qcom|mid",
            "Nokia X30 5G|2022|qcom|mid", "ASUS_AI2205|2022|qcom|flagship",
            "ASUS_AI2401|2024|qcom|flagship"
    };

    /** A device plus everything needed to derive a coherent fingerprint. */
    public static final class Spec {
        public final String model;
        public final int year;
        public final String soc;
        public final String tier;
        private final boolean flagship;

        Spec(String line) {
            String[] a = line.split("\\|");
            model = a[0];
            year = Integer.parseInt(a[1]);
            soc = a[2];
            tier = a[3];
            flagship = "flagship".equals(tier);
        }

        /** Android versions realistic for this model's age (incl. OS upgrades). */
        public String androidVersion(Random r) {
            int[] vs;
            switch (year) {
                case 2022: vs = new int[]{12, 13, 14}; break;
                case 2023: vs = new int[]{13, 14, 15}; break;
                case 2024: vs = new int[]{14, 15, 16}; break;
                case 2025: vs = new int[]{15, 16}; break;
                default:   vs = new int[]{16}; break;      // 2026
            }
            return String.valueOf(vs[r.nextInt(vs.length)]);
        }

        /** Oldest Chrome major a device of this age could plausibly still run in 2026. */
        public int minChrome() {
            return 120 + (year - 2022) * 3;
        }

        /**
         * Chrome version. If alignMajor is set (TLS-consistency mode), stay within
         * +/-1 of the real WebView's Chrome major so the UA matches the network stack.
         */
        public String chromeFull(Random r, String alignMajor) {
            return UaFactory.chromeFull(r, minChrome(), alignMajor);
        }

        /** {vendor, renderer} chosen from the SoC family / year. */
        public String[] gpu(Random r) {
            String[][] pool;
            if ("qcom".equals(soc)) {
                if (year <= 2022) pool = new String[][]{{"Qualcomm", "Adreno (TM) 660"}, {"Qualcomm", "Adreno (TM) 730"}};
                else if (year == 2023) pool = new String[][]{{"Qualcomm", "Adreno (TM) 740"}};
                else if (year == 2024) pool = new String[][]{{"Qualcomm", "Adreno (TM) 740"}, {"Qualcomm", "Adreno (TM) 750"}};
                else pool = new String[][]{{"Qualcomm", "Adreno (TM) 750"}, {"Qualcomm", "Adreno (TM) 830"}};
            } else if ("tensor".equals(soc)) {
                if (year <= 2022) pool = new String[][]{{"ARM", "Mali-G710"}};
                else if (year <= 2024) pool = new String[][]{{"ARM", "Mali-G715"}, {"ARM", "Mali-G720"}};
                else pool = new String[][]{{"ARM", "Mali-G725"}, {"ARM", "Mali-G925"}};
            } else if ("mtk".equals(soc)) {
                if (year <= 2022) pool = new String[][]{{"MediaTek", "Mali-G610"}, {"MediaTek", "Mali-G615"}};
                else if (year <= 2023) pool = new String[][]{{"MediaTek", "Mali-G615"}, {"ARM", "Mali-G720"}};
                else pool = new String[][]{{"ARM", "Mali-G720"}, {"ARM", "Mali-G925"}};
            } else if ("exynos".equals(soc)) {
                if (year <= 2023) pool = new String[][]{{"Samsung", "Xclipse 920"}};
                else pool = new String[][]{{"Samsung", "Xclipse 940"}, {"ARM", "Mali-G925"}};
            } else if ("kirin".equals(soc)) {
                if (year <= 2023) pool = new String[][]{{"Huawei", "Maleoon 910"}};
                else pool = new String[][]{{"Huawei", "Maleoon 910"}, {"Huawei", "Maleoon 920"}};
            } else {
                pool = new String[][]{{"ARM", "Mali-G615"}};
            }
            return pool[r.nextInt(pool.length)];
        }

        public int[] screen(Random r) {
            int[][] p = flagship ? FLAGSHIP_SCREENS : MID_SCREENS;
            return p[r.nextInt(p.length)];
        }

        public float dpr(Random r) {
            float[] p = flagship ? FLAGSHIP_DPR : MID_DPR;
            return p[r.nextInt(p.length)];
        }

        public int cores(Random r) {
            int[] p = flagship ? new int[]{8, 8, 10, 12} : new int[]{6, 8, 8};
            return p[r.nextInt(p.length)];
        }

        public int mem(Random r) {
            int[] p = flagship ? new int[]{8, 12, 12, 16, 24} : new int[]{4, 6, 8, 12};
            return p[r.nextInt(p.length)];
        }
    }

    private static final int[][] FLAGSHIP_SCREENS = {
            {393, 851}, {393, 873}, {412, 915}, {420, 933}, {430, 932}, {448, 998},
            {480, 1029}, {480, 1040}, {480, 1056}, {480, 1067}, {480, 1072}, {540, 1200}
    };
    private static final int[][] MID_SCREENS = {
            {360, 640}, {360, 740}, {360, 780}, {360, 800}, {384, 854},
            {390, 844}, {393, 852}, {412, 732}, {412, 869}
    };
    private static final float[] FLAGSHIP_DPR = {2.625f, 2.75f, 2.8f, 3.0f, 3.0f, 3.5f};
    private static final float[] MID_DPR = {2.0f, 2.25f, 2.5f, 2.75f, 3.0f};

    private static final String[] ANDROID_VERSIONS = {"12", "13", "14", "15", "16"};

    private static final String[] IOS_VERSIONS = {
            "16_6", "16_7", "17_0", "17_1", "17_2", "17_3", "17_4", "17_5", "17_6", "17_7",
            "18_0", "18_1", "18_2", "18_3", "18_4", "18_5", "18_6", "18_7",
            "26_0", "26_1"
    };

    private static final String[] DESKTOP_PLATFORMS = {
            "Windows NT 10.0; Win64; x64", "Windows NT 10.0; Win64; x64",
            "Windows NT 11.0; Win64; x64",
            "Macintosh; Intel Mac OS X 13_6_1", "Macintosh; Intel Mac OS X 14_5",
            "Macintosh; Intel Mac OS X 15_2",
            "X11; Linux x86_64", "X11; Ubuntu; Linux x86_64"
    };

    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String BUILD_PREFIX = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private UaFactory() {
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static String pick(String[] a, Random r) {
        return a[r.nextInt(a.length)];
    }

    public static Spec randomSpec(Random r) {
        return new Spec(DEVICE_SPECS[r.nextInt(DEVICE_SPECS.length)]);
    }

    public static Spec randomSpec() {
        return randomSpec(new Random());
    }

    /** Chrome 110..145 with realistic build / patch numbers (legacy entry point). */
    public static String randomChromeFull(Random r) {
        return chromeFull(r, 110, null);
    }

    /**
     * Shared Chrome version picker. alignMajor (TLS-consistency mode) keeps the
     * major within +/-1 of the device's real WebView so the UA matches the stack
     * that actually performs the TLS handsake.
     */
    public static String chromeFull(Random r, int minMajor, String alignMajor) {
        int min = clamp(minMajor, 110, 145);
        int major;
        if (alignMajor != null && alignMajor.length() > 0) {
            try {
                major = clamp(Integer.parseInt(alignMajor) + r.nextInt(3) - 1, min, 145);
            } catch (Throwable ignored) {
                major = min + r.nextInt(146 - min);
            }
        } else {
            major = min + r.nextInt(146 - min);
        }
        // Chromium build numbers grow roughly 55 per major release.
        int build = 5300 + (major - 110) * 55 + r.nextInt(120);
        return major + ".0." + build + "." + r.nextInt(260);
    }

    private static String buildId(Random r) {
        StringBuilder b = new StringBuilder();
        b.append(BUILD_PREFIX.charAt(r.nextInt(BUILD_PREFIX.length())));
        for (int i = 0; i < 3; i++) b.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        b.append('.');
        for (int i = 0; i < 6; i++) b.append(r.nextInt(10));
        b.append('.');
        for (int i = 0; i < 3; i++) b.append(r.nextInt(10));
        return b.toString();
    }

    public static String randomDevice(Random r) {
        return randomSpec(r).model;
    }

    public static String randomAndroidVersion(Random r) {
        return pick(ANDROID_VERSIONS, r);
    }

    public static String randomIosVersion(Random r) {
        return pick(IOS_VERSIONS, r);
    }

    public static String randomDesktopPlatform(Random r) {
        return pick(DESKTOP_PLATFORMS, r);
    }

    public static int deviceCount() {
        return DEVICE_SPECS.length;
    }

    public static String yearRange() {
        int min = 9999, max = 0;
        for (String line : DEVICE_SPECS) {
            int y = Integer.parseInt(line.split("\\|")[1]);
            if (y < min) min = y;
            if (y > max) max = y;
        }
        return min + "–" + max;
    }

    /** Human-readable hint for the identity UI. */
    public static String varietyHint() {
        return deviceCount() + " 款 " + yearRange() + " 年机型 × 按年份匹配的 Android 版本 × "
                + "按年份/芯片匹配的 Chrome 与 GPU × 随机构建号 × 屏幕/DPR/核数/内存，"
                + "且每个身份的机型、系统、浏览器、GPU 互相自洽";
    }

    /** UA for a specific Android device / OS / Chrome version. */
    public static String androidUa(String device, String av, String chromeFull, Random r) {
        String platform = "Linux; Android " + av + "; " + device;
        if (r.nextInt(10) < 7) platform += " Build/" + buildId(r);
        return "Mozilla/5.0 (" + platform + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chromeFull + " Mobile Safari/537.36";
    }

    public static String iosUa(String iosVersion) {
        String[] p = iosVersion.split("_");
        return "Mozilla/5.0 (iPhone; CPU iPhone OS " + iosVersion
                + " like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/"
                + p[0] + "." + p[1] + " Mobile/15E148 Safari/604.1";
    }

    public static String desktopUa(String chromeFull, String platform) {
        return "Mozilla/5.0 (" + platform
                + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeFull + " Safari/537.36";
    }

    public static String randomAndroid() {
        return randomAndroid(new Random());
    }

    public static String randomAndroid(Random r) {
        Spec s = randomSpec(r);
        return androidUa(s.model, s.androidVersion(r), s.chromeFull(r, null), r);
    }

    public static String randomIos() {
        return randomIos(new Random());
    }

    public static String randomIos(Random r) {
        return iosUa(randomIosVersion(r));
    }

    public static String randomDesktop() {
        return randomDesktop(new Random());
    }

    public static String randomDesktop(Random r) {
        return desktopUa(randomChromeFull(r), randomDesktopPlatform(r));
    }

    public static String random() {
        return random(new Random());
    }

    public static String random(Random r) {
        int n = r.nextInt(10);
        if (n < 8) return randomAndroid(r);
        if (n == 8) return randomIos(r);
        return randomDesktop(r);
    }
}
