package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;

/**
 * The azan at prayer time, while the TV is on. Each prayer can be switched on or off (Wallpaper menu →
 * Location, text &amp; ayat → Azan); Maghrib is on by default. One exact alarm is kept for the next prayer
 * that is switched on, worked out from the stored JAKIM timetable; Home sets it again each time it comes
 * to the front, and the alarm sets the one after. Nothing runs in between.
 *
 * The recording is the theme's azan.mp3 when there is one, else the call to prayer recorded at Masjid
 * KLCC (Internet Archive "AzanZhurMasjidKlcc", CC0), fetched once and kept.
 */
final class Azan {
    private static final String TAG = "TvHome.Azan";
    /** The prayers that can call the azan: indexes into PrayerTimes.NAMES (Syuruk is not a prayer). */
    static final int[] PRAYERS = {0, 2, 3, 4, 5};
    private static final int MAGHRIB = 4;
    private static final String SOURCE = "https://archive.org/download/AzanZhurMasjidKlcc/CallToPrayer.mp3";
    /** An alarm delivered this late (the TV was asleep) is let go rather than played out of time. */
    static final long LATE_MS = 2 * 60 * 1000L;

    private Azan() {
    }

    static boolean on(Context ctx, int prayer) {
        return prefs(ctx).getBoolean("on_" + prayer, prayer == MAGHRIB);
    }

    static void set(Context ctx, int prayer, boolean on) {
        prefs(ctx).edit().putBoolean("on_" + prayer, on).apply();
    }

    /** Sets the alarm for the next prayer that is on, today or tomorrow; clears it when none is. */
    static void schedule(Context ctx, String zone) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent old = pending(ctx, -1, 0, PendingIntent.FLAG_NO_CREATE);
        if (old != null) am.cancel(old);
        if (zone == null || zone.isEmpty()) return;
        prefs(ctx).edit().putString("zone", zone).apply();
        long now = System.currentTimeMillis();
        // An "azan-test" file next to the theme sets a Maghrib azan one minute from now, to try it from the
        // computer while another app is in front: tools/tv.py 'touch …/files/azan-test', then press Home.
        File dir = Config.dir(ctx);
        if (dir != null && new File(dir, "azan-test").delete()) {
            long when = now + 60_000;
            try {
                am.setExact(AlarmManager.RTC, when, pending(ctx, MAGHRIB, when, PendingIntent.FLAG_UPDATE_CURRENT));
                Log.i(TAG, "test azan in one minute");
            } catch (SecurityException e) {
                Log.w(TAG, "exact alarms not allowed: " + e.getMessage());
            }
            return;
        }
        for (int d = 0; d < 2; d++) {
            Calendar day = Calendar.getInstance();
            day.add(Calendar.DAY_OF_MONTH, d);
            PrayerTimes.Day times = PrayerTimes.day(ctx, zone, day);
            if (times == null) continue;
            for (int p : PRAYERS) {
                if (!on(ctx, p)) continue;
                Calendar at = (Calendar) day.clone();
                at.set(Calendar.HOUR_OF_DAY, times.minutes[p] / 60);
                at.set(Calendar.MINUTE, times.minutes[p] % 60);
                at.set(Calendar.SECOND, 0);
                at.set(Calendar.MILLISECOND, 0);
                long when = at.getTimeInMillis();
                if (when <= now + 5000) continue;
                try {
                    // RTC, not RTC_WAKEUP: a TV in standby stays asleep; the receiver drops a late alarm.
                    am.setExact(AlarmManager.RTC, when, pending(ctx, p, when, PendingIntent.FLAG_UPDATE_CURRENT));
                    Log.i(TAG, "next azan: " + PrayerTimes.NAMES[p] + " at " + at.getTime());
                } catch (SecurityException e) {
                    Log.w(TAG, "exact alarms not allowed: " + e.getMessage());
                }
                return;
            }
        }
    }

    private static PendingIntent pending(Context ctx, int prayer, long when, int flags) {
        Intent i = new Intent(ctx, AzanReceiver.class).putExtra("prayer", prayer).putExtra("at", when);
        return PendingIntent.getBroadcast(ctx, 0, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The recording to play: the theme's azan.mp3, else the KLCC one once fetched; null when neither is there. */
    static File recording(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir != null) {
            File theme = new File(dir, "azan.mp3");
            if (theme.isFile() && theme.length() > 0) return theme;
        }
        File kept = new File(ctx.getFilesDir(), "azan-klcc.mp3");
        return kept.isFile() && kept.length() > 0 ? kept : null;
    }

    /** Fetches the KLCC recording once (1.8 MB), so the first azan does not wait on the network. */
    static void fetch(Context ctx) {
        if (recording(ctx) != null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            File part = new File(app.getFilesDir(), "azan-klcc.mp3.part");
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(SOURCE).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[16 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                if (part.length() > 100_000 && part.renameTo(new File(app.getFilesDir(), "azan-klcc.mp3"))) {
                    Log.i(TAG, "azan recording kept");
                }
            } catch (Exception e) {
                Log.w(TAG, "azan recording not fetched: " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
                part.delete();
            }
        }, "azan-fetch").start();
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("azan", Context.MODE_PRIVATE);
    }
}
