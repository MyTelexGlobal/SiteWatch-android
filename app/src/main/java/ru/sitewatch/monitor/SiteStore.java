package ru.sitewatch.monitor;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SiteStore {
    private static final String PREFS = "monitor_data_v1";
    private static final Object LOCK = new Object();
    public static final int DEFAULT_INTERVAL_MIN = 5;

    private SiteStore() {}

    public static class Site {
        public String id;
        public String url;
        public String state = "UNKNOWN";
        public String details = "Ожидает проверки";
        public int httpCode = -1;
        public String errorType = "";
        public long checkedAt = 0;
        public long latencyMs = 0;

        public Site(String id, String url) {
            this.id = id;
            this.url = url;
        }

        JSONObject toJson() {
            JSONObject item = new JSONObject();
            try {
                item.put("id", id);
                item.put("url", url);
                item.put("state", state);
                item.put("details", details);
                item.put("httpCode", httpCode);
                item.put("errorType", errorType);
                item.put("checkedAt", checkedAt);
                item.put("latencyMs", latencyMs);
            } catch (Exception ignored) {}
            return item;
        }

        static Site fromJson(JSONObject item) {
            Site site = new Site(item.optString("id"), item.optString("url"));
            site.state = item.optString("state", "UNKNOWN");
            site.details = item.optString("details", "Ожидает проверки");
            site.httpCode = item.optInt("httpCode", -1);
            site.errorType = item.optString("errorType", "");
            // Read status from v1.0 data so previously checked sites show their HTTP code.
            if (site.httpCode < 0 && site.details.startsWith("HTTP ")) {
                String[] parts = site.details.split(" ");
                if (parts.length >= 2) {
                    try { site.httpCode = Integer.parseInt(parts[1]); }
                    catch (NumberFormatException ignored) { }
                }
            }
            // Upgrade results saved by v1.0/v1.1, without losing the saved site list.
            if (site.httpCode < 0 && site.errorType.isEmpty() && "DOWN".equals(site.state)) {
                if (site.details.startsWith("Превышено время ожидания")
                        || site.details.startsWith("Тайм-аут")) site.errorType = "TIMEOUT";
                else if (site.details.contains("DNS")) site.errorType = "DNS";
                else if (site.details.contains("SSL")) site.errorType = "SSL";
                else if (site.details.startsWith("Нет соединения")) site.errorType = "CONNECT";
                else site.errorType = "NETWORK";
            }
            site.checkedAt = item.optLong("checkedAt", 0);
            site.latencyMs = item.optLong("latencyMs", 0);
            return site;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Site> all(Context ctx) {
        synchronized (LOCK) {
            return read(ctx);
        }
    }

    private static List<Site> read(Context ctx) {
        List<Site> sites = new ArrayList<>();
        String raw = prefs(ctx).getString("sites", "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) sites.add(Site.fromJson(item));
            }
        } catch (Exception ignored) {}
        return sites;
    }

    private static void save(Context ctx, List<Site> sites) {
        JSONArray array = new JSONArray();
        for (Site s : sites) array.put(s.toJson());
        prefs(ctx).edit().putString("sites", array.toString()).commit();
    }

    /** Returns the inserted site, or null if that URL already exists. */
    public static Site add(Context ctx, String url) {
        synchronized (LOCK) {
            List<Site> sites = read(ctx);
            for (Site s : sites) {
                if (s.url.equalsIgnoreCase(url)) return null;
            }
            Site site = new Site(UUID.randomUUID().toString(), url);
            sites.add(site);
            save(ctx, sites);
            return site;
        }
    }

    public static void remove(Context ctx, String id) {
        synchronized (LOCK) {
            List<Site> sites = read(ctx);
            for (int i = sites.size() - 1; i >= 0; i--) {
                if (sites.get(i).id.equals(id)) sites.remove(i);
            }
            save(ctx, sites);
        }
    }

    /** Returns true if a previously DOWN site has just recovered. */
    public static boolean update(Context ctx, String id, String state, String details,
                                 int httpCode, String errorType, long checkedAt, long latencyMs) {
        synchronized (LOCK) {
            List<Site> sites = read(ctx);
            for (Site s : sites) {
                if (s.id.equals(id)) {
                    if (!httpEnabled(ctx) && UrlPolicy.isHttp(s.url)) {
                        markHttpBlocked(s);
                        save(ctx, sites);
                        return false;
                    }
                    boolean recovered = "DOWN".equals(s.state) && "UP".equals(state);
                    s.state = state;
                    s.details = details;
                    s.httpCode = httpCode;
                    s.errorType = errorType;
                    s.checkedAt = checkedAt;
                    s.latencyMs = latencyMs;
                    save(ctx, sites);
                    return recovered;
                }
            }
            // The site has been removed while a request was in flight.
            return false;
        }
    }

    public static int getInterval(Context ctx) {
        return prefs(ctx).getInt("interval_min", DEFAULT_INTERVAL_MIN);
    }

    public static void setInterval(Context ctx, int minutes) {
        prefs(ctx).edit().putInt("interval_min", minutes).apply();
    }

    public static boolean notificationsEnabled(Context ctx) {
        return prefs(ctx).getBoolean("notifications", false);
    }

    public static boolean httpEnabled(Context ctx) {
        return prefs(ctx).getBoolean("http_enabled", false);
    }

    public static void setHttpEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean("http_enabled", enabled).apply();
        if (!enabled) applyHttpPolicy(ctx);
    }

    public static void applyHttpPolicy(Context ctx) {
        synchronized (LOCK) {
            if (httpEnabled(ctx)) return;
            List<Site> sites = read(ctx);
            boolean changed = false;
            for (Site site : sites) {
                if (UrlPolicy.isHttp(site.url) && (!"BLOCKED".equals(site.state)
                        || !UrlPolicy.HTTP_DISABLED_MESSAGE.equals(site.details))) {
                    markHttpBlocked(site);
                    changed = true;
                }
            }
            if (changed) save(ctx, sites);
        }
    }

    private static void markHttpBlocked(Site site) {
        site.state = "BLOCKED";
        site.details = UrlPolicy.HTTP_DISABLED_MESSAGE;
        site.errorType = "POLICY";
        site.httpCode = -1;
        site.checkedAt = System.currentTimeMillis();
        site.latencyMs = 0;
    }

    public static void setNotificationsEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean("notifications", enabled).apply();
    }
}
