package com.huandou.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把最后一次未捕获异常写进内部存储，下次打开诊断页能看到。
 *
 * <p>存在的理由很具体：1.9.0 第一版点按钮必闪退，报「直接给我闪退了」之后，
 * 没有 logcat 只能靠读代码猜——猜对了是运气，猜错了就是来回发版。有了这个文件，
 * 下次只需要一句「诊断页里写着什么」。</p>
 *
 * <p>三条约束：</p>
 * <ul>
 *   <li><b>只存一份</b>，覆盖写，不滚日志。崩溃日志攒多了会占空间，
 *       而且没人看第二份。</li>
 *   <li><b>不吞异常</b>：写完立刻转交给系统默认处理器，崩溃行为、系统弹窗、
 *       Play 的统计全都保持原样。改崩溃行为的诊断工具比不加更糟。</li>
 *   <li><b>不含用户数据</b>：只记栈和版本，不记 URL、cookie、文件路径。</li>
 * </ul>
 */
public final class CrashLog {

    private static final String NAME = "last_crash.txt";
    /** 64KB 足够放下最深的栈，也足够小到不会被当成数据泄露面。 */
    private static final int MAX_BYTES = 64 * 1024;

    private CrashLog() {
    }

    /** 在 Application/Activity 创建时调用一次。重复调用会层层包装，所以只在 onCreate 里调。 */
    public static void install(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    save(app, t, e);
                } catch (Throwable ignored) {
                    // 记录失败绝不能影响原本的崩溃流程。
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    private static void save(Context ctx, Thread t, Throwable e) throws Exception {
        StringWriter sw = new StringWriter(2048);
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("版本 " + ctx.getPackageName());
        pw.println("线程 " + (t == null ? "?" : t.getName()));
        pw.println("类型 " + e.getClass().getName());
        String msg = e.getMessage();
        if (msg != null) pw.println("消息 " + msg);
        pw.println();
        e.printStackTrace(pw);
        pw.flush();

        byte[] data = sw.toString().getBytes("UTF-8");
        if (data.length > MAX_BYTES) {
            // 截断也要留头部：类型、消息、前几帧最有价值。
            byte[] cut = new byte[MAX_BYTES];
            System.arraycopy(data, 0, cut, 0, MAX_BYTES);
            data = cut;
        }

        File f = new File(ctx.getFilesDir(), NAME);
        FileOutputStream fo = new FileOutputStream(f, false);
        try {
            fo.write(data);
            fo.flush();
        } finally {
            fo.close();
        }
    }

    /** 上次崩溃的文本，没有就返回空串。 */
    public static String last(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), NAME);
            if (!f.exists() || f.length() == 0) return "";
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(f.length(), MAX_BYTES));
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            } finally {
                fi.close();
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 用户看过就清掉，免得下次还显示同一条。 */
    public static void clear(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), NAME);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
