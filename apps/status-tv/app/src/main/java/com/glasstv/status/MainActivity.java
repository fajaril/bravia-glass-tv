package com.glasstv.status;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.util.List;
import java.util.Locale;

/**
 * The TV's memory, storage and network at a glance, refreshed every two seconds, with a button that
 * closes apps idling in the background to give memory back. Reads only what Android hands any app;
 * nothing goes online.
 */
public class MainActivity extends Activity {
    private static final int GREEN = 0xFF30D158, YELLOW = 0xFFFFD60A, RED = 0xFFFF453A, MUTE = 0xA6FFFFFF;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 2000);
        }
    };
    private float density;
    private Typeface outfit;
    private Ring ramRing, diskRing;
    private TextView ramBig, ramSub, diskBig, diskSub, freeNote, info, infoValues;
    private TextView freeButton;

    /** Left for another app or Home: close completely, so the TV has the memory back at once. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finishAndRemoveTask();
    }

    /** Closed for good (Back, or left above): end the process too, rather than leave it cached in memory. */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && !isChangingConfigurations()) android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;
        outfit = getResources().getFont(R.font.outfit);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(64), dp(40), dp(64), dp(28));
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2C3038, 0xFF0E0F12}));

        TextView title = text("TV Status", 34, 0xFFFFFFFF, 500);
        page.addView(title);
        TextView model = text(Build.MANUFACTURER.toUpperCase(Locale.ROOT) + " " + Build.MODEL + "  ·  ANDROID " + Build.VERSION.RELEASE,
                13, MUTE, 500);
        model.setLetterSpacing(0.16f);
        model.setPadding(0, dp(4), 0, dp(24));
        page.addView(model);

        LinearLayout gauges = new LinearLayout(this);
        gauges.setOrientation(LinearLayout.HORIZONTAL);
        ramRing = new Ring(this);
        diskRing = new Ring(this);
        ramBig = text("", 30, 0xFFFFFFFF, 500);
        ramSub = text("", 15, MUTE, 400);
        diskBig = text("", 30, 0xFFFFFFFF, 500);
        diskSub = text("", 15, MUTE, 400);
        gauges.addView(card("Memory (RAM)", ramRing, ramBig, ramSub), weight());
        View gap = new View(this);
        gauges.addView(gap, new LinearLayout.LayoutParams(dp(20), 1));
        gauges.addView(card("Storage", diskRing, diskBig, diskSub), weight());
        page.addView(gauges);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(22), 0, 0);
        freeButton = text("Free up memory", 18, 0xFF000000, 600);
        freeButton.setPadding(dp(26), dp(12), dp(26), dp(12));
        freeButton.setFocusable(true);
        freeButton.setClickable(true);
        freeButton.setBackground(buttonBackground());
        freeButton.setOnClickListener(v -> freeMemory());
        row.addView(freeButton);
        freeNote = text("Closes apps idling in the background. Nothing is deleted; they open again as normal.", 14, MUTE, 400);
        freeNote.setPadding(dp(18), 0, 0, 0);
        row.addView(freeNote, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(row);

        // Two columns, so the values line up: the names in grey, the values in white.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(dp(22), dp(16), dp(22), dp(16));
        box.setBackground(glass());
        info = text("", 15, MUTE, 500);
        info.setLineSpacing(dp(6), 1f);
        infoValues = text("", 15, 0xE6FFFFFF, 400);
        infoValues.setLineSpacing(dp(6), 1f);
        box.addView(info, new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(infoValues);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(22);
        page.addView(box, ilp);

        setContentView(page);
        freeButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void refresh() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        float ramPct = mi.totalMem > 0 ? used / (float) mi.totalMem : 0;
        ramRing.set(ramPct, colourFor(ramPct, 0.75f, 0.88f));
        ramBig.setText(Math.round(ramPct * 100) + "% used");
        ramSub.setText(size(used) + " of " + size(mi.totalMem) + "\n" + size(mi.availMem) + " free"
                + (mi.lowMemory ? "  ·  running low" : ""));

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
        float diskPct = total > 0 ? (total - free) / (float) total : 0;
        diskRing.set(diskPct, colourFor(diskPct, 0.8f, 0.92f));
        diskBig.setText(Math.round(diskPct * 100) + "% used");
        diskSub.setText(size(total - free) + " of " + size(total) + "\n" + size(free) + " free");

        long up = SystemClock.elapsedRealtime() / 60000;
        String uptime = up >= 1440 ? (up / 1440) + " d " + (up % 1440 / 60) + " h" : (up / 60) + " h " + (up % 60) + " min";
        info.setText("Network\nOn for\nProcessor\nAndroid");
        infoValues.setText(network()
                + "\n" + uptime + " since the last restart"
                + "\n" + Runtime.getRuntime().availableProcessors() + " cores  ·  " + Build.HARDWARE
                + "\n" + Build.VERSION.RELEASE + "  ·  security patch " + Build.VERSION.SECURITY_PATCH);
    }

    /** Ethernet or Wi-Fi, and the TV's address on the home network. */
    private String network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "Offline";
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            String kind = caps == null ? "Connected" : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : "Connected";
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp != null) {
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) return kind + "  ·  " + a.getAddress().getHostAddress();
                }
            }
            return kind;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /** Closes every app idling in the background (not this one, not Home), then says how much came back. */
    private void freeMemory() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(probe, 0);
        int closed = 0;
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals("com.gathin.tvhome")) continue;
            am.killBackgroundProcesses(pkg);
            closed++;
        }
        final int n = closed;
        freeNote.setText("Freeing memory…");
        handler.postDelayed(() -> {
            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);
            long gained = Math.max(0, after.availMem - before.availMem);
            freeNote.setText("Done: " + size(gained) + " freed, " + size(after.availMem) + " free now. Checked " + n + " apps.");
            refresh();
        }, 1500);
    }

    private static int colourFor(float pct, float warn, float alarm) {
        return pct >= alarm ? RED : pct >= warn ? YELLOW : GREEN;
    }

    private static String size(long bytes) {
        double gb = bytes / 1073741824.0;
        return gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb) : String.format(Locale.ROOT, "%d MB", Math.round(bytes / 1048576.0));
    }

    /** A glass card: its name, the ring and the figures beside it. */
    private View card(String name, Ring ring, TextView big, TextView sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(24), dp(22), dp(24), dp(22));
        c.setBackground(glass());
        c.addView(ring, new LinearLayout.LayoutParams(dp(120), dp(120)));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(24), 0, 0, 0);
        TextView label = text(name.toUpperCase(Locale.ROOT), 13, MUTE, 500);
        label.setLetterSpacing(0.16f);
        t.addView(label);
        big.setPadding(0, dp(6), 0, dp(4));
        t.addView(big);
        sub.setLineSpacing(dp(4), 1f);
        t.addView(sub);
        c.addView(t);
        return c;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private GradientDrawable glass() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x1AFFFFFF);
        g.setCornerRadius(dp(18));
        g.setStroke(Math.max(1, dp(1)), 0x2EFFFFFF);
        return g;
    }

    private android.graphics.drawable.StateListDrawable buttonBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(dp(24));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0xCCFFFFFF);
        off.setCornerRadius(dp(24));
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private TextView text(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(outfit);
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** A ring gauge: a faint full circle and a coloured arc for the share used. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float pct;

        Ring(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            for (Paint p : new Paint[]{track, arc}) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(14 * d);
                p.setStrokeCap(Paint.Cap.ROUND);
            }
            track.setColor(0x24FFFFFF);
        }

        void set(float pct, int colour) {
            this.pct = pct;
            arc.setColor(colour);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = track.getStrokeWidth() / 2;
            box.set(s, s, getWidth() - s, getHeight() - s);
            c.drawArc(box, 0, 360, false, track);
            c.drawArc(box, -90, 360 * Math.max(0.01f, Math.min(1, pct)), false, arc);
        }
    }
}
