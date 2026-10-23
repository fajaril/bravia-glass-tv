package com.glasstv.doa;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Doa harian from Hisnul Muslim (Fortress of the Muslim, hisnmuslim.com): the everyday chapters,
 * grouped as doa harian are taught in Malaysia, each doa in Arabic with its English translation, how
 * many times to say it, and its recitation. A chapter is fetched once and kept, so it reads offline
 * afterwards; the recitation streams.
 *
 * Remote: Up/Down move, OK opens a chapter or plays the selected doa (OK again pauses), Back returns.
 */
public class MainActivity extends Activity {
    private static final String API = "https://www.hisnmuslim.com/api/en/";
    private static final int TEAL = 0xFF5FE0D0, GOLD = 0xFFE6C27A;

    /** The groups, in the order a day runs, each with its Hisnul Muslim chapter numbers. */
    private static final String[] GROUPS = {"Pagi & Petang", "Tidur & Bangun", "Tandas & Wudhu", "Rumah & Masjid",
            "Pakaian", "Makan & Minum", "Selepas Solat", "Perjalanan", "Hujan & Cuaca", "Kesusahan & Dugaan",
            "Sakit & Kesihatan", "Taubat & Zikir"};
    private static final int[][] CHAPTERS = {{27}, {28, 1, 29, 30, 31}, {6, 7, 8, 9}, {10, 11, 12, 13, 14, 15},
            {2, 3, 5}, {69, 70, 68, 71, 72}, {25, 24, 26}, {95, 96, 97, 98, 105}, {61, 62, 64, 65},
            {34, 35, 43, 46, 53, 41}, {49, 124, 126}, {129, 131, 85, 82, 77}};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private Typeface outfit, amiri;
    private SharedPreferences prefs;
    private FrameLayout root;
    private View listPage, readPage;
    private ListView chapterList, doaList;
    private TextView today, readTitle, readStatus;

