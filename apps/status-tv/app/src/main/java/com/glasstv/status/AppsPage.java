package com.glasstv.status;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Apps tab: which apps hold the TV's memory, largest first, read from the TV's own debugging shell
 * ({@link TvShell}). OK on an app you open from Home offers Restart (stop it and open it fresh), Close
 * (stop it) and App info. System services are listed for the full picture but cannot be stopped here:
 * Android restarts them itself, and stopping some would disturb the TV.
 */
final class AppsPage extends LinearLayout {
    private static final String HOME = "com.gathin.tvhome";
    /** A process this large on a 2 GB TV is worth a look. */
    private static final long BIG_KB = 250 * 1024;

    private final MainActivity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView summary, chosen, hint;
    private final LinearLayout list, actions;
    private final TextView restart, close, info;
    final TextView refresh;
    private boolean loading, ran;
    private App picked;

    /** One app (all its processes together). */
    private static final class App {
        String pkg, label;
        long kb;
        int processes;
        boolean launchable, system;
    }

    AppsPage(MainActivity a) {
        super(a);
        this.a = a;
        setOrientation(VERTICAL);

        summary = a.text("", 14, MainActivity.MUTE, 400);
        summary.setPadding(a.dp(4), 0, 0, a.dp(10));
        addView(summary);

        ScrollView scroll = new ScrollView(a);
        scroll.setBackground(a.glass());
        scroll.setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8));
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(a);
        list.setOrientation(VERTICAL);
        scroll.addView(list);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(262)));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, a.dp(14), 0, 0);
        refresh = button("Refresh");
        refresh.setOnClickListener(v -> load(true));
        bottom.addView(refresh);
        actions = new LinearLayout(a);
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        chosen = a.text("", 16, 0xFFFFFFFF, 500);
        chosen.setPadding(a.dp(20), 0, a.dp(14), 0);
        actions.addView(chosen);
        restart = button("Restart");
        close = button("Close");
        info = button("App info");
        for (TextView b : new TextView[]{restart, close, info}) {
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = a.dp(10);
            actions.addView(b, lp);
        }
        restart.setOnClickListener(v -> stop(true));
        close.setOnClickListener(v -> stop(false));
        info.setOnClickListener(v -> appInfo());
        actions.setVisibility(GONE);
        bottom.addView(actions);
        hint = a.text("", 14, MainActivity.MUTE, 400);
        hint.setPadding(a.dp(18), 0, 0, 0);
        bottom.addView(hint, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(bottom);
    }

    /** The first time the tab is shown: read the list (and, the very first time, the TV asks once). */
    void runOnce() {
        if (!ran) load(true);
    }

    private void load(boolean mayAsk) {
        if (loading) return;
        loading = ran = true;
        pick(null);
        summary.setText("Reading which apps use the memory…");
        hint.setText("If the TV asks \"Allow USB debugging?\", tick Always allow and choose Allow.");
        new Thread(() -> {
            String out = null, problem = null;
            try {
                out = TvShell.run(a, mayAsk, "ps -A -o RSS,USER,NAME");
            } catch (TvShell.NotAllowed e) {
                problem = "The TV did not allow TV Status to read the list. Press Refresh, then on the TV tick Always allow and choose Allow.";
            } catch (IOException e) {
                problem = "Needs the TV's debugging switched on: Settings → System → Developer options → USB debugging. "
                        + "Until then, App info is still here for each app.";
            } catch (Exception e) {
                problem = "Could not read the list: " + e.getMessage();
            }
            final List<App> apps = out == null ? launchableOnly() : parse(out);
            final String p = problem;
            ui.post(() -> {
                loading = false;
                show(apps, p);
            });
        }, "apps-list").start();
    }

    /** "RSS USER NAME" lines into apps, processes of one package added together, largest first. */
    private List<App> parse(String out) {
        PackageManager pm = a.getPackageManager();
        Map<String, App> byPkg = new HashMap<>();
        for (String line : out.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 3 || !f[0].matches("\\d+")) continue;
            long kb = Long.parseLong(f[0]);
            if (kb < 8 * 1024) continue;
            String name = f[2], pkg = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
            App app = byPkg.get(pkg);
            if (app == null) {
                app = describe(pm, pkg, !f[1].startsWith("u0_a"));
                byPkg.put(pkg, app);
            }
            app.kb += kb;
            app.processes++;
        }
        List<App> apps = new ArrayList<>(byPkg.values());
        apps.sort((x, y) -> Long.compare(y.kb, x.kb));
        return apps;
    }

    private App describe(PackageManager pm, String pkg, boolean systemUser) {
        App app = new App();
        app.pkg = pkg;
        app.label = pkg;
        app.system = systemUser;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            app.label = pm.getApplicationLabel(ai).toString();
            app.launchable = !pkg.equals(HOME) && !pkg.equals(a.getPackageName())
                    && pm.getLeanbackLaunchIntentForPackage(pkg) != null;
        } catch (Exception ignored) {
            // A native service (surfaceflinger…) or a shared process: its own name, or a plain one.
            if (pkg.equals("system") || pkg.equals("system_server")) app.label = "Android system";
            else if (pkg.startsWith("com.google.process.")) app.label = "Google services";
        }
        if (pkg.equals(HOME)) app.label = "Glass TV";
        return app;
    }

    /** Without the debugging shell: the apps on Home, with no figures, so App info still works. */
    private List<App> launchableOnly() {
        PackageManager pm = a.getPackageManager();
        List<App> apps = new ArrayList<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            App app = describe(pm, ri.activityInfo.packageName, false);
            app.kb = -1;
            apps.add(app);
        }
        apps.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
        return apps;
    }

    private void show(List<App> apps, String problem) {
        list.removeAllViews();
        long max = 1, total = 0;
        for (App app : apps) {
            max = Math.max(max, app.kb);
            total += Math.max(0, app.kb);
        }
        View first = null;
        for (App app : apps) {
            if (app.kb >= 0 && app.kb < 15 * 1024) continue;   // small helpers: noise in a list on the TV
            View row = row(app, max);
            if (first == null) first = row;
            list.addView(row);
        }
        if (problem != null) {
            summary.setText(problem);
            summary.setTextColor(MainActivity.YELLOW);
        } else {
            summary.setTextColor(MainActivity.MUTE);
            summary.setText(String.format(Locale.ROOT, "%d apps and services running  ·  OK on an app to restart or close it. "
                    + "Figures include memory shared with Android, so they add up to more than the TV has.", apps.size()));
        }
        hint.setText(problem == null ? "Big apps (over 250 MB) are marked yellow." : "");
        if (first != null) a.linkDown(first);
    }

    private View row(App app, long max) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(VERTICAL);
        r.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackground(rowBackground());

        LinearLayout line = new LinearLayout(a);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = a.text(app.label, 16, 0xFFFFFFFF, 500);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String kind = app.pkg.equals(HOME) ? "Home screen" : app.pkg.equals(a.getPackageName()) ? "This app"
                : app.launchable ? (app.processes > 1 ? app.processes + " processes" : "App") : "System service";
        TextView tag = a.text(kind, 13, MainActivity.MUTE, 500);
        tag.setPadding(a.dp(12), 0, a.dp(16), 0);
        line.addView(tag);
        boolean big = app.kb >= BIG_KB;
        TextView size = a.text(app.kb < 0 ? "—" : mb(app.kb), 16, big ? MainActivity.YELLOW : 0xE6FFFFFF, 600);
        size.setGravity(Gravity.END);
        line.addView(size, new LayoutParams(a.dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(line);

        if (app.kb > 0) {
            // A thin bar: this app's share against the largest one.
            View bar = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setColor(big ? MainActivity.YELLOW : 0x8064D2FF);
            g.setCornerRadius(a.dp(2));
            bar.setBackground(g);
            LinearLayout track = new LinearLayout(a);
            track.setWeightSum(1f);
            track.addView(bar, new LayoutParams(0, a.dp(3), Math.max(0.01f, app.kb / (float) max)));
            LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(3));
            tlp.topMargin = a.dp(5);
            r.addView(track, tlp);
        }
        r.setOnClickListener(v -> {
            pick(app);
            (app.launchable && app.kb >= 0 ? restart : info).requestFocus();
        });
        return r;
    }

    /** Shows the actions for {@code app} (or hides them for null). System services get App info only. */
    private void pick(App app) {
        picked = app;
        if (app == null) {
            actions.setVisibility(GONE);
            hint.setVisibility(VISIBLE);
            return;
        }
        chosen.setText(app.label);
        boolean canStop = app.launchable && app.kb >= 0;
        restart.setVisibility(canStop ? VISIBLE : GONE);
        close.setVisibility(canStop ? VISIBLE : GONE);
        info.setVisibility(app.pkg.contains(".") ? VISIBLE : GONE);
        actions.setVisibility(VISIBLE);
        hint.setVisibility(GONE);
    }

    /** Stops the picked app ({@code am force-stop}); Restart then opens it fresh. */
    private void stop(boolean reopen) {
        final App app = picked;
        if (app == null || !app.launchable) return;
        chosen.setText((reopen ? "Restarting " : "Closing ") + app.label + "…");
        new Thread(() -> {
            String problem = null;
            try {
                TvShell.run(a, false, "am force-stop " + app.pkg);
            } catch (Exception e) {
                problem = "Could not stop " + app.label + ". Try App info → Force stop.";
            }
            final String p = problem;
            ui.post(() -> {
                if (p != null) {
                    chosen.setText(p);
                    return;
                }
                if (reopen) {
                    Intent open = a.getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
                    if (open != null) {
                        a.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    }
                }
                // Focus first: hiding the buttons it sits on would otherwise send it up to a tab.
                refresh.requestFocus();
                load(false);
            });
        }, "apps-stop").start();
    }

    private void appInfo() {
        if (picked == null) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + picked.pkg)));
        } catch (Exception e) {
            chosen.setText("App info is not available for " + picked.label);
        }
    }

    private static String mb(long kb) {
        return kb >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f GB", kb / 1048576.0) : (kb / 1024) + " MB";
    }

    private TextView button(String label) {
        TextView b = a.text(label, 17, 0xFF000000, 600);
        b.setPadding(a.dp(22), a.dp(10), a.dp(22), a.dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(a.buttonBackground());
        return b;
    }

    private StateListDrawable rowBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x33FFFFFF);
        on.setCornerRadius(a.dp(12));
        on.setStroke(a.dp(2), 0xFF64D2FF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new GradientDrawable());
        return s;
    }
}
