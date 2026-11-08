package com.huandou.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 下载链路的滚动日志。
 *
 * <p>为什么需要它：下载失败时的表现形式差异很大——有时弹「下载失败」，有时通知栏一闪就没，
 * 有时干脆什么都不发生。前两种能靠通知猜，最后一种全靠猜。而这个 App 没有服务器、没有崩溃上报，
 * 用户在手机上遇到「点了没反应」，能给我的只有一句描述。
 *
 * <p>所以把关键节点落到文件：服务有没有收到、队列有没有排上、网络拿到多少字节、
 * 保存到 MediaStore 有没有成功。出问题时把诊断页那几行截个图，就能定位到环。
 *
 * <p>不写 Logcat：普通用户不会开 adb，而且 Logcat 会被系统反复冲掉。
 *
 * <p>容量上限 64KB，超了直接从零重写，不滚动备份——这是排障用的临时记录，不是归档。
 */
public final class Diag {

    private static final String FILE = "hd_diag.log";
    private static final long MAX_BYTES = 64 * 1024;

    private Diag() {
    }

    /** 追加一行。任何异常都吞掉：日志写不进去不能影响下载本身。 */
    public static synchronized void log(Context c, String tag, String msg) {
        if (c == null) return;
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (f.length() > MAX_BYTES) {
                // 简单粗暴：满了就清空。保留最近一段比保留最早一段有用。
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " [" + tag + "] " + msg + "\n";
            FileOutputStream os = new FileOutputStream(f, true);
            try {
                os.write(line.getBytes("UTF-8"));
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 取末尾若干行，给诊断页展示。读不到返回空串。 */
    public static synchronized String tail(Context c, int lines) {
        if (c == null) return "";
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists() || f.length() == 0) return "";
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            String all = new String(bo.toByteArray(), "UTF-8");
            String[] arr = all.split("\n");
            int from = Math.max(0, arr.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < arr.length; i++) {
                String s = arr[i];
                if (s.length() == 0) continue;
                sb.append(s).append("\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context c) {
        if (c == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(c.getFilesDir(), FILE).delete();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给 URL 脱敏：去掉全部查询参数，只留主机和路径。
     *
     * <p>为什么要做：视频直链的 query 里带的是 token / sign / expires，一次有效的播放凭证。
     * 日志本来是给自己看的，但只要把诊断页截个图发出去（求助、发群里），
     * 那张图里就等于带着一个能直接播的链接。算法名被人看见无所谓，这个是实打实的凭证泄露。
     *
     * <p>保留主机和路径是有用的：能看出是 /hls/ 还是 /mp4/，能看出走的哪个域名。
     */
    public static String maskUrl(String u) {
        if (u == null) return "null";
        int q = u.indexOf('?');
        String base = q < 0 ? u : u.substring(0, q);
        if (base.length() > 72) base = base.substring(0, 72) + "…";
        return q < 0 ? base : base + "?[已脱敏 " + (u.length() - q - 1) + " 字符]";
    }
}
