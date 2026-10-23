package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

/**
 * Today's prayer times for a Malaysian zone (prayer_zone, e.g. MLK01), from JAKIM's e-Solat API.
 * The month's timetable is fetched once and kept in the app's files, so Home reads it from disk and
 * only goes online when the month in hand no longer has today in it.
 */
final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    private static final String API = "https://www.e-solat.gov.my/index.php?r=esolatApi/TakwimSolat&period=month&zone=";
    /** e-Solat writes the month in Malay: "22-Okt-2026". */
    private static final String[] MONTHS = {"jan", "feb", "mac", "apr", "mei", "jun", "jul", "ogos", "sep", "okt", "nov", "dis"};
    private static final String[] HIJRI_MONTHS = {"Muharam", "Safar", "Rabiulawal", "Rabiulakhir", "Jamadilawal",
            "Jamadilakhir", "Rejab", "Syaaban", "Ramadan", "Syawal", "Zulkaedah", "Zulhijah"};
    static final String[] NAMES = {"Subuh", "Syuruk", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};
    /** Index of Syuruk in NAMES: sunrise, shown but never the "next prayer". */
    static final int SYURUK = 1;

    /** One day: the six times as minutes after midnight, and the Hijri date for the header. */
    static final class Day {
        final int[] minutes;
        final String hijri;

        Day(int[] minutes, String hijri) {
            this.minutes = minutes;
            this.hijri = hijri;
        }
    }

    private PrayerTimes() {}

    private static File file(Context ctx, String zone) {
        return new File(ctx.getFilesDir(), "prayer-" + zone.toLowerCase(Locale.ROOT) + ".json");
    }

    /** The day from the stored timetable, or null when it is not there (yet). Reads disk only. */
    static Day day(Context ctx, String zone, Calendar date) {
        try {
            JSONObject json = read(file(ctx, zone));
            return json == null ? null : find(json, date);
        } catch (Exception e) {
            Log.w(TAG, "stored timetable unreadable: " + e.getMessage());
            return null;
        }
    }

    /** True when the stored timetable lacks today, so this month's should be fetched. */
    static boolean due(Context ctx, String zone) {
        return day(ctx, zone, Calendar.getInstance()) == null;
    }

    /** Fetches this month's timetable. Network; never on the UI thread. */
    static boolean refresh(Context ctx, String zone) {
        try {
            byte[] body = get(API + zone);
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray days = json.optJSONArray("prayerTime");
            if (days == null || days.length() == 0) throw new IllegalStateException("no prayerTime in reply");
            try (FileOutputStream out = new FileOutputStream(file(ctx, zone))) {
                out.write(body);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "e-Solat fetch failed: " + e.getMessage());
            return false;
        }
    }

    /** "11 Jamadilawal 1448" from e-Solat's "1448-05-11"; empty when it cannot be read. */
    static String hijriLabel(String hijri) {
        String[] p = hijri == null ? new String[0] : hijri.split("-");
        if (p.length != 3) return "";
        try {
            int m = Integer.parseInt(p[1]);
            return Integer.parseInt(p[2]) + " " + HIJRI_MONTHS[m - 1] + " " + p[0];
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Day find(JSONObject json, Calendar date) {
        JSONArray days = json.optJSONArray("prayerTime");
        if (days == null) return null;
        String want = String.format(Locale.ROOT, "%02d-%s-%04d", date.get(Calendar.DAY_OF_MONTH),
                MONTHS[date.get(Calendar.MONTH)], date.get(Calendar.YEAR));
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null || !want.equalsIgnoreCase(d.optString("date"))) continue;
            int[] minutes = new int[KEYS.length];
            for (int k = 0; k < KEYS.length; k++) {
                minutes[k] = minutes(d.optString(KEYS[k]));
                if (minutes[k] < 0) return null;
            }
            return new Day(minutes, d.optString("hijri"));
        }
        return null;
    }

    /** "05:46:00" to 346; -1 when it is not a time. */
    private static int minutes(String hms) {
        String[] p = hms.split(":");
        if (p.length < 2) return -1;
        try {
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static JSONObject read(File f) throws Exception {
        if (!f.isFile() || f.length() == 0 || f.length() > 256 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 256 * 1024) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
