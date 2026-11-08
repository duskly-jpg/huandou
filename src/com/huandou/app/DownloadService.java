package com.huandou.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.webkit.CookieManager;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;

/**
 * Foreground download service. Downloads keep running when the Activity is destroyed,
 * the screen is off, or the user switches to another app. Progress is shown in a
 * persistent notification with a cancel action; completed files are written to the
 * public Downloads/Huandou folder through MediaStore and can be opened by tapping
 * the notification.
 *
 * <p>Jobs are queued and processed one at a time. A PARTIAL_WAKE_LOCK keeps the CPU
 * awake for the duration of each job (with a 30-minute safety timeout).</p>
 */
public class DownloadService extends Service {

    public static final String ACTION_START = "com.huandou.app.action.DL_START";
    public static final String ACTION_CANCEL = "com.huandou.app.action.DL_CANCEL";

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_UA = "ua";
    public static final String EXTRA_COOKIE = "cookie";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_TO_MP4 = "to_mp4";

    // 渠道 id 带版本后缀：Android 的 NotificationChannel 一旦创建，其重要性就不可修改，
    // 只能删掉旧渠道、用新 id 重建。
    //  - v1 用 IMPORTANCE_LOW：LOW 的通知**不显示状态栏图标**，用户会以为「没有通知」。
    //  - v3 把声音设成了 null：MIUI / ColorOS 会把「无声渠道」归为不重要通知，
    //    状态栏图标被折叠甚至不显示。v4 恢复默认提示音与 DEFAULT 重要性。
    private static final String CHANNEL_ID = "huandou_download_v4";
    private static final String[] LEGACY_CHANNELS = {
            "huandou_download", "huandou_download_v2", "huandou_download_v3"
    };
    private static final int NOTI_ID = 20261104;

    /** 应用内反馈回调：通知被系统禁用时，用户仍能在界面上看到进度与结果。 */
    public interface UiCallback {
        void onDownloadEvent(String text, int pct, boolean finished);
    }

    private static volatile UiCallback sUiCallback;
    /** 界面不在前台时暂存的最终结果，回到应用后补提示（通知不可用时尤其重要）。 */
    private static volatile String sPendingMsg;
    /** 最后一次向界面/通知发出事件的时间，用于判断服务是否真的跑起来了。 */
    private static volatile long sLastEventMs;
    private static volatile boolean sAlive;

    public static void setUiCallback(UiCallback cb) {
        sUiCallback = cb;
    }

    public static long lastEventMs() {
        return sLastEventMs;
    }

    public static boolean isAlive() {
        return sAlive;
    }

    public static String takePendingMsg() {
        String s = sPendingMsg;
        sPendingMsg = null;
        return s;
    }

    /** 通知已经显示过结果时调用，避免回到应用后又弹一遍同样的提示。 */
    public static void clearPending() {
        sPendingMsg = null;
    }
    private static final int PI_FLAGS_BASE = PendingIntent.FLAG_UPDATE_CURRENT;

    private static final class Job {
        String url;
        String ua;
        String cookie;
        String referer;
        String name;
        boolean toMp4;

        static Job from(Intent i) {
            Job j = new Job();
            j.url = i.getStringExtra(EXTRA_URL);
            j.ua = i.getStringExtra(EXTRA_UA);
            j.cookie = i.getStringExtra(EXTRA_COOKIE);
            j.referer = i.getStringExtra(EXTRA_REFERER);
            j.name = i.getStringExtra(EXTRA_NAME);
            j.toMp4 = i.getBooleanExtra(EXTRA_TO_MP4, true);
            if (j.name == null || j.name.length() == 0) {
                j.name = "huandou_" + System.currentTimeMillis() + ".mp4";
            }
            return j;
        }
    }

    private final LinkedList<Job> queue = new LinkedList<Job>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile boolean foregroundStarted;
    private volatile String currentTitle = "幻豆 · 视频下载";
    private volatile long lastProgressPost;

    @Override
    public void onCreate() {
        super.onCreate();
        sAlive = true;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(this);
    }

