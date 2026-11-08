package com.huandou.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 用户脚本仓库：一个 index.json 存元数据，每个脚本的正文单独放 &lt;id&gt;.js。
 * 正文单独存是为了列表刷新时不用把几百 KB 的代码全读进内存。
 */
public final class UserscriptStore {

    private static final String DIR = "userscripts";
    private static final String INDEX = "index.json";
    private static final int MAX_CODE = 1024 * 1024;
    private static final int MAX_FETCH = 3 * 1024 * 1024;

    private static UserscriptStore inst;

    private final File dir;
    private final Context ctx;
    private final ArrayList<Userscript> list = new ArrayList<Userscript>();
    private boolean loaded;

    private UserscriptStore(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // 存不进去也不能崩，退回内存模式（重启即失）。
        }
    }

    public static synchronized UserscriptStore get(Context c) {
        if (inst == null) inst = new UserscriptStore(c.getApplicationContext());
        return inst;
    }

    public synchronized ArrayList<Userscript> all() {
        load();
        return new ArrayList<Userscript>(list);
    }

    public synchronized int size() {
        load();
        return list.size();
    }

    public synchronized int enabledCount() {
        load();
        int n = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).enabled) n++;
        return n;
    }

    public synchronized Userscript byId(String id) {
        load();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return list.get(i);
        }
        return null;
    }

    /** 命中该网址的脚本，按 run-at 过滤。没有则返回空数组。 */
    public synchronized ArrayList<Userscript> forUrl(String url, String runAt) {
        load();
        ArrayList<Userscript> out = new ArrayList<Userscript>();
        for (int i = 0; i < list.size(); i++) {
            Userscript u = list.get(i);
            if (!u.enabled) continue;
            if (runAt != null && !runAt.equals(u.runAt)) continue;
            if (u.appliesTo(url)) out.add(u);
        }
        return out;
    }

    public synchronized boolean hasStartScript(String url) {
        return forUrl(url, Userscript.RUN_START).size() > 0;
    }

    public synchronized Userscript add(Userscript u, String code) {
        load();
        if (u.id == null || u.id.length() == 0) {
            u.id = "u" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString((int) (Math.random() * 1296), 36);
        }
        u.bytes = code.length();
        writeFile(u.id + ".js", code);
        // 覆盖安装同 id 的旧脚本时先摘掉旧的，避免列表里出现两条。
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(u.id)) list.remove(i);
        }
        list.add(0, u);
        saveIndex();
        return u;
    }

    public synchronized void remove(String id) {
        load();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        new File(dir, id + ".js").delete();
        for (int i = 0; i < 8; i++) new File(dir, id + ".req" + i + ".js").delete();
        saveIndex();
    }

    public synchronized void setEnabled(String id, boolean on) {
        load();
        Userscript u = byId(id);
        if (u == null) return;
        u.enabled = on;
        saveIndex();
    }

    public synchronized void touch(String id) {
        load();
        saveIndex();
    }

    public synchronized String readCode(Userscript u) {
        String s = readFile(u.id + ".js");
        return s == null ? "" : s;
    }

    /** @require 的外部库，按声明顺序拼在正文前面。 */
    public synchronized String readRequires(Userscript u) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < u.requires.size() && i < 8; i++) {
            String s = readFile(u.id + ".req" + i + ".js");
            if (s != null && s.length() > 0) {
                b.append("/* @require ").append(u.requires.get(i)).append(" */\n");
                b.append(s).append("\n;\n");
            }
        }
        return b.toString();
    }

    /**
     * 安装时把 @require 的库抓到本地。返回下载失败的地址。
     * 放在安装阶段做，是为了运行时注入不必等网络，也不会因为离线而白屏。
     */
    public ArrayList<String> fetchRequires(Userscript u) {
        ArrayList<String> failed = new ArrayList<String>();
        for (int i = 0; i < u.requires.size() && i < 8; i++) {
            String url = u.requires.get(i);
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
            String body = fetch(url, 512 * 1024);
            if (body == null || body.length() == 0) {
                failed.add(url);
                continue;
            }
            writeFile(u.id + ".req" + i + ".js", body);
        }
        return failed;
    }

    // ---------------- 文件 ----------------

    private void load() {
        if (loaded) return;
        loaded = true;
        String raw = readFile(INDEX);
        if (raw == null || raw.length() == 0) return;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Userscript u = new Userscript();
                u.id = o.optString("id", "");
                if (u.id.length() == 0) continue;
                u.name = o.optString("name", "");
                u.namespace = o.optString("namespace", "");
                u.version = o.optString("version", "");
                u.description = o.optString("description", "");
                u.icon = o.optString("icon", "");
                u.sourceUrl = o.optString("sourceUrl", "");
                u.runAt = o.optString("runAt", Userscript.RUN_END);
                u.enabled = o.optBoolean("enabled", true);
                u.installedAt = o.optLong("installedAt", 0L);
                u.bytes = o.optInt("bytes", 0);
                fill(u.matches, o.optJSONArray("matches"));
                fill(u.includes, o.optJSONArray("includes"));
                fill(u.excludes, o.optJSONArray("excludes"));
                fill(u.requires, o.optJSONArray("requires"));
                fill(u.grants, o.optJSONArray("grants"));
                list.add(u);
            }
        } catch (Throwable ignored) {
        }
        // 旧版本存的是明文 .js，这里顺手换成密文，只跑一次（下次读到的已经是 g2:）。
        for (int i = 0; i < list.size(); i++) {
            Userscript u = list.get(i);
            migratePlain(u.id + ".js");
            for (int k = 0; k < 8; k++) migratePlain(u.id + ".req" + k + ".js");
        }
    }

    /** 文件还是明文就重写一次，让它变成密文。读不出来（已加密）就跳过。 */
    private void migratePlain(String name) {
        File f = new File(dir, name);
        if (!f.isFile() || f.length() < 3) return;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] head = new byte[3];
            int n = in.read(head);
            if (n == 3 && head[0] == 'g' && head[1] == '2' && head[2] == ':') return;
        } catch (Throwable t) {
            return;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        String text = readFile(name);
        if (text != null && text.length() > 0) writeFile(name, text);
    }

    private static void fill(ArrayList<String> dst, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (s.length() > 0) dst.add(s);
        }
    }

    private void saveIndex() {
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < list.size(); i++) {
                Userscript u = list.get(i);
                JSONObject o = new JSONObject();
                o.put("id", u.id);
                o.put("name", u.name);
                o.put("namespace", u.namespace);
                o.put("version", u.version);
                o.put("description", u.description);
                o.put("icon", u.icon);
                o.put("sourceUrl", u.sourceUrl);
                o.put("runAt", u.runAt);
                o.put("enabled", u.enabled);
                o.put("installedAt", u.installedAt);
                o.put("bytes", u.bytes);
                o.put("matches", new JSONArray(u.matches));
                o.put("includes", new JSONArray(u.includes));
                o.put("excludes", new JSONArray(u.excludes));
                o.put("requires", new JSONArray(u.requires));
                o.put("grants", new JSONArray(u.grants));
                a.put(o);
            }
            writeFile(INDEX, a.toString());
        } catch (Throwable ignored) {
        }
    }

    private String readFile(String name) {
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length() + 16, MAX_CODE));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), "UTF-8");
            if (!sealedFile(name) || !VaultCrypto.isSealed(text)) return text;
            // 解不开返回 null：宁可这个脚本这一次不跑，也不能把密文当代码注入网页。
            return VaultCrypto.open(ctx, text, VaultCrypto.aad("us", name));
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private void writeFile(String name, String text) {
        String payload = text;
        if (sealedFile(name)) {
            String sealed = VaultCrypto.seal(ctx, text, VaultCrypto.aad("us", name));
            if (sealed != null) payload = sealed;
        }
        File tmp = new File(dir, name + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(payload.getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            File dst = new File(dir, name);
            if (!tmp.renameTo(dst)) {
                // 某些文件系统不支持覆盖式 rename。
                dst.delete();
                tmp.renameTo(dst);
            }
        } catch (Throwable ignored) {
            tmp.delete();
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 只加密脚本正文，index.json 保持明文。
     *
     * <p>index 里是名字、版本、match 这些元数据，不含密钥；而它一旦解不开，脚本列表
     * 会整体消失——为了那点元数据搭上「整个仓库看起来空了」的风险不划算。
     */
    private static boolean sealedFile(String name) {
        return name != null && name.endsWith(".js");
    }

    // ---------------- 网络 ----------------

    /** 纯文本抓取。失败返回 null，不抛异常。 */
    public static String fetch(String url, int max) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/javascript,*/*");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    in.close();
                    return null;
                }
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

}