    /** Rows of the chapter list: a group heading (id < 0) or a chapter (its number and English title). */
    private final List<Object[]> rows = new ArrayList<>();
    private final java.util.Map<Integer, String> titles = new java.util.HashMap<>();
    private int chapter;
    /** The open chapter's doa: Arabic, English, repeat count, audio URL. */
    private final List<String[]> doas = new ArrayList<>();
    private MediaPlayer player;
    private int playing = -1;
    private boolean paused;

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finishAndRemoveTask();
    }

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
        amiri = getResources().getFont(R.font.amiri_quran);
        prefs = getSharedPreferences("doa", MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF12524C, 0xFF051F1D}));
        buildList();
        buildReader();
        setContentView(root);
        showList();
        loadTitles();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudio();
    }

    // ---- chapter list ----------------------------------------------------------------------------

    private void buildList() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(dp(56), dp(34), dp(56), dp(24));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        TextView arabic = text("حصن المسلم", 44, GOLD, amiri, 400);
        arabic.setGravity(Gravity.LEFT);
        side.addView(arabic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        side.addView(text("Doa Harian", 34, 0xFFFFFFFF, outfit, 300));
        TextView about = text("From Hisnul Muslim (Fortress of the Muslim)\nby Sa'id bin Wahf al-Qahtani\nArabic · English · recitation", 14, 0xB3FFFFFF, outfit, 400);
        about.setSingleLine(false);
        about.setLineSpacing(dp(4), 1f);
        about.setPadding(0, dp(12), 0, dp(22));
        side.addView(about);
        today = text("", 16, 0xFF000000, outfit, 600);
        today.setSingleLine(false);
        today.setPadding(dp(18), dp(12), dp(18), dp(12));
        today.setFocusable(true);
        today.setBackground(pill());
        int day = (int) ((Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur")).getTimeInMillis() + 8 * 3600_000L) / 86_400_000L);
        final int[] pick = pickOfDay(day);
        today.setOnClickListener(v -> openChapter(pick[0], pick[1]));
        side.addView(today, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        side.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        side.addView(text("Source: hisnmuslim.com", 11, 0x80FFFFFF, outfit, 400));
        page.addView(side, new LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT));

        for (int g = 0; g < GROUPS.length; g++) {
            rows.add(new Object[]{-1, GROUPS[g]});
            for (int c : CHAPTERS[g]) rows.add(new Object[]{c, ""});
        }
        chapterList = list();
        chapterList.setAdapter(new ChapterAdapter());
        chapterList.setOnItemClickListener((p, v, pos, id) -> {
            int c = (int) rows.get(pos)[0];
            if (c > 0) openChapter(c, 0);
        });
        page.addView(chapterList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        listPage = page;
        root.addView(page);
    }

    /** The doa of the day: a chapter from the everyday groups, and one doa in it, changing at midnight. */
    private static int[] pickOfDay(int day) {
        List<Integer> all = new ArrayList<>();
        for (int[] g : CHAPTERS) for (int c : g) if (c != 27) all.add(c);   // morning and evening is a long set of its own
        return new int[]{all.get(Math.floorMod(day, all.size())), 0};
    }

    private void loadTitles() {
        new Thread(() -> {
            try {
                JSONArray all = new JSONObject(cached("index.json", API + "husn_en.json")).getJSONArray("English");
                for (int i = 0; i < all.length(); i++) {
                    JSONObject c = all.getJSONObject(i);
                    titles.put(c.getInt("ID"), tidy(c.optString("TITLE")));
                }
                handler.post(() -> {
                    ((BaseAdapter) chapterList.getAdapter()).notifyDataSetChanged();
                    int day = (int) ((System.currentTimeMillis() + 8 * 3600_000L) / 86_400_000L);
                    String t = titles.get(pickOfDay(day)[0]);
                    today.setText("▶  Doa of the day\n" + (t != null ? t : ""));
                    selectFirstChapter();
                });
            } catch (Exception e) {
                handler.post(() -> today.setText("Can't reach hisnmuslim.com. Check the internet connection."));
            }
        }, "titles").start();
    }

    private void selectFirstChapter() {
        for (int i = 0; i < rows.size(); i++) {
            if ((int) rows.get(i)[0] > 0) {
                chapterList.setSelection(i);
                break;
            }
        }
        chapterList.requestFocus();
    }

    /** "supplications for when you wake up" as "Supplications for when you wake up", and the site's stray spaces gone. */
    private static String tidy(String s) {
        s = s.trim().replaceAll("\\s+", " ");
        if (s.startsWith("vocation")) s = "In" + s;
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private class ChapterAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int p) { return (int) rows.get(p)[0] > 0; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            int c = (int) rows.get(p)[0];
            if (c < 0) {
                TextView h = text(((String) rows.get(p)[1]).toUpperCase(), 13, TEAL, outfit, 600);
                h.setLetterSpacing(0.18f);
                h.setPadding(dp(16), dp(p == 0 ? 2 : 18), dp(16), dp(4));
                return h;
            }
            TextView t = text(titles.containsKey(c) ? titles.get(c) : "Chapter " + c, 19, 0xFFFFFFFF, outfit, 400);
            t.setPadding(dp(16), dp(11), dp(16), dp(11));
            return t;
        }
    }

    // ---- reading ---------------------------------------------------------------------------------

    private void buildReader() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(26), dp(56), dp(16));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        readTitle = text("", 26, 0xFFFFFFFF, outfit, 400);
        head.addView(readTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        readStatus = text("", 14, GOLD, outfit, 500);
        readStatus.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x24FFFFFF);
        chip.setCornerRadius(dp(18));
        readStatus.setBackground(chip);
        head.addView(readStatus);
        page.addView(head);
        doaList = list();
        doaList.setOnItemClickListener((p, v, pos, id) -> onDoaClick(pos));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        page.addView(doaList, lp);
        TextView hint = text("OK: play this doa / pause   ·   Back: chapters   ·   Translation: Hisnul Muslim (English)",
                11, 0x80FFFFFF, outfit, 400);
        hint.setPadding(0, dp(8), 0, 0);
        page.addView(hint);
        readPage = page;
        root.addView(page);
    }

    private void openChapter(int number, int at) {
        stopAudio();
        chapter = number;
        doas.clear();
        readTitle.setText(titles.containsKey(number) ? titles.get(number) : "Chapter " + number);
        readStatus.setText("Loading…");
        doaList.setAdapter(new DoaAdapter());
        listPage.setVisibility(View.GONE);
        readPage.setVisibility(View.VISIBLE);
        doaList.requestFocus();
        new Thread(() -> {
            try {
                JSONObject d = new JSONObject(cached("chapter_" + number + ".json", API + number + ".json"));
                JSONArray items = d.getJSONArray(d.keys().next());
                List<String[]> got = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject e = items.getJSONObject(i);
                    got.add(new String[]{e.optString("ARABIC_TEXT").trim(), e.optString("TRANSLATED_TEXT").trim(),
                            e.optString("REPEAT", "1").trim(), e.optString("AUDIO").replace("http://", "https://")});
                }
                handler.post(() -> {
                    if (number != chapter) return;
                    doas.addAll(got);
                    ((BaseAdapter) doaList.getAdapter()).notifyDataSetChanged();
                    doaList.setSelection(Math.min(at, Math.max(0, doas.size() - 1)));
                    doaList.requestFocus();
                    showStatus();
                });
            } catch (Exception e) {
                Log.w("Doa", "chapter " + number + ": " + e);
                handler.post(() -> readStatus.setText("Can't reach hisnmuslim.com"));
            }
        }, "chapter").start();
    }

    private class DoaAdapter extends BaseAdapter {
        @Override public int getCount() { return doas.size(); }
        @Override public Object getItem(int p) { return doas.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            String[] d = doas.get(p);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(22), dp(12), dp(22), dp(14));
            if (p == playing) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x26E6C27A);
                g.setCornerRadius(dp(14));
                row.setBackground(g);
            }
            TextView ar = text(d[0], 28, p == playing ? GOLD : 0xFFFFFFFF, amiri, 400);
            ar.setSingleLine(false);
            ar.setTextDirection(View.TEXT_DIRECTION_RTL);
            ar.setGravity(Gravity.RIGHT);
            ar.setLineSpacing(dp(6), 1f);
            row.addView(ar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView en = text(d[1], 15, 0xD9FFFFFF, outfit, 400);
            en.setSingleLine(false);
            en.setLineSpacing(dp(3), 1f);
            en.setPadding(0, dp(8), 0, 0);
            row.addView(en, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            String n = d[2];
            if (!n.isEmpty() && !n.equals("1") && !n.equals("0")) {
                TextView rep = text("Recite " + n + "×", 13, TEAL, outfit, 600);
                rep.setPadding(0, dp(6), 0, 0);
                row.addView(rep);
            }
            return row;
        }
    }

    private void onDoaClick(int pos) {
        if (playing == pos && player != null) {
            if (paused) player.start();
            else player.pause();
            paused = !paused;
            showStatus();
            return;
        }
        play(pos);
    }

    private void play(int pos) {
        stopAudio();
        if (pos < 0 || pos >= doas.size() || doas.get(pos)[3].isEmpty()) return;
        playing = pos;
        paused = false;
        ((BaseAdapter) doaList.getAdapter()).notifyDataSetChanged();
        doaList.setSelection(pos);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        try {
            player.setDataSource(doas.get(pos)[3]);
        } catch (Exception e) {
            readStatus.setText("Can't play this doa");
            return;
        }
        final MediaPlayer mp = player;
        player.setOnPreparedListener(m -> {
            if (m == player) m.start();
        });
        player.setOnCompletionListener(m -> {
            if (m == player) stopAudio();
        });
        player.setOnErrorListener((m, what, extra) -> {
            if (m == mp) readStatus.setText("Can't reach the recitation");
            return true;
        });
        player.prepareAsync();
        showStatus();
    }

    private void stopAudio() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playing >= 0) {
            playing = -1;
            if (doaList != null && doaList.getAdapter() != null) ((BaseAdapter) doaList.getAdapter()).notifyDataSetChanged();
        }
        paused = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showStatus();
    }

    private void showStatus() {
        if (readStatus == null) return;
        if (playing >= 0) readStatus.setText(paused ? "❚❚  Paused" : "▶  Playing " + (playing + 1) + " of " + doas.size());
        else readStatus.setText(doas.size() + (doas.size() == 1 ? " doa" : " doa"));
    }

    // ---- pages and keys --------------------------------------------------------------------------

    private void showList() {
        readPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        for (int i = 0; i < rows.size(); i++) if ((int) rows.get(i)[0] == chapter) chapterList.setSelection(i);
        chapterList.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (readPage.getVisibility() == View.VISIBLE && (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            onDoaClick(playing >= 0 ? playing : Math.max(0, doaList.getSelectedItemPosition()));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (readPage.getVisibility() == View.VISIBLE) {
            stopAudio();
            showList();
        } else {
            super.onBackPressed();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private ListView list() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setDividerHeight(dp(4));
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x33FFFFFF);
        on.setCornerRadius(dp(14));
        on.setStroke(dp(2), 0xCCFFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        l.setSelector(s);
        l.setVerticalScrollBarEnabled(false);
        l.setCacheColorHint(0);
        l.setFocusable(true);
        return l;
    }

    private StateListDrawable pill() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(dp(22));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0xCCFFFFFF);
        off.setCornerRadius(dp(22));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        return s;
    }

    private TextView text(String s, float sp, int color, Typeface face, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        if (face == outfit) t.setFontVariationSettings("'wght' " + weight);
        t.setSingleLine(true);
        if (face != amiri) t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        t.setIncludeFontPadding(face == amiri);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** A hisnmuslim.com answer kept in the app's files: fetched once, then read from storage. */
    private String cached(String name, String url) throws Exception {
        File f = new File(getFilesDir(), name);
        if (f.isFile() && f.length() > 0) return read(f);
        String body = get(url);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return body;
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "GlassTV-Doa/1.0 (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 2 * 1024 * 1024) throw new IllegalStateException("response too large");
                }
                String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return s.startsWith("﻿") ? s.substring(1) : s;   // the site's files start with a BOM
            }
        } finally {
            c.disconnect();
        }
    }
}