    /**
     * 建渠道。Activity（测试通知）和服务共用同一份定义，避免两边 id / 重要性不一致。
     */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager m = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (m == null) return;
        for (String old : LEGACY_CHANNELS) {
            try {
                m.deleteNotificationChannel(old);
            } catch (Throwable ignored) {
            }
        }
        NotificationChannel c = new NotificationChannel(CHANNEL_ID,
                "视频下载", NotificationManager.IMPORTANCE_DEFAULT);
        c.setDescription("后台下载的进度、完成与失败提醒");
        c.setShowBadge(true);
        c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        c.enableVibration(false);
        try {
            android.net.Uri sound = android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_NOTIFICATION);
            if (sound != null) {
                android.media.AudioAttributes aa =
                        new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(android.media.AudioAttributes
                                        .CONTENT_TYPE_SONIFICATION)
                                .build();
                c.setSound(sound, aa);
            }
        } catch (Throwable ignored) {
        }
        // 已存在时 createNotificationChannel 不会改重要性，所以只在缺失时创建。
        try {
            if (m.getNotificationChannel(CHANNEL_ID) == null) m.createNotificationChannel(c);
        } catch (Throwable ignored) {
        }
    }

    private void createChannel() {
        ensureChannel(this);
    }

    /** 通知是否真的能被用户看到（权限被拒 / 用户在系统里关掉通知时为 false）。 */
    private boolean canPostNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    private void postUi(final String text, final int pct, final boolean finished) {
        sLastEventMs = System.currentTimeMillis();
        if (finished) sPendingMsg = text;
        final UiCallback cb = sUiCallback;
        if (cb == null) return;
        if (finished) sPendingMsg = null;   // 已实时提示，不必回前台再补一次
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onDownloadEvent(text, pct, finished);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Diag.log(this, "svc", "onStart action=" + (intent == null ? "null" : intent.getAction()));
        // 第二道签名校验。MainActivity 那处判断可以被 patch 掉，但即便被 patch，
        // 服务这里也会拒绝干活：改过的包下不了文件，也发不出通知。
        if (!SignatureGuard.ok(this)) {
            Diag.log(this, "svc", "签名校验未通过，拒绝下载");
            stopSelf();
            return START_NOT_STICKY;
        }
        // 第三道：设备凭据。能 patch 掉签名校验的人，未必会去 patch 服务里的
        // 每一次下载任务；而门禁凭据是服务端签的，他造不出来。
        if (!Gate.allowed(this)) {
            Diag.log(this, "svc", "无有效设备凭据，拒绝下载");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            if (!running && queue.isEmpty()) stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelled = true;
            queue.clear();
            if (!running) {
                stopForeground(false);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (intent.getStringExtra(EXTRA_URL) == null) return START_NOT_STICKY;
            if (!foregroundStarted) {
                foregroundStarted = true;
                Notification n = buildNotification("幻豆 · 视频下载", "准备下载…",
                        -1, true, true, null, null);
                boolean fgOk = true;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTI_ID, n,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(NOTI_ID, n);
                    }
                } catch (Throwable t) {
                    Diag.log(this, "svc", "startForeground 抛异常：" + t.getClass().getName()
                            + " " + t.getMessage());
                    // Android 12+ 后台启动限制、Android 14 前台服务类型校验等都可能在此抛异常。
                    // 若不兜住，服务会直接崩溃 —— 用户看到的就是「点了下载，既没通知也没文件」。
                    // 退回不带类型的调用；仍失败则保留服务存活，下载继续，进度在应用内可见。
                    try {
                        startForeground(NOTI_ID, n);
                    } catch (Throwable t2) {
                        fgOk = false;
                        // 没进前台，服务随时可能被系统回收，允许下次任务重新尝试。
                        foregroundStarted = false;
                    }
                }
                if (!fgOk) {
                    // 两次都进不了前台：系统会在 5 秒内杀掉进程，此时静默继续只会让用户
                    // 看到「点了没反应」。直接说明原因并退出，比假装在下载诚实。
                    postUi("无法进入后台下载：系统拒绝了前台服务（请允许「幻豆」后台运行）",
                            100, true);
                    stopSelf();
                    return START_NOT_STICKY;
                }
                if (!canPostNotifications()) {
                    postUi("通知被系统禁用：下载照常进行，进度在应用内显示", 0, false);
                }
            }
            queue.add(Job.from(intent));
            Diag.log(this, "svc", "入队 url=" + Diag.maskUrl(intent.getStringExtra(EXTRA_URL))
                    + " queue=" + queue.size() + " running=" + running);
            if (!running) pump();
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void pump() {
        if (running) return;
        final Job j = queue.poll();
        if (j == null) {
            Diag.log(this, "pump", "队列为空，停止服务");
            stopForeground(false);
            stopSelf();
            return;
        }
        running = true;
        cancelled = false;
        lastProgressPost = 0;
        currentTitle = "下载：" + stripExt(j.name);
        Diag.log(this, "pump", "开始任务 " + Diag.maskUrl(j.url) + " → " + j.name
                + " toMp4=" + j.toMp4);
        notifyNow(buildNotification(currentTitle, "准备下载…", -1,
                true, true, null, null));
        postUi("开始下载：" + stripExt(j.name), 0, false);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runJob(j);
            }
        }, "hd-download");
        t.setDaemon(false);
        t.start();
    }

    private void runJob(final Job j) {
        acquireWake();
        Diag.log(this, "job", "线程启动 ua=" + (j.ua == null ? "null" : "有")
                + " cookie=" + (j.cookie == null ? "null" : "有")
                + " referer=" + (j.referer == null ? "null" : "有"));
        long stamp = System.currentTimeMillis();
        File tmp = new File(getCacheDir(), "hd_dl_" + stamp + ".ts");
        File mp4 = new File(getCacheDir(), "hd_dl_" + stamp + ".mp4");
        boolean ok = false;
        String err = null;
        String note = "";
        Uri outUri = null;
        String finalName = j.name;
        String finalMime = "video/*";
        boolean[] legacy = new boolean[]{false};
        try {
            MediaDownloader md = new MediaDownloader(j.ua, j.cookie, j.referer,
                    new MediaDownloader.Progress() {
                        @Override
                        public void onProgress(int done, int total, String label) {
                            int pct;
                            if (total > 0) {
                                pct = 5 + (int) (70L * done / total);
                                if (pct > 78) pct = 78;
                            } else {
                                pct = -1;
                            }
                            postProgress("已下载 " + (label == null ?
                                    String.valueOf(done) : label), pct);
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled;
                        }
                    });
            md.setCookieProvider(new MediaDownloader.CookieProvider() {
                @Override
                public String cookieFor(String u) {
                    try {
                        return CookieManager.getInstance().getCookie(u);
                    } catch (Throwable t2) {
                        return j.cookie;
                    }
                }
            });

            OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp));
            long bytes;
            try {
                bytes = md.run(j.url, os);
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
            if (cancelled) throw new Exception("已取消");
            Diag.log(this, "job", "网络完成 " + bytes + " 字节 ext=" + md.outputExt
                    + " mime=" + md.outputMime);
            if (bytes <= 0) throw new Exception("内容为空，这个地址可能是接口而非视频直链");

            String ext = md.outputExt;
            String mime = md.outputMime;
            File payload = tmp;

            if (j.toMp4 && ".ts".equals(ext) && TsToMp4.isTsFile(tmp)) {
                postProgress("正在封装为 MP4…", 80);
                try {
                    TsToMp4.Result r = TsToMp4.convert(tmp, mp4, new TsToMp4.Progress() {
                        @Override
                        public void onProgress(int pct, String label) {
                            postProgress(label, 80 + pct / 5);
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled;
                        }
                    });
                    payload = mp4;
                    ext = ".mp4";
                    mime = "video/mp4";
                    note = "已转 MP4（" + r.videoFrames + " 帧 / "
                            + (r.durationMs / 1000) + " 秒，无损封装）";
                } catch (Throwable t2) {
                    if (cancelled) throw new Exception("已取消");
                    note = "MP4 封装未成功，已保留 TS：" + shortMsg(t2);
                }
            } else if (".mp4".equals(ext)) {
                note = "MP4 直链";
            }
            if (md.wasEncrypted) {
                note = "已按标准 AES-128 解密" + (note.length() == 0 ? "" : " · " + note);
            }

            finalName = stripExt(j.name) + ext;
            finalMime = mime;
            outUri = saveToDownloads(payload, finalName, mime, legacy);
            Diag.log(this, "job", "已保存 " + finalName + " → " + outUri);
            ok = true;
        } catch (Throwable t) {
            err = t.getMessage() == null ? t.toString() : t.getMessage();
            Diag.log(this, "job", "失败 " + t.getClass().getName() + ": " + err);
        } finally {
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
            try {
                mp4.delete();
            } catch (Throwable ignored) {
            }
            releaseWake();

            final String fName = finalName;
            final String fMime = finalMime;
            final String fNote = note;
            final Uri fUri = outUri;
            final String fErr = err;
            final boolean fOk = ok;
            final boolean fCancelled = cancelled;
            final boolean fLegacy = legacy[0];
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (fCancelled && !fOk) {
                        notifyNow(buildNotification("下载已取消", j.name,
                                -2, false, false, null, null));
                        postUi("下载已取消", 100, true);
                    } else if (fOk) {
                        String text = "保存到 下载/Huandou/"
                                + (fNote.length() == 0 ? "" : " · " + fNote)
                                + (fLegacy ? "（旧系统请用文件管理器打开）" : "（点开可播放）");
                        notifyNow(buildNotification("下载完成：" + fName, text,
                                -2, false, false, fUri, fMime));
                        postUi("下载完成：" + fName + " → 下载/Huandou/", 100, true);
                    } else {
                        Notification nf = buildNotification("下载失败：" + shortText(fErr),
                                fErr, -2, false, false, null, null);
                        // 失败不能一闪而过：去掉自动清除，让用户回来还能看到原因。
                        try {
                            nf.flags &= ~Notification.FLAG_AUTO_CANCEL;
                        } catch (Throwable ignored) {
                        }
                        notifyNow(nf);
                        postUi("下载失败：" + shortText(fErr), 100, true);
                    }
                    running = false;
                    pump();
                }
            });
        }
    }

    // ------------------------------------------------------------------ output

    private Uri saveToDownloads(File src, String name, String mime, boolean[] legacy)
            throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Huandou");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("无法创建下载文件");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("无法写入下载文件");
            try {
                copy(src, os);
            } finally {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "Huandou");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
        File dst = new File(dir, name);
        // 同名不覆盖：不同剧集撞上同一个名字时，加 (2)(3) 而不是把前一个文件冲掉。
        if (dst.exists()) {
            String base = stripExt(name);
            String ext = name.substring(base.length());
            for (int i = 2; i < 100 && dst.exists(); i++) {
                dst = new File(dir, base + " (" + i + ")" + ext);
            }
        }
        OutputStream os = new BufferedOutputStream(new FileOutputStream(dst));
        try {
            copy(src, os);
        } finally {
            try {
                os.close();
            } catch (Throwable ignored) {
            }
        }
        legacy[0] = true;
        try {
            MediaScannerConnection.scanFile(this,
                    new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void copy(File src, OutputStream os) throws Exception {
        InputStream in = new FileInputStream(src);
        byte[] buf = new byte[256 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            in.close();
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String shortMsg(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.length() == 0) s = t.getClass().getSimpleName();
        return shortText(s);
    }

    private static String shortText(String s) {
        if (s == null) return "未知错误";
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
    }

    // ------------------------------------------------------------------ notification

    private void postProgress(final String text, final int pct) {
        long now = System.currentTimeMillis();
        if (pct >= 0 && pct < 100 && now - lastProgressPost < 300) return;
        lastProgressPost = now;
        postUi(text, pct, false);
        handler.post(new Runnable() {
            @Override
            public void run() {
                notifyNow(buildNotification(currentTitle, text, pct,
                        true, true, null, null));
            }
        });
    }

    private void notifyNow(Notification n) {
        try {
            nm.notify(NOTI_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String title, String text, int pct,
                                           boolean ongoing, boolean showCancel,
                                           Uri openUri, String mime) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_download);
        b.setContentTitle(title);
        if (text != null) b.setContentText(text);
        b.setOnlyAlertOnce(true);
        b.setOngoing(ongoing);
        b.setWhen(System.currentTimeMillis());
        b.setShowWhen(true);
        b.setColor(0xFF9B7BFF);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        b.setCategory(ongoing ? Notification.CATEGORY_PROGRESS : Notification.CATEGORY_STATUS);
        // Android 12+ 默认会把「前台服务通知」压后 10 秒才显示。下载常常几秒就结束，
        // 等通知出来时任务已经跑完，用户看到的就是「一点反应都没有」。
        // FOREGROUND_SERVICE_IMMEDIATE 让通知立即出现，这是通知不显示的关键修复。
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        } else if (Build.VERSION.SDK_INT >= 26) {
            b.setPriority(Notification.PRIORITY_DEFAULT);
        } else {
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        if (pct >= 0) {
            b.setProgress(100, pct, false);
        } else if (pct == -1) {
            b.setProgress(100, 0, true);
        }
        if (showCancel) {
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "取消", cancelPendingIntent()).build());
        }
        Intent target;
        if (openUri != null) {
            target = new Intent(Intent.ACTION_VIEW);
            target.setDataAndType(openUri, mime == null ? "video/*" : mime);
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            target = new Intent(this, MainActivity.class);
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        b.setContentIntent(PendingIntent.getActivity(this, 3, target, piFlags()));
        if (!ongoing) b.setAutoCancel(true);
        return b.build();
    }

    private PendingIntent cancelPendingIntent() {
        Intent i = new Intent(this, DownloadService.class);
        i.setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 1, i, piFlags());
    }

    private static int piFlags() {
        int f = PI_FLAGS_BASE;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    // ------------------------------------------------------------------ wake lock

    private synchronized void acquireWake() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "huandou:download");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(30 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private synchronized void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        sAlive = false;
        cancelled = true;
        queue.clear();
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
