package com.glasstv.mathurat;

import android.app.Activity;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Al-Ma'thurat (Wazifah Sughra) for the morning and the evening. Nothing here is typed by hand: the
 * verses come from Quran.com (Uthmani text, Basmeih's Malay, Saheeh International's English, Alafasy's
 * recitation) and the adhkar from Hisnul Muslim (hisnmuslim.com: Arabic, English, how many times,
 * recitation). For the evening, the phrases Hisnul Muslim says to change are shown under the morning text.
 *
 * Remote: Up/Down move, OK recites from the selected part on to the end (OK again pauses),
 * Left/Right switch Bahasa Melayu / English for the verses, Back returns to the choice of Pagi / Petang.
 */
public class MainActivity extends Activity {
    private static final String QURAN = "https://api.quran.com/api/v4/";
    private static final String HISN = "https://www.hisnmuslim.com/api/en/";
    private static final String AUDIO = "https://verses.quran.com/";
    private static final int MALAY = 39, ENGLISH = 20, RECITER = 7;
    private static final int GOLD = 0xFFE6C27A, AMBER = 0xFFF2A65A;

    /** The verses: surah, first and last ayah, times, and a name. */
    private static final Object[][] VERSES = {
            {1, 1, 7, 1, "Al-Fatihah"}, {2, 1, 5, 1, "Al-Baqarah 1–5"}, {2, 255, 257, 1, "Al-Baqarah 255–257 (Ayat al-Kursi)"},
            {2, 284, 286, 1, "Al-Baqarah 284–286"}, {112, 1, 4, 3, "Al-Ikhlas"}, {113, 1, 5, 3, "Al-Falaq"}, {114, 1, 6, 3, "An-Nas"}};
    /** The adhkar: Hisnul Muslim chapter, the item's ID in it, and the times when it differs from the item's own count. */
    private static final int[][] ADHKAR = {{27, 77, 0}, {27, 90, 0}, {27, 81, 0}, {27, 82, 0}, {27, 83, 7}, {27, 86, 0},
            {27, 97, 0}, {27, 87, 0}, {34, 121, 0}, {27, 79, 0}, {27, 94, 0}, {27, 98, 0}, {27, 92, 0}, {85, 196, 0}};
    private static final Pattern EVENING = Pattern.compile("\\[\\s*وإذا أمسى قال:?\\s*([^\\]]+)\\]");

    /** A part of the wird: its name, times, then its lines of {Arabic, Malay, English, audio URL}. */
    static final class Part {
        final String name;
        final int times;
        final boolean quran;
        final List<String[]> lines = new ArrayList<>();
        String note = "";

        Part(String name, int times, boolean quran) {
            this.name = name;
            this.times = times;
            this.quran = quran;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private Typeface outfit, amiri;
    private FrameLayout root;
    private View choicePage, readPage;
    private ListView partList;
    private TextView readTitle, readStatus, pagiButton, petangButton;
    private final List<Part> parts = new ArrayList<>();
    /** The list's rows: each line of each part, {part, line}; a part's name sits on its first line. */
    private final List<int[]> rows = new ArrayList<>();
    private boolean evening;
    private boolean english;
    private MediaPlayer player;
    /** What plays: the part, and the line within it. */
    private int playPart = -1, playLine;
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
        english = getSharedPreferences("mathurat", MODE_PRIVATE).getBoolean("english", false);
        root = new FrameLayout(this);
        buildChoice();
        buildReader();
        setContentView(root);
        showChoice();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudio();
    }

    // ---- Pagi or Petang --------------------------------------------------------------------------

