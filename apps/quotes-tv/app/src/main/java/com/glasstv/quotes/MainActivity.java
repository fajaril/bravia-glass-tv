package com.glasstv.quotes;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * A quote a day from a built-in collection (assets/quotes.json): hadith with their references, sayings
 * of the scholars, words of wisdom and Malay peribahasa with their meaning. Opens on today's quote;
 * Left/Right browse, Up/Down change the collection (all, each kind, favourites), OK marks a favourite.
 * Works offline: nothing is fetched.
 */
public class MainActivity extends Activity {
    /** The collections Up/Down step through; "fav" is the owner's favourites. */
    private static final String[] KINDS = {"all", "hadith", "scholars", "wisdom", "peribahasa", "fav"};
    private static final String[] KIND_NAMES = {"All quotes", "Hadith", "Wisdom of the scholars", "Words of wisdom",
            "Peribahasa Melayu", "Favourites"};
    /** Each kind's sky: top and bottom of the gradient, and the accent. */
    private static final int[][] SKY = {
            {0xFF0F4A3D, 0xFF04201A, 0xFFDEB868},   // hadith: emerald and gold
            {0xFF1B2A4E, 0xFF0A1022, 0xFFE8C27A},   // scholars: night blue
            {0xFF4A2C62, 0xFF18122C, 0xFFFFBE78},   // wisdom: plum
            {0xFF6B2230, 0xFF240A12, 0xFFF2B36B}};  // peribahasa: songket red

    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private final Set<String> favourites = new HashSet<>();
    private SharedPreferences prefs;
    private float density;
    private Typeface outfit;
    private FrameLayout root;
    private TextView kind, quote, meaning, author, source, counter, heart, filter;
    private int kindIndex, position;

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
        prefs = getSharedPreferences("quotes", MODE_PRIVATE);
        favourites.addAll(prefs.getStringSet("favourites", new HashSet<>()));
        load();
        build();
        // Today's quote: one a day through the whole collection, in Malaysian time.
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kuala_Lumpur"));
        long day = (c.getTimeInMillis() + c.getTimeZone().getOffset(c.getTimeInMillis())) / 86_400_000L;
        select(0, all.isEmpty() ? 0 : (int) (day % all.size()));
    }

    private void load() {
        try (InputStream in = getAssets().open("quotes.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            JSONArray a = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) all.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
    }

    private void build() {
        root = new FrameLayout(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(96), dp(40), dp(96), dp(26));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        kind = text(13, 0xFFFFFFFF, 500);
        kind.setLetterSpacing(0.2f);
        kind.setAllCaps(true);
        top.addView(kind, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        filter = text(13, 0xB3FFFFFF, 500);
        filter.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x24FFFFFF);
        chip.setCornerRadius(dp(18));
        chip.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        filter.setBackground(chip);
        top.addView(filter);
        page.addView(top);

        // The quote sits in the middle of the screen, a large opening mark above it.
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text(110, 0x66FFFFFF, 600);
        mark.setText("“");
        mark.setIncludeFontPadding(false);
        mark.setPadding(0, 0, 0, -dp(40));
        middle.addView(mark);
        quote = text(34, 0xFFFFFFFF, 300);
        quote.setLineSpacing(dp(6), 1f);
        middle.addView(quote);
        meaning = text(19, 0xD9FFFFFF, 400);
        meaning.setLineSpacing(dp(4), 1f);
        meaning.setPadding(0, dp(14), 0, 0);
        middle.addView(meaning);
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setPadding(0, dp(26), 0, 0);
        TextView dash = text(20, 0x99FFFFFF, 300);
        dash.setText("—  ");
        who.addView(dash);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        author = text(20, 0xFFFFFFFF, 500);
        source = text(14, 0xA6FFFFFF, 400);
        names.addView(author);
        names.addView(source);
        who.addView(names);
        middle.addView(who);
        page.addView(middle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = text(12, 0x80FFFFFF, 400);
        hint.setText("◀ ▶  browse     ▲ ▼  collection     OK  favourite");
        bottom.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heart = text(18, 0xFFFF7A8A, 500);
        heart.setPadding(0, 0, dp(18), 0);
        bottom.addView(heart);
        counter = text(13, 0x99FFFFFF, 500);
        bottom.addView(counter);
        page.addView(bottom);

        root.addView(page);
        setContentView(root);
    }

    /** Shows collection {@code k} at {@code at}; an empty collection (no favourites yet) says so. */
    private void select(int k, int at) {
        kindIndex = k;
        shown.clear();
        for (JSONObject q : all) {
            String c = q.optString("c");
            if (k == 0 || KINDS[k].equals(c) || (k == KINDS.length - 1 && favourites.contains(id(q)))) shown.add(q);
        }
        filter.setText(KIND_NAMES[k]);
        position = shown.isEmpty() ? 0 : Math.floorMod(at, shown.size());
        show();
    }

    private void show() {
        if (shown.isEmpty()) {
            paint(2);
            kind.setText("Favourites");
            quote.setText("No favourites yet. Press OK on a quote to keep it here.");
            meaning.setText("");
            author.setText("");
            source.setText("");
            heart.setText("");
            counter.setText("");
            return;
        }
        JSONObject q = shown.get(position);
        String c = q.optString("c");
        int sky = "hadith".equals(c) ? 0 : "scholars".equals(c) ? 1 : "peribahasa".equals(c) ? 3 : 2;
        paint(sky);
        kind.setText("hadith".equals(c) ? "Hadith" : "scholars".equals(c) ? "Wisdom of the scholars"
                : "peribahasa".equals(c) ? "Peribahasa Melayu" : "Words of wisdom");
        kind.setTextColor(SKY[sky][2]);
        String t = q.optString("t");
        quote.setText(t);
        // Long quotes step down a size so they never run off the screen.
        quote.setTextSize(t.length() > 170 ? 26 : t.length() > 110 ? 30 : 34);
        String m = q.optString("m", "");
        meaning.setText(m);
        meaning.setVisibility(m.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        String a = q.optString("a", "");
        author.setText("peribahasa".equals(c) ? "Peribahasa Melayu" : a);
        source.setText(q.optString("s", ""));
        heart.setText(favourites.contains(id(q)) ? "♥" : "♡");
        counter.setText((position + 1) + " / " + shown.size());
    }

    private void paint(int sky) {
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SKY[sky][0], SKY[sky][1]}));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (!shown.isEmpty()) {
                    position = (position + 1) % shown.size();
                    show();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (!shown.isEmpty()) {
                    position = Math.floorMod(position - 1, shown.size());
                    show();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                select((kindIndex + 1) % KINDS.length, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                select(Math.floorMod(kindIndex - 1, KINDS.length), 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleFavourite();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void toggleFavourite() {
        if (shown.isEmpty()) return;
        String id = id(shown.get(position));
        if (!favourites.remove(id)) favourites.add(id);
        prefs.edit().putStringSet("favourites", new HashSet<>(favourites)).apply();
        show();
    }

    /** A quote's own text names it, so favourites survive the collection being reordered or extended. */
    private static String id(JSONObject q) {
        return q.optString("t");
    }

    private TextView text(float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(outfit);
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
