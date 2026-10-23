package com.glasstv.quran;

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
import android.widget.AdapterView;
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
import java.util.List;

/**
 * The Quran from Quran.com on the TV: the 114 surahs, then a surah verse by verse in Uthmani script with
 * a Malay or English translation, recited by Mishary Alafasy. Text is fetched once per surah and kept,
 * so a surah opened before reads offline; the recitation streams verse by verse.
 *
 * Remote: Up/Down move, OK opens a surah or recites from the selected verse (OK again pauses),
 * Left/Right switch Bahasa Melayu / English / Arabic only, Back returns to the list.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Quran";
    private static final String API = "https://api.quran.com/api/v4/";
    private static final String AUDIO = "https://verses.quran.com/";
    /** Abdullah Muhammad Basmeih (Malay) and Saheeh International (English); recitation 7 is Mishary Alafasy. */
    private static final int MALAY = 39, ENGLISH = 20, RECITER = 7;
    private static final String[] MODES = {"Bahasa Melayu", "English", "Arabic only"};
    private static final int GOLD = 0xFFDEB868;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private Typeface outfit, amiri;
    private SharedPreferences prefs;

    private FrameLayout root;
    private View listPage, readPage;
    private ListView surahList, verseList;
    private TextView readTitle, readInfo, readStatus, resume;

    private JSONArray chapters;
    /** The open surah: its number and verses (Arabic, Malay, English, audio path). */
    private int chapter;
    private final List<String[]> verses = new ArrayList<>();
    private int mode;
    private MediaPlayer player;
    private int playing = -1;
    private boolean paused;

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
        amiri = getResources().getFont(R.font.amiri_quran);
        prefs = getSharedPreferences("quran", MODE_PRIVATE);
        mode = prefs.getInt("mode", 0);
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0F4A3D, 0xFF04201A}));
        buildList();
        buildReader();
        setContentView(root);
        showList();
        loadChapters();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudio();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---- surah list ------------------------------------------------------------------------------

    private void buildList() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(dp(56), dp(34), dp(56), dp(24));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        TextView arabic = text("القرآن الكريم", 44, GOLD, amiri, 400);
        arabic.setGravity(Gravity.LEFT);
        side.addView(arabic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        side.addView(text("Al-Quran", 34, 0xFFFFFFFF, outfit, 300));
        TextView about = text("114 surahs · Uthmani script\nBahasa Melayu (Basmeih) · English (Saheeh Intl.)\nRecitation: Mishary Rashid Alafasy",
                14, 0xB3FFFFFF, outfit, 400);
        about.setSingleLine(false);
        about.setLineSpacing(dp(4), 1f);
        about.setPadding(0, dp(12), 0, dp(24));
        side.addView(about);
        resume = text("", 16, 0xFFFFFFFF, outfit, 500);
        resume.setPadding(dp(18), dp(12), dp(18), dp(12));
        resume.setFocusable(true);
        resume.setBackground(pill(true));
        resume.setOnClickListener(v -> openChapter(prefs.getInt("last_ch", 1), prefs.getInt("last_verse", 0)));
        resume.setVisibility(View.GONE);
        side.addView(resume, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView source = text("Text, translations and audio: Quran.com", 11, 0x80FFFFFF, outfit, 400);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
        side.addView(new View(this), slp);
        side.addView(source);
        page.addView(side, new LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT));

        surahList = list();
        surahList.setOnItemClickListener((p, v, pos, id) -> openChapter(pos + 1, 0));
        page.addView(surahList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        listPage = page;
        root.addView(page);
    }

    private void loadChapters() {
        new Thread(() -> {
            try {
                String json = cached("chapters_ms.json", API + "chapters?language=ms");
                JSONArray all = new JSONObject(json).getJSONArray("chapters");
                handler.post(() -> {
                    chapters = all;
                    surahList.setAdapter(new SurahAdapter());
                    updateResume();
                    surahList.requestFocus();
                });
            } catch (Exception e) {
                Log.w(TAG, "chapters: " + e);
                handler.post(() -> {
                    resume.setVisibility(View.VISIBLE);
                    resume.setText("Can't reach Quran.com. Check the internet connection.");
                });
            }
        }, "chapters").start();
    }

    private void updateResume() {
        int ch = prefs.getInt("last_ch", 0);
        if (ch < 1 || chapters == null) return;
        JSONObject c = chapters.optJSONObject(ch - 1);
        resume.setText("▶  Continue  ·  " + c.optString("name_simple") + "  " + ch + ":" + (prefs.getInt("last_verse", 0) + 1));
        resume.setVisibility(View.VISIBLE);
    }

    private class SurahAdapter extends BaseAdapter {
        @Override public int getCount() { return chapters.length(); }
        @Override public Object getItem(int p) { return chapters.optJSONObject(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            JSONObject c = chapters.optJSONObject(p);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(20), dp(10));
            TextView num = text(String.valueOf(p + 1), 15, GOLD, outfit, 500);
            num.setGravity(Gravity.CENTER);
            row.addView(num, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout names = new LinearLayout(MainActivity.this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(dp(10), 0, 0, 0);
            TextView name = text(c.optString("name_simple"), 19, 0xFFFFFFFF, outfit, 500);
            name.setGravity(Gravity.START);
            names.addView(name);
            String place = "makkah".equals(c.optString("revelation_place")) ? "Makkiyyah" : "Madaniyyah";
            JSONObject tr = c.optJSONObject("translated_name");
            TextView sub = text((tr != null ? tr.optString("name") + "  ·  " : "") + c.optInt("verses_count") + " ayat  ·  " + place,
                    13, 0xA6FFFFFF, outfit, 400);
            sub.setGravity(Gravity.START);
            names.addView(sub);
            row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text(c.optString("name_arabic"), 28, GOLD, amiri, 400));
            return row;
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
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        readTitle = text("", 28, 0xFFFFFFFF, outfit, 400);
        readTitle.setGravity(Gravity.START);
        readInfo = text("", 13, 0xB3FFFFFF, outfit, 500);
        readInfo.setGravity(Gravity.START);
        readInfo.setLetterSpacing(0.12f);
        titles.addView(readTitle);
        titles.addView(readInfo);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        readStatus = text("", 14, GOLD, outfit, 500);
        readStatus.setPadding(dp(14), dp(8), dp(14), dp(8));
        readStatus.setBackground(pill(false));
        head.addView(readStatus);
        page.addView(head);

        verseList = list();
        verseList.setOnItemClickListener((p, v, pos, id) -> onVerseClick(pos));
        verseList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("last_ch", chapter).putInt("last_verse", pos).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        page.addView(verseList, lp);
        TextView hint = text("OK: recite from this verse / pause   ·   ◀ ▶: Bahasa Melayu · English · Arabic only   ·   Back: surah list",
                11, 0x80FFFFFF, outfit, 400);
        hint.setGravity(Gravity.START);
        hint.setPadding(0, dp(8), 0, 0);
        page.addView(hint);
        readPage = page;
        root.addView(page);
    }

    private void openChapter(int number, int verse) {
        stopAudio();
        chapter = number;
        verses.clear();
        JSONObject c = chapters != null ? chapters.optJSONObject(number - 1) : null;
        readTitle.setText(c != null ? number + ".  " + c.optString("name_simple") + "   " + c.optString("name_arabic") : "Surah " + number);
        if (c != null) readTitle.setTypeface(outfit);
        JSONObject tr = c != null ? c.optJSONObject("translated_name") : null;
        readInfo.setText(c == null ? "" : ((tr != null ? tr.optString("name") + "  ·  " : "") + c.optInt("verses_count") + " ayat").toUpperCase());
        readStatus.setText("Loading…");
        verseList.setAdapter(new VerseAdapter());
        listPage.setVisibility(View.GONE);
        readPage.setVisibility(View.VISIBLE);
        verseList.requestFocus();
        final int ch = number;
        new Thread(() -> {
            try {
                List<String[]> got = fetchVerses(ch);
                handler.post(() -> {
                    if (ch != chapter) return;
                    verses.clear();
                    verses.addAll(got);
                    ((BaseAdapter) verseList.getAdapter()).notifyDataSetChanged();
                    int at = Math.max(0, Math.min(verse, verses.size() - 1));
                    verseList.setSelection(at);
                    verseList.requestFocus();
                    showStatus();
                });
            } catch (Exception e) {
                Log.w(TAG, "verses " + ch + ": " + e);
                handler.post(() -> readStatus.setText("Can't reach Quran.com"));
            }
        }, "verses").start();
    }

    /** Every verse of a surah: Arabic, Malay, English and its recitation file. Kept after the first fetch. */
    private List<String[]> fetchVerses(int ch) throws Exception {
        File file = new File(getFilesDir(), "surah_" + ch + ".json");
        JSONArray all = null;
        if (file.isFile()) {
            try {
                all = new JSONArray(read(file));
            } catch (Exception ignored) {
            }
        }
        if (all == null) {
            all = new JSONArray();
            for (int page = 1; page < 20; page++) {
                JSONObject res = new JSONObject(get(API + "verses/by_chapter/" + ch + "?fields=text_uthmani&per_page=50&page=" + page
                        + "&translations=" + MALAY + "," + ENGLISH + "&audio=" + RECITER));
                JSONArray vs = res.getJSONArray("verses");
                for (int i = 0; i < vs.length(); i++) all.put(vs.get(i));
                JSONObject pag = res.optJSONObject("pagination");
                if (pag == null || pag.isNull("next_page")) break;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(all.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject v = all.getJSONObject(i);
            String malay = "", english = "";
            JSONArray trs = v.optJSONArray("translations");
            for (int t = 0; trs != null && t < trs.length(); t++) {
                JSONObject tr = trs.getJSONObject(t);
                String clean = tr.optString("text").replaceAll("<sup[^>]*>.*?</sup>", "").replaceAll("<[^>]+>", "").trim();
                if (tr.optInt("resource_id") == MALAY) malay = clean;
                else if (tr.optInt("resource_id") == ENGLISH) english = clean;
            }
            JSONObject audio = v.optJSONObject("audio");
            out.add(new String[]{v.optString("text_uthmani"), malay, english, audio != null ? audio.optString("url") : "",
                    v.optString("verse_key")});
        }
        return out;
    }

    private class VerseAdapter extends BaseAdapter {
        @Override public int getCount() { return verses.size(); }
        @Override public Object getItem(int p) { return verses.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int p, View convert, ViewGroup parent) {
            String[] v = verses.get(p);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(22), dp(12), dp(22), dp(14));
            if (p == playing) row.setBackground(highlight());
            TextView ar = text(v[0] + "  ﴿" + arabicDigits(p + 1) + "﴾", 30, p == playing ? GOLD : 0xFFFFFFFF, amiri, 400);
            ar.setSingleLine(false);
            // Right-aligned, as on the page of a mushaf; the layout itself runs left to right.
            ar.setTextDirection(View.TEXT_DIRECTION_RTL);
            ar.setGravity(Gravity.RIGHT);
            ar.setLineSpacing(dp(6), 1f);
            row.addView(ar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            String tr = mode == 0 ? v[1] : mode == 1 ? v[2] : "";
            if (!tr.isEmpty()) {
                TextView t = text(v[4] + "   " + tr, 15, 0xD9FFFFFF, outfit, 400);
                t.setSingleLine(false);
                t.setGravity(Gravity.START);
                t.setLineSpacing(dp(3), 1f);
                t.setPadding(0, dp(8), 0, 0);
                row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return row;
        }
    }

    private void onVerseClick(int pos) {
        if (playing == pos && player != null) {
            if (paused) player.start();
            else player.pause();
            paused = !paused;
            showStatus();
            return;
        }
        play(pos);
    }

    /** Recites from a verse and carries on to the end of the surah, the verse being read lit and kept in view. */
    private void play(int pos) {
        stopAudio();
        if (pos < 0 || pos >= verses.size() || verses.get(pos)[3].isEmpty()) return;
        playing = pos;
        paused = false;
        ((BaseAdapter) verseList.getAdapter()).notifyDataSetChanged();
        verseList.setSelection(pos);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String path = verses.get(pos)[3];
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        try {
            player.setDataSource(path.startsWith("http") ? path : AUDIO + path);
        } catch (Exception e) {
            readStatus.setText("Can't play this verse");
            return;
        }
        final MediaPlayer mp = player;
        player.setOnPreparedListener(m -> {
            if (m == player) m.start();
        });
        player.setOnCompletionListener(m -> {
            if (m != player) return;
            if (pos + 1 < verses.size()) play(pos + 1);
            else stopAudio();
        });
        player.setOnErrorListener((m, what, extra) -> {
            if (m == mp) readStatus.setText("Can't reach the recitation");
            return true;
        });
        player.prepareAsync();
        prefs.edit().putInt("last_ch", chapter).putInt("last_verse", pos).apply();
        showStatus();
    }

    private void stopAudio() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playing >= 0) {
            playing = -1;
            if (verseList != null && verseList.getAdapter() != null) ((BaseAdapter) verseList.getAdapter()).notifyDataSetChanged();
        }
        paused = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showStatus();
    }

    private void showStatus() {
        if (readStatus == null) return;
        String m = MODES[mode];
        if (playing >= 0) readStatus.setText((paused ? "❚❚  Paused" : "▶  Reciting " + chapter + ":" + (playing + 1)) + "   ·   " + m);
        else readStatus.setText("Alafasy   ·   " + m);
    }

    // ---- pages and keys --------------------------------------------------------------------------

    private void showList() {
        readPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        updateResume();
        if (chapter > 0 && surahList.getAdapter() != null) surahList.setSelection(chapter - 1);
        surahList.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean reading = readPage.getVisibility() == View.VISIBLE;
        if (reading && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            mode = (mode + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : MODES.length - 1)) % MODES.length;
            prefs.edit().putInt("mode", mode).apply();
            int at = verseList.getSelectedItemPosition();
            ((BaseAdapter) verseList.getAdapter()).notifyDataSetChanged();
            if (at >= 0) verseList.setSelection(at);
            showStatus();
            return true;
        }
        if (reading && (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            int at = playing >= 0 ? playing : Math.max(0, verseList.getSelectedItemPosition());
            onVerseClick(at);
            return true;
        }
        if (reading && keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            stopAudio();
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

    // ---- small helpers ---------------------------------------------------------------------------

    private ListView list() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setDividerHeight(dp(6));
        l.setSelector(rowSelector());
        l.setDrawSelectorOnTop(false);
        l.setVerticalScrollBarEnabled(false);
        l.setCacheColorHint(0);
        l.setItemsCanFocus(false);
        l.setFocusable(true);
        return l;
    }

    private StateListDrawable rowSelector() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x33FFFFFF);
        on.setCornerRadius(dp(14));
        on.setStroke(dp(2), 0xCCFFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_selected}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        return s;
    }

    private GradientDrawable highlight() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x26DEB868);
        g.setCornerRadius(dp(14));
        return g;
    }

    private StateListDrawable pill(boolean focusable) {
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(0x24FFFFFF);
        idle.setCornerRadius(dp(22));
        idle.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        StateListDrawable s = new StateListDrawable();
        if (focusable) {
            GradientDrawable on = new GradientDrawable();
            on.setColor(0xFFFFFFFF);
            on.setCornerRadius(dp(22));
            s.addState(new int[]{android.R.attr.state_focused}, on);
        }
        s.addState(new int[]{}, idle);
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
        // Arabic is never cut with an ellipsis: on right-to-left text it pushed the line out of place.
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

    /** A Quran.com answer kept in the app's files: fetched once, then read from storage. */
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
        c.setRequestProperty("User-Agent", "GlassTV-Quran/1.0 (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 4 * 1024 * 1024) throw new IllegalStateException("response too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
