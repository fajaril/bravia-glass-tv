package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * The daily ayat above the prayer times (weekly_ayah): one of 52 well-known short verses, a new one each
 * day in Malaysian time, with its Uthmani text and Abdullah Muhammad Basmeih's Malay translation from
 * Quran.com. Fetched once a day and kept, so Home shows it at once.
 */
final class WeeklyAyah {
    private static final String TAG = "TvHome.Ayah";
    private static final String API = "https://api.quran.com/api/v4/";
    /** Short enough for two lines of Arabic and a few of Malay; read in order, one a day. */
    static final String[] VERSES = {
            "2:152", "94:5", "13:28", "2:153", "20:25", "2:45", "3:8", "94:6", "3:134", "7:23",
            "2:201", "93:5", "3:31", "40:60", "20:114", "2:156", "14:7", "4:28", "18:10", "17:24",
            "6:162", "10:62", "11:115", "15:99", "16:128", "19:96", "20:46", "23:118", "33:41", "47:7",
            "49:10", "50:16", "51:56", "55:13", "73:9", "76:9", "87:14", "93:3", "94:8", "96:1",
            "103:2", "108:1", "110:3", "112:1", "2:155", "3:92", "28:24", "31:18", "35:34", "42:19",
            "67:2", "9:129"};
    /** Abdullah Muhammad Basmeih (Malay) and Saheeh International (English), as in the Quran app. */
    private static final int MALAY = 39, ENGLISH = 20;

    /** The day's verse: Arabic, both translations, and "Al-Baqarah 2:152". */
    static final class Ayah {
        final String arabic, malay, english, reference;

        Ayah(String arabic, String malay, String english, String reference) {
            this.arabic = arabic;
            this.malay = malay;
            this.english = english;
            this.reference = reference;
        }

        String translation(boolean inEnglish) {
            return inEnglish && !english.isEmpty() ? english : malay;
        }
    }

    /**
     * The owner's choice of translation, from the Wallpaper menu: Malay unless set to English. Its key is
     * not the English text's: one name for both, a boolean and a string, crashed Home on its first start.
     */
    static boolean english(Context ctx) {
        return prefs(ctx).getBoolean("show_english", false);
    }

    static void setEnglish(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("show_english", on).apply();
    }

    /** Whether the owner shows the ayat on Home (Wallpaper menu → Text & ayat); shown unless hidden there. */
    static boolean shown(Context ctx) {
        return !prefs(ctx).getBoolean("hidden", false);
    }

    static void setShown(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("hidden", !on).apply();
    }

    private WeeklyAyah() {}

    /** Days since 1970 in Malaysian time: the verse changes at midnight. */
    static int day() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        return (int) ((c.getTimeInMillis() + c.getTimeZone().getOffset(c.getTimeInMillis())) / 86_400_000L);
    }

    static String keyFor(int day) {
        return VERSES[Math.floorMod(day, VERSES.length)];
    }

    /** Today's verse from storage, or null when it has not been fetched yet. */
    static Ayah stored(Context ctx) {
        SharedPreferences p = prefs(ctx);
        // A verse kept before English was added lacks it: fetch again, once.
        if (!keyFor(day()).equals(p.getString("key", null)) || !p.contains("text_english")) return null;
        return read(p);
    }

    /** The last verse kept, whatever day it was for: shown while today's is on its way. */
    static Ayah last(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getString("arabic", null) == null ? null : read(p);
    }

    private static Ayah read(SharedPreferences p) {
        return new Ayah(p.getString("arabic", ""), p.getString("translation", ""), p.getString("text_english", ""),
                p.getString("reference", ""));
    }

    /** Fetches today's verse. Network; never on the UI thread. */
    static Ayah refresh(Context ctx) {
        String key = keyFor(day());
        try {
            JSONObject v = new JSONObject(get(API + "verses/by_key/" + key + "?fields=text_uthmani&translations="
                    + MALAY + "," + ENGLISH)).getJSONObject("verse");
            JSONArray trs = v.optJSONArray("translations");
            String malay = "", english = "";
            for (int i = 0; trs != null && i < trs.length(); i++) {
                JSONObject t = trs.getJSONObject(i);
                String text = t.optString("text").replaceAll("<sup[^>]*>.*?</sup>", "").replaceAll("<[^>]+>", "").trim();
                if (t.optInt("resource_id") == MALAY) malay = text;
                else if (t.optInt("resource_id") == ENGLISH) english = text;
            }
            String chapter = key.substring(0, key.indexOf(':'));
            String name = new JSONObject(get(API + "chapters/" + chapter + "?language=ms")).getJSONObject("chapter").optString("name_simple");
            Ayah a = new Ayah(v.optString("text_uthmani"), malay, english, name + " " + key);
            prefs(ctx).edit().putString("key", key).putString("arabic", a.arabic).putString("translation", a.malay)
                    .putString("text_english", a.english).putString("reference", a.reference).apply();
            return a;
        } catch (Exception e) {
            Log.w(TAG, "verse " + key + ": " + e.getMessage());
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "GlassTV/1.0 (Android TV home screen)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 256 * 1024) throw new IllegalStateException("response too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("weekly_ayah", Context.MODE_PRIVATE);
    }
}
