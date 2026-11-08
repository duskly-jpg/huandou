package com.huandou.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Persists several device identities and remembers the active one. */
public class IdentityManager {

    private static final String PREF = "huandou_identities";
    private static final String KEY_LIST = "list";
    private static final String KEY_ACTIVE = "active_id";

    private final SharedPreferences sp;
    private final List<DeviceProfile> items = new ArrayList<DeviceProfile>();

    public IdentityManager(Context c) {
        sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        items.clear();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                DeviceProfile p = DeviceProfile.fromJson(a.getString(i));
                if (p != null) items.add(p);
            }
        } catch (Exception ignored) {
        }
    }

    private void persist() {
        JSONArray a = new JSONArray();
        for (DeviceProfile p : items) a.put(p.toJson());
        sp.edit().putString(KEY_LIST, a.toString()).apply();
    }

    public List<DeviceProfile> list() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public DeviceProfile get(int i) {
        return (i >= 0 && i < items.size()) ? items.get(i) : null;
    }

    public void add(DeviceProfile p) {
        items.add(p);
        persist();
    }

    public boolean contains(long id) {
        for (DeviceProfile p : items) {
            if (p.id == id) return true;
        }
        return false;
    }

    public void remove(int i) {
        if (i >= 0 && i < items.size()) {
            items.remove(i);
            persist();
        }
    }

    public void update(DeviceProfile p) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == p.id) {
                items.set(i, p);
                persist();
                return;
            }
        }
    }

    public DeviceProfile active() {
        long id = sp.getLong(KEY_ACTIVE, 0L);
        for (DeviceProfile p : items) {
            if (p.id == id) return p;
        }
        return items.isEmpty() ? null : items.get(0);
    }

    public void setActive(DeviceProfile p) {
        sp.edit().putLong(KEY_ACTIVE, p == null ? 0L : p.id).apply();
    }

    public String nextLabel() {
        return "身份 " + (size() + 1);
    }
}