    private void buildChoice() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A2A5C, 0xFF140E24}));
        TextView ar = text("المأثورات", 64, GOLD, amiri, 400);
        ar.setGravity(Gravity.CENTER);
        page.addView(ar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(text("Al-Ma'thurat", 40, 0xFFFFFFFF, outfit, 300), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = text("Wazifah Sughra · Imam Hasan al-Banna", 15, 0xB3FFFFFF, outfit, 400);
        sub.setPadding(0, dp(6), 0, dp(34));
        page.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        pagiButton = choice("Pagi", "After Subuh");
        petangButton = choice("Petang", "After Asar");
        pagiButton.setOnClickListener(v -> open(false));
        petangButton.setOnClickListener(v -> open(true));
        buttons.addView(pagiButton);
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(26), 1));
        buttons.addView(petangButton);
        page.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView src = text("Verses: Quran.com (Basmeih, Saheeh International, Alafasy)   ·   Adhkar: Hisnul Muslim (hisnmuslim.com)",
                12, 0x80FFFFFF, outfit, 400);
        src.setPadding(0, dp(40), 0, 0);
        page.addView(src, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        choicePage = page;
        root.addView(page);
    }

    private TextView choice(String title, String when) {
        TextView t = text(title + "\n" + when, 26, 0xFFFFFFFF, outfit, 500);
        t.setSingleLine(false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(46), dp(22), dp(46), dp(22));
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable on = new GradientDrawable();
        on.setColor(0xFFFFFFFF);
        on.setCornerRadius(dp(26));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x33FFFFFF);
        off.setCornerRadius(dp(26));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, off);
        t.setBackground(s);
        t.setOnFocusChangeListener((v, has) -> ((TextView) v).setTextColor(has ? 0xFF000000 : 0xFFFFFFFF));
        return t;
    }

    private void showChoice() {
        stopAudio();
        readPage.setVisibility(View.GONE);
        choicePage.setVisibility(View.VISIBLE);
        // Before Asar it is the morning wird, from Asar the evening one (roughly 4 pm in Malaysia).
        int hour = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur")).get(Calendar.HOUR_OF_DAY);
        (hour >= 4 && hour < 16 ? pagiButton : petangButton).requestFocus();
    }

    // ---- the wird --------------------------------------------------------------------------------

    private void buildReader() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(26), dp(56), dp(16));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        readTitle = text("", 28, 0xFFFFFFFF, outfit, 400);
        head.addView(readTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        readStatus = text("", 14, GOLD, outfit, 500);
        readStatus.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x24FFFFFF);
        chip.setCornerRadius(dp(18));
        readStatus.setBackground(chip);
        head.addView(readStatus);
        page.addView(head);
        partList = new ListView(this);
        partList.setDivider(null);
        partList.setDividerHeight(dp(6));
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x26FFFFFF);
        on.setCornerRadius(dp(14));
        on.setStroke(dp(2), 0xCCFFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        partList.setSelector(s);
        partList.setVerticalScrollBarEnabled(false);
        partList.setOnItemClickListener((p, v, pos, id) -> onPartClick(pos));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        page.addView(partList, lp);
        TextView hint = text("OK: recite from here / pause   ·   ◀ ▶: Melayu / English (verses)   ·   Back: Pagi / Petang",
                11, 0x80FFFFFF, outfit, 400);
        hint.setPadding(0, dp(8), 0, 0);
        page.addView(hint);
        readPage = page;
        root.addView(page);
    }

    private void open(boolean eve) {
        evening = eve;
        readPage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                eve ? new int[]{0xFF2A2350, 0xFF0B0A1C} : new int[]{0xFF7A4A2A, 0xFF241208}));
        readTitle.setText(eve ? "Al-Ma'thurat · Petang" : "Al-Ma'thurat · Pagi");
        readStatus.setText("Loading…");
        choicePage.setVisibility(View.GONE);
        readPage.setVisibility(View.VISIBLE);
        partList.setAdapter(new PartAdapter());
        partList.requestFocus();
        new Thread(() -> {
            try {
                List<Part> got = load(eve);
                handler.post(() -> {
                    parts.clear();
                    parts.addAll(got);
                    rows.clear();
                    for (int i = 0; i < parts.size(); i++) for (int l = 0; l < parts.get(i).lines.size(); l++) rows.add(new int[]{i, l});
                    ((BaseAdapter) partList.getAdapter()).notifyDataSetChanged();
                    partList.setSelection(0);
                    partList.requestFocus();
                    showStatus();
                });
            } catch (Exception e) {
                Log.w("Mathurat", "load: " + e);
                handler.post(() -> readStatus.setText("Can't reach Quran.com / hisnmuslim.com"));
            }
        }, "load").start();
    }

    private List<Part> load(boolean eve) throws Exception {
        List<Part> out = new ArrayList<>();
        for (Object[] v : VERSES) {
            int surah = (int) v[0], from = (int) v[1], to = (int) v[2];
            Part p = new Part((String) v[4], (int) v[3], true);
            JSONArray verses = new JSONObject(cached("q_" + surah + "_" + from + ".json", QURAN + "verses/by_chapter/" + surah
                    + "?fields=text_uthmani&per_page=50&page=" + ((from - 1) / 50 + 1) + "&translations=" + MALAY + "," + ENGLISH
                    + "&audio=" + RECITER)).getJSONArray("verses");
            for (int i = 0; i < verses.length(); i++) {
                JSONObject x = verses.getJSONObject(i);
                int n = x.getInt("verse_number");
                if (n < from || n > to) continue;
                String ms = "", en = "";
                JSONArray trs = x.optJSONArray("translations");
                for (int t = 0; trs != null && t < trs.length(); t++) {
                    JSONObject tr = trs.getJSONObject(t);
                    String clean = tr.optString("text").replaceAll("<sup[^>]*>.*?</sup>", "").replaceAll("<[^>]+>", "").trim();
                    if (tr.optInt("resource_id") == MALAY) ms = clean;
                    else en = clean;
                }
                JSONObject audio = x.optJSONObject("audio");
                p.lines.add(new String[]{x.optString("text_uthmani") + "  ﴿" + arabicDigits(n) + "﴾", ms, en,
                        audio != null ? AUDIO + audio.optString("url") : ""});
            }
            out.add(p);
        }
        java.util.Map<Integer, JSONArray> chapters = new java.util.HashMap<>();
        for (int[] a : ADHKAR) {
            if (!chapters.containsKey(a[0])) {
                JSONObject d = new JSONObject(cached("h_" + a[0] + ".json", HISN + a[0] + ".json"));
                chapters.put(a[0], d.getJSONArray(d.keys().next()));
            }
            JSONArray items = chapters.get(a[0]);
            for (int i = 0; i < items.length(); i++) {
                JSONObject e = items.getJSONObject(i);
                if (e.getInt("ID") != a[1]) continue;
                int times = a[2] > 0 ? a[2] : Math.max(1, e.optInt("REPEAT", 1));
                String arabic = e.optString("ARABIC_TEXT").trim(), english = e.optString("TRANSLATED_TEXT").trim();
                // Hisnul Muslim gives only the phrases that change in the evening (often cut short with
                // "..."), so the morning text stays and those phrases go in the note.
                StringBuilder eveText = new StringBuilder();
                Matcher m = EVENING.matcher(arabic);
                while (m.find()) {
                    if (eveText.length() > 0) eveText.append("  ·  ");
                    eveText.append(m.group(1).trim().replaceAll("[.。]+$", "").trim()).append(" …");
                }
                String morning = EVENING.matcher(arabic).replaceAll("").trim();
                Part p = new Part("Zikir " + (out.size() - VERSES.length + 1), times, false);
                boolean changes = eve && (eveText.length() > 0 || arabic.contains("أَصْبَح"));
                // The recitation is the morning wording: in the evening it would not match what is said.
                p.lines.add(new String[]{morning, "", english, changes ? "" : e.optString("AUDIO").replace("http://", "https://")});
                if (changes) p.note = eveText.length() > 0 ? "Petang: " + eveText
                        : "Petang: say أَمْسَيْنَا / أَمْسَى in place of أَصْبَحْنَا / أَصْبَحَ";
                out.add(p);
            }
        }
        return out;
    }

    private class PartAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int r) { return rows.get(r); }
        @Override public long getItemId(int r) { return r; }

        @Override
        public View getView(int r, View convert, ViewGroup parent) {
            int pos = rows.get(r)[0], i = rows.get(r)[1];
            Part p = parts.get(pos);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(22), dp(i == 0 ? 14 : 6), dp(22), dp(10));
            boolean lit = pos == playPart && i == playLine;
            if (lit) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x26E6C27A);
                g.setCornerRadius(dp(14));
                row.setBackground(g);
            }
            if (i == 0) {
                TextView name = text(p.name.toUpperCase() + (p.times > 1 ? "   ·   " + p.times + "×" : ""), 13, AMBER, outfit, 600);
                name.setLetterSpacing(0.14f);
                row.addView(name);
            }
            String[] l = p.lines.get(i);
            TextView ar = text(l[0], 26, lit ? GOLD : 0xFFFFFFFF, amiri, 400);
            ar.setSingleLine(false);
            ar.setTextDirection(View.TEXT_DIRECTION_RTL);
            ar.setGravity(Gravity.RIGHT);
            ar.setLineSpacing(dp(4), 1f);
            ar.setPadding(0, dp(4), 0, 0);
            row.addView(ar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            String tr = p.quran && !english ? l[1] : l[2];
            if (!tr.isEmpty()) {
                TextView t = text(tr, 14, 0xD9FFFFFF, outfit, 400);
                t.setSingleLine(false);
                t.setLineSpacing(dp(3), 1f);
                t.setPadding(0, dp(4), 0, 0);
                row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (i == p.lines.size() - 1 && !p.note.isEmpty()) {
                TextView n = text(p.note, 13, GOLD, outfit, 500);
                n.setSingleLine(false);
                n.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                n.setPadding(0, dp(6), 0, 0);
                row.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return row;
        }
    }

    // ---- recitation ------------------------------------------------------------------------------

    private void onPartClick(int r) {
        if (r < 0 || r >= rows.size()) return;
        int pos = rows.get(r)[0], line = rows.get(r)[1];
        if (playPart == pos && playLine == line && player != null) {
            if (paused) player.start();
            else player.pause();
            paused = !paused;
            showStatus();
            return;
        }
        play(pos, line);
    }

    /** Recites a part line by line, then the next part, to the end; parts without a recording are passed over. */
    private void play(int pos, int line) {
        releasePlayer();
        while (pos < parts.size() && (line >= parts.get(pos).lines.size() || parts.get(pos).lines.get(line)[3].isEmpty())) {
            if (line < parts.get(pos).lines.size() && parts.get(pos).lines.get(line)[3].isEmpty()) {
                pos++;
                line = 0;
            } else {
                pos++;
                line = 0;
            }
        }
        if (pos >= parts.size()) {
            stopAudio();
            return;
        }
        playPart = pos;
        playLine = line;
        paused = false;
        ((BaseAdapter) partList.getAdapter()).notifyDataSetChanged();
        for (int r = 0; r < rows.size(); r++) if (rows.get(r)[0] == pos && rows.get(r)[1] == line) partList.setSelection(r);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        try {
            player.setDataSource(parts.get(pos).lines.get(line)[3]);
        } catch (Exception e) {
            stopAudio();
            return;
        }
        final MediaPlayer mp = player;
        final int p = pos, l = line;
        player.setOnPreparedListener(m -> {
            if (m == player) m.start();
        });
        player.setOnCompletionListener(m -> {
            if (m == player) play(p, l + 1);
        });
        player.setOnErrorListener((m, what, extra) -> {
            if (m == mp) readStatus.setText("Can't reach the recitation");
            return true;
        });
        player.prepareAsync();
        showStatus();
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void stopAudio() {
        releasePlayer();
        if (playPart >= 0) {
            playPart = -1;
            if (partList != null && partList.getAdapter() != null) ((BaseAdapter) partList.getAdapter()).notifyDataSetChanged();
        }
        paused = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showStatus();
    }

    private void showStatus() {
        if (readStatus == null || parts.isEmpty()) return;
        String lang = english ? "English" : "Bahasa Melayu";
        if (playPart >= 0) readStatus.setText((paused ? "❚❚  Paused" : "▶  " + parts.get(playPart).name) + "   ·   " + lang);
        else readStatus.setText(parts.size() + " parts   ·   " + lang);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean reading = readPage.getVisibility() == View.VISIBLE;
        if (reading && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            english = !english;
            getSharedPreferences("mathurat", MODE_PRIVATE).edit().putBoolean("english", english).apply();
            int at = partList.getSelectedItemPosition();
            ((BaseAdapter) partList.getAdapter()).notifyDataSetChanged();
            if (at >= 0) partList.setSelection(at);
            showStatus();
            return true;
        }
        if (reading && (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            int r = Math.max(0, partList.getSelectedItemPosition());
            if (playPart >= 0) for (int k = 0; k < rows.size(); k++) if (rows.get(k)[0] == playPart && rows.get(k)[1] == playLine) r = k;
            onPartClick(r);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (readPage.getVisibility() == View.VISIBLE) showChoice();
        else super.onBackPressed();
    }

    // ---- helpers ---------------------------------------------------------------------------------

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

    private static String arabicDigits(int n) {
        StringBuilder b = new StringBuilder();
        for (char ch : String.valueOf(n).toCharArray()) b.append((char) ('٠' + (ch - '0')));
        return b.toString();
    }

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
        c.setRequestProperty("User-Agent", "GlassTV-Mathurat/1.0 (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 3 * 1024 * 1024) throw new IllegalStateException("response too large");
                }
                String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return s.startsWith("﻿") ? s.substring(1) : s;
            }
        } finally {
            c.disconnect();
        }
    }
}
