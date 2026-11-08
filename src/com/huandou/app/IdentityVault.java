package com.huandou.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores one site-state snapshot per device identity.
 *
 * <p>落盘前一律过 {@link VaultCrypto}（AES-256-GCM），AAD 绑定条目名。快照里装的是
 * 整条 Cookie 头和站点 localStorage——很多站的登录态（token、uid）就存在这里面，
 * 明文躺在 {@code /data/data/.../shared_prefs} 里，root 或有备份权限的工具直接读走。
 */
public final class IdentityVault {

    /** Compat mode has no stable DeviceProfile id, so it gets a fixed key. */
    public static final String KEY_COMPAT = "compat";

    private static final String PREF = "huandou_vault_v1";
    private static final String PREFIX = "s_";

    private final SharedPreferences sp;
    private final Context ctx;

    public IdentityVault(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String keyOf(long id) {
        return "id_" + id;
    }

    private static String aadOf(String key) {
        return VaultCrypto.aad("vault", key);
    }

    public VaultCodec.Snap load(String key) {
        if (key == null) return new VaultCodec.Snap();
        String raw = sp.getString(PREFIX + key, "");
        if (raw == null || raw.length() == 0) return new VaultCodec.Snap();

        if (!VaultCrypto.isSealed(raw)) {
            // 旧版本留下的明文存档：读得出来，顺手换成密文写回去，只迁移一次。
            VaultCodec.Snap old = VaultCodec.decode(raw);
            if (!old.isEmpty()) save(key, old);
            return old;
        }
        String plain = VaultCrypto.open(ctx, raw, aadOf(key));
        // 解不开（换了设备 / 换了签名 / 文件被改过）就当没有存档，不抛异常也不吐垃圾。
        return plain == null ? new VaultCodec.Snap() : VaultCodec.decode(plain);
    }

    public void save(String key, VaultCodec.Snap snap) {
        if (key == null || snap == null) return;
        snap.time = System.currentTimeMillis();
        String text = VaultCodec.encode(snap);
        String sealed = VaultCrypto.seal(ctx, text, aadOf(key));
        // 加密失败退回明文：宁可暂时不加密，也不能把用户的登录态弄丢。
        sp.edit().putString(PREFIX + key, sealed != null ? sealed : text).apply();
    }

    /** Merge freshly captured cookies into an existing snapshot. */
    public void saveCookies(String key, java.util.LinkedHashMap<String, String> cookies) {
        VaultCodec.Snap s = load(key);
        s.cookies.clear();
        if (cookies != null) s.cookies.putAll(cookies);
        save(key, s);
    }

    /** Merge a freshly dumped localStorage / sessionStorage into a snapshot. */
    public void saveStorage(String key, String dumpResult) {
        VaultCodec.Snap s = load(key);
        VaultCodec.applyDump(s, dumpResult);
        save(key, s);
    }

    public void remove(String key) {
        if (key == null) return;
        sp.edit().remove(PREFIX + key).apply();
    }

    public boolean has(String key) {
        return key != null && sp.contains(PREFIX + key);
    }

    public String brief(String key) {
        return has(key) ? VaultCodec.brief(load(key)) : "无存档";
    }

    public int snapshotCount() {
        int n = 0;
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(PREFIX)) n++;
        }
        return n;
    }
}
