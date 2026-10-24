package com.glasstv.weather;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Home's weather and air quality on one TV screen, from Open-Meteo (no key, no account) and the
 * official API from Jabatan Alam Sekitar. Home is the place chosen on the TV in Glass TV (Location, read
 * from its LocationProvider), else Melaka. Right (or OK) opens a Malaysia page with every state's API;
 * OK on a state shows that state's weather, and Back walks back. Home's last answer is kept, so the
 * screen fills at once and only refreshes behind it; every ten minutes while it is open. Opened from
 * its Glass TV module card or its ordinary tile.
 */
public class MainActivity extends Activity {
    private static final String TAG = "Weather";
    private static final String TZ = "Asia%2FKuala_Lumpur";
    /** DOE's states (STATE_ID 1 to 16), with the capital whose weather stands for each. */
    private static final String[] STATES = {"Johor", "Kedah", "Kelantan", "Melaka", "Negeri Sembilan", "Pahang",
            "Pulau Pinang", "Perak", "Perlis", "Selangor", "Terengganu", "Sabah", "Sarawak", "Kuala Lumpur", "Labuan", "Putrajaya"};
    private static final double[][] CAPITALS = {{1.4927, 103.7414}, {6.1248, 100.3678}, {6.1254, 102.2381}, {2.1896, 102.2501},
            {2.7297, 101.9381}, {3.8077, 103.3260}, {5.4141, 100.3288}, {4.5975, 101.0901}, {6.4414, 100.1986}, {3.0733, 101.5185},
            {5.3296, 103.1370}, {5.9804, 116.0735}, {1.5535, 110.3593}, {3.1390, 101.6869}, {5.2831, 115.2308}, {2.9264, 101.6964}};
    /** Where Glass TV says the TV is (LocationProvider); read-only, for apps signed with the same key. */
    private static final android.net.Uri LOCATION = android.net.Uri.parse("content://com.gathin.tvhome.location");
    /** Home: its state (index into STATES), its name and the place its weather is for. Melaka until Glass TV says otherwise. */
    private int home = 3;
    private String homeName = "Melaka";
    private double homeLat = CAPITALS[3][0], homeLon = CAPITALS[3][1];
    /** The Department of Environment's own APIMS feed: every station's hourly API, as the APIMS map shows it. */
    private static final String DOE = "https://eqms.doe.gov.my/api3/publicportalapims/mapcaqm";
    private static final long REFRESH_MS = 10 * 60_000L;

    /** Malaysian API bands (Indeks Pencemar Udara), with DOE's colours: blue, green, yellow, orange, red. */
    private static final int[] API_TOP = {50, 100, 200, 300, 9999};
    private static final int[] API_COLOR = {0xFF3D8BFF, 0xFF34C759, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A};
    private static final String[] API_NAME = {"Good", "Moderate", "Unhealthy", "Very unhealthy", "Hazardous"};
    private static final String[] API_ADVICE = {
            "Air is clean. Normal outdoor activities.",
            "Normal activities. People with heart or lung conditions should limit long outdoor exertion.",
            "Reduce outdoor activity. Children, older adults and people with asthma should stay indoors.",
            "Avoid outdoor activity. Wear a mask outside and keep windows closed.",
            "Stay indoors with windows closed. Follow official haze advisories."};

    /** US AQI bands: upper bound, colour, name, advice. */
    private static final int[] AQI_TOP = {50, 100, 150, 200, 300, 500};
    private static final int[] AQI_COLOR = {0xFF4CC38A, 0xFFF2CC4B, 0xFFF59A3C, 0xFFEF5350, 0xFFAB47BC, 0xFF8E2441};
    private static final String[] AQI_NAME = {"Good", "Moderate", "Unhealthy for sensitive groups", "Unhealthy",
            "Very unhealthy", "Hazardous"};
    private static final String[] AQI_ADVICE = {
            "Air is clean. A good time to be outdoors.",
            "Acceptable. Unusually sensitive people may limit long outdoor exertion.",
            "Children, older adults and people with asthma should reduce outdoor exertion.",
            "Everyone should limit time outdoors. Keep windows closed.",
            "Avoid outdoor activity. Wear a mask outside; run an air purifier indoors.",
            "Stay indoors with windows closed. Wear an N95 mask if you must go out."};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };
    private float density;
    /** The state on the weather page (index into STATES); home unless one was opened from the Malaysia page. */
    private int state = 3;
    private double lat = CAPITALS[3][0], lon = CAPITALS[3][1];
    private boolean dashShown;
    /** The latest DOE reading, for the Malaysia page. */
    private String lastDoe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        readHome();
        state = home;
        lat = homeLat;
        lon = homeLon;
        setPlace();
        findViewById(R.id.airCard).setBackground(glass(18));
        SharedPreferences last = getSharedPreferences("last", MODE_PRIVATE);
        String forecast = last.getString("forecast", null), air = last.getString("air", null);
        lastDoe = last.getString("doe", null);
        if (forecast != null) show(forecast, air, last.getString("doe", null), last.getLong("at", 0));
        else setSky(true, 2);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

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

    private static String forecastUrl(double lat, double lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day,uv_index"
                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
                + "&timezone=" + TZ + "&forecast_days=7&forecast_hours=12";
    }

    private static String airUrl(double lat, double lon) {
        return "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon
                + "&current=us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide,carbon_monoxide&timezone=" + TZ;
    }

    /**
     * Home from Glass TV's Location: the zone's main town, its coordinates and its state. When home has
     * moved, the last forecast (another place's) is dropped rather than shown.
     */
    private void readHome() {
        try (android.database.Cursor c = getContentResolver().query(LOCATION, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return;
            String code = c.getString(c.getColumnIndexOrThrow("code"));
            String town = c.getString(c.getColumnIndexOrThrow("town"));
            String st = c.getString(c.getColumnIndexOrThrow("state"));
            homeLat = c.getDouble(c.getColumnIndexOrThrow("lat"));
            homeLon = c.getDouble(c.getColumnIndexOrThrow("lon"));
            if (town != null && !town.isEmpty()) homeName = town;
            int index = "WLY02".equals(code) ? 14 : "Wilayah Persekutuan".equals(st) ? 13 : java.util.Arrays.asList(STATES).indexOf(st);
            if (index >= 0) home = index;
            SharedPreferences last = getSharedPreferences("last", MODE_PRIVATE);
            String key = homeLat + "," + homeLon;
            if (!key.equals(last.getString("home", key))) last.edit().remove("forecast").remove("air").remove("at").apply();
            last.edit().putString("home", key).apply();
        } catch (Exception e) {
            // Glass TV not installed, or not allowed: home stays Melaka.
        }
    }

    private void setPlace() {
        ((TextView) findViewById(R.id.place)).setText((state == home ? homeName : STATES[state])
                + (state == home ? "      ▸  All states" : "      ◂  Back to all states"));
    }

    private void fetch() {
        final int forState = state;
        final double la = lat, lo = lon;
        new Thread(() -> {
            try {
                String forecast = get(forecastUrl(la, lo));
                String air, doe;
                try {
                    air = get(airUrl(la, lo));
                } catch (Exception e) {
                    air = null;   // the weather still shows without it
                }
                try {
                    doe = get(DOE);
                } catch (Exception e) {
                    doe = getSharedPreferences("last", MODE_PRIVATE).getString("doe", null);
                }
                long at = System.currentTimeMillis();
                // Only home is kept for the next start; another state is looked at, not lived in.
                if (forState == home) {
                    getSharedPreferences("last", MODE_PRIVATE).edit().putString("forecast", forecast)
                            .putString("air", air).putString("doe", doe).putLong("at", at).apply();
                }
                final String a = air, d = doe;
                handler.post(() -> {
                    if (isDestroyed()) return;
                    lastDoe = d;
                    if (dashShown) showDash();
                    if (forState == state) show(forecast, a, d, at);
                });
            } catch (Exception e) {
                Log.w(TAG, "fetch failed: " + e);
                handler.post(() -> {
                    if (isDestroyed()) return;
                    TextView src = findViewById(R.id.source);
                    if (src.getText().length() == 0) src.setText("Can't reach the weather service. Check the internet connection.");
                });
            }
        }, "weather").start();
    }

    private void show(String forecastJson, String airJson, String doeJson, long at) {
        try {
            JSONObject f = new JSONObject(forecastJson);
            JSONObject now = f.getJSONObject("current");
            int code = now.optInt("weather_code");
            boolean day = now.optInt("is_day", 1) == 1;
            setSky(day, code);
            TextView nowIcon = findViewById(R.id.nowIcon);
            nowIcon.setText(null);
            android.graphics.drawable.Drawable big = getDrawable(icon(code, day));
            int px = (int) (64 * density);
            big.setBounds(0, 0, px, px);
            nowIcon.setCompoundDrawables(big, null, null, null);
            ((TextView) findViewById(R.id.nowTemp)).setText(Math.round(now.optDouble("temperature_2m")) + "°");
            ((TextView) findViewById(R.id.nowText)).setText(describe(code));
            JSONObject daily = f.getJSONObject("daily");
            ((TextView) findViewById(R.id.nowFeels)).setText("Feels like " + Math.round(now.optDouble("apparent_temperature")) + "°"
                    + "   ·   H " + Math.round(daily.getJSONArray("temperature_2m_max").optDouble(0))
                    + "°  L " + Math.round(daily.getJSONArray("temperature_2m_min").optDouble(0)) + "°");
            String detail = "Humidity " + now.optInt("relative_humidity_2m") + "%"
                    + "   ·   Wind " + Math.round(now.optDouble("wind_speed_10m")) + " km/h"
                    + (now.has("uv_index") ? "   ·   UV " + Math.round(now.optDouble("uv_index")) : "")
                    + "\nRain chance today " + daily.getJSONArray("precipitation_probability_max").optInt(0) + "%"
                    + "   ·   Sunrise " + clock(daily.getJSONArray("sunrise").optString(0))
                    + "   ·   Sunset " + clock(daily.getJSONArray("sunset").optString(0));
            ((TextView) findViewById(R.id.nowDetail)).setText(detail);
            showHours(f.getJSONObject("hourly"));
            showDays(daily);
        } catch (Exception e) {
            Log.w(TAG, "forecast unreadable: " + e);
        }
        String doeTime = showAir(airJson, doeJson);
        String when = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(at));
        ((TextView) findViewById(R.id.source)).setText("Updated " + when
                + (doeTime != null ? "  ·  API: Jabatan Alam Sekitar (APIMS), reading at " + doeTime : "")
                + "  ·  Weather and US AQI: Open-Meteo model");
    }

    /**
     * The official API from the nearest DOE station first, with the other stations in the state beside
     * it; Open-Meteo's US AQI underneath as a second opinion. Falls back to US AQI alone when DOE's feed
     * cannot be read. Returns the DOE reading's time, or null.
     */
    private String showAir(String airJson, String doeJson) {
        TextView big = findViewById(R.id.aqi), level = findViewById(R.id.aqiLevel), label = findViewById(R.id.aqiLabel);
        TextView stations = findViewById(R.id.stations), model = findViewById(R.id.pollutants), advice = findViewById(R.id.advice);
        String usAqi = "";
        int us = -1, usBand = 0;
        try {
            JSONObject now = new JSONObject(airJson).getJSONObject("current");
            us = now.optInt("us_aqi", -1);
            while (usBand < AQI_TOP.length - 1 && us > AQI_TOP[usBand]) usBand++;
            usAqi = "Model US AQI " + us + "  ·  PM2.5 " + Math.round(now.optDouble("pm2_5"))
                    + "  ·  PM10 " + Math.round(now.optDouble("pm10")) + " µg/m³";
        } catch (Exception ignored) {
        }
        try {
            JSONArray all = new JSONObject(doeJson).getJSONArray("data");
            JSONObject near = null;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < all.length(); i++) {
                JSONObject st = all.getJSONObject(i);
                if (st.optInt("API", -1) < 0) continue;
                double dx = st.optDouble("LONGITUDE") - lon, dy = st.optDouble("LATITUDE") - lat, dist = dx * dx + dy * dy;
                if (dist < best) {
                    best = dist;
                    near = st;
                }
            }
            if (near == null) throw new IllegalStateException("no station");
            int api = near.getInt("API"), band = 0;
            while (band < API_TOP.length - 1 && api > API_TOP[band]) band++;
            big.setText(String.valueOf(api));
            big.setTextColor(API_COLOR[band]);
            level.setText(API_NAME[band]);
            level.setTextColor(API_COLOR[band]);
            label.setText("API · " + place(near) + pollutant(near.optString("PARAM_SYMBOL")));
            // The other stations in the same state, by name and reading.
            StringBuilder rest = new StringBuilder();
            for (int i = 0; i < all.length(); i++) {
                JSONObject st = all.getJSONObject(i);
                if (st == near || st.optInt("STATE_ID") != near.optInt("STATE_ID") || st.optInt("API", -1) < 0) continue;
                if (rest.length() > 0) rest.append("   ·   ");
                rest.append(place(st)).append(" ").append(st.optInt("API"));
            }
            stations.setText(rest);
            stations.setVisibility(rest.length() > 0 ? View.VISIBLE : View.GONE);
            model.setText(usAqi);
            advice.setText(API_ADVICE[band]);
            drawScale(api, new int[]{50, 50, 100, 100}, API_COLOR, 300);
            return clock(near.optString("RECORD_DT"));
        } catch (Exception e) {
            Log.w(TAG, "DOE feed unreadable: " + e);
        }
        // No official reading: the model's US AQI alone, said plainly.
        stations.setVisibility(View.GONE);
        if (us < 0) {
            big.setText("–");
            level.setText("Not available");
            level.setTextColor(0xCCFFFFFF);
            label.setText("");
            model.setText("");
            advice.setText("");
            return null;
        }
        big.setText(String.valueOf(us));
        big.setTextColor(AQI_COLOR[usBand]);
        level.setText(AQI_NAME[usBand]);
        level.setTextColor(AQI_COLOR[usBand]);
        label.setText("US AQI · model, DOE unavailable");
        model.setText(usAqi);
        advice.setText(AQI_ADVICE[usBand]);
        drawScale(us, new int[]{50, 50, 50, 50, 100}, AQI_COLOR, 300);
        return null;
    }

    // ---- pages -----------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!dashShown && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || (state != home && keyCode == KeyEvent.KEYCODE_DPAD_LEFT))) {
            openDash();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (dashShown) openState(home);      // the Malaysia page backs out to home
        else if (state != home) openDash();  // another state backs out to the Malaysia page
        else super.onBackPressed();
    }

    private void openDash() {
        dashShown = true;
        findViewById(R.id.detail).setVisibility(View.GONE);
        findViewById(R.id.dash).setVisibility(View.VISIBLE);
        findViewById(R.id.root).setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF14223D, 0xFF070C18}));
        showDash();
        View focus = findViewById(R.id.dashGrid).findViewWithTag("state" + state);
        if (focus != null) focus.requestFocus();
    }

    /** Shows a state's weather page; its weather is fetched now, its air from the DOE reading in hand. */
    private void openState(int index) {
        boolean changed = index != state;
        state = index;
        lat = index == home ? homeLat : CAPITALS[index][0];
        lon = index == home ? homeLon : CAPITALS[index][1];
        dashShown = false;
        findViewById(R.id.dash).setVisibility(View.GONE);
        findViewById(R.id.detail).setVisibility(View.VISIBLE);
        setPlace();
        if (changed) {
            SharedPreferences last = getSharedPreferences("last", MODE_PRIVATE);
            if (index == home && last.getString("forecast", null) != null) {
                show(last.getString("forecast", null), last.getString("air", null), lastDoe, last.getLong("at", 0));
            } else {
                ((TextView) findViewById(R.id.nowTemp)).setText("–");
                ((TextView) findViewById(R.id.nowText)).setText("Loading…");
                ((TextView) findViewById(R.id.nowFeels)).setText("");
                ((TextView) findViewById(R.id.nowDetail)).setText("");
                ((LinearLayout) findViewById(R.id.hours)).removeAllViews();
                ((LinearLayout) findViewById(R.id.days)).removeAllViews();
                showAir(null, lastDoe);
            }
            fetch();
        }
    }

    /** The Malaysia page: a count per band, then a card per state with its highest station. */
    private void showDash() {
        LinearLayout grid = findViewById(R.id.dashGrid), summary = findViewById(R.id.dashSummary);
        View had = getCurrentFocus();
        Object hadTag = had != null ? had.getTag() : null;
        grid.removeAllViews();
        summary.removeAllViews();
        JSONArray all;
        try {
            all = new JSONObject(lastDoe).getJSONArray("data");
        } catch (Exception e) {
            TextView t = text("Loading official readings…", 18, 0xCCFFFFFF, 400);
            t.setGravity(Gravity.START);
            grid.addView(t);
            return;
        }
        int[] count = new int[API_TOP.length];
        String time = null;
        java.util.List<java.util.List<JSONObject>> byState = new java.util.ArrayList<>();
        for (int i = 0; i < STATES.length; i++) byState.add(new java.util.ArrayList<>());
        for (int i = 0; i < all.length(); i++) {
            JSONObject st = all.optJSONObject(i);
            if (st == null || st.optInt("API", -1) < 0) continue;
            int id = st.optInt("STATE_ID");
            if (id >= 1 && id <= STATES.length) byState.get(id - 1).add(st);
            count[band(st.optInt("API"))]++;
            if (time == null) time = st.optString("RECORD_DT");
        }
        ((TextView) findViewById(R.id.dashTime)).setText(time != null ? "Reading at " + clock(time) : "");
        for (int b = 0; b < API_TOP.length; b++) {
            TextView chip = text(API_NAME[b] + "  " + count[b], 14, API_COLOR[b], 500);
            int p = (int) (6 * density);
            chip.setPadding(p * 2, p, p * 2, p);
            chip.setBackground(glass(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (10 * density);
            summary.addView(chip, lp);
        }
        int columns = 4;
        LinearLayout row = null;
        for (int i = 0; i < STATES.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            row.addView(stateCard(i, byState.get(i)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        }
        View again = hadTag != null ? grid.findViewWithTag(hadTag) : null;
        if (again != null) again.requestFocus();
    }

    /** One state: its name, the highest API among its stations, and the stations from highest down. */
    private View stateCard(int index, java.util.List<JSONObject> stations) {
        stations.sort((a, b) -> b.optInt("API") - a.optInt("API"));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (12 * density);
        c.setPadding(p, (int) (8 * density), p, (int) (8 * density));
        c.setFocusable(true);
        c.setTag("state" + index);
        TextView name = text(STATES[index] + (index == home ? "  ⌂" : ""), 15, 0xFFFFFFFF, 500);
        name.setGravity(Gravity.START);
        c.addView(name);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.BOTTOM);
        if (stations.isEmpty()) {
            line.addView(text("–", 30, 0x99FFFFFF, 400));
        } else {
            int api = stations.get(0).optInt("API"), b = band(api);
            line.addView(text(String.valueOf(api), 30, API_COLOR[b], 400));
            TextView cls = text(API_NAME[b], 13, API_COLOR[b], 500);
            cls.setPadding((int) (8 * density), 0, 0, (int) (5 * density));
            line.addView(cls);
        }
        c.addView(line);
        StringBuilder list = new StringBuilder();
        for (JSONObject st : stations) {
            if (list.length() > 0) list.append("  ·  ");
            list.append(place(st)).append(" ").append(st.optInt("API"));
        }
        TextView rest = text(stations.size() + (stations.size() == 1 ? " station: " : " stations: ") + list, 11, 0xB3FFFFFF, 400);
        rest.setGravity(Gravity.START);
        rest.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(rest);
        GradientDrawable idle = glass(14), lit = glass(14);
        lit.setColor(0x40FFFFFF);
        lit.setStroke((int) (2 * density), 0xE6FFFFFF);
        c.setBackground(idle);
        c.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? lit : idle);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(140).start();
        });
        c.setOnClickListener(v -> openState(index));
        LinearLayout wrap = new LinearLayout(this);
        int m = (int) (5 * density);
        wrap.setPadding(m, m, m, m);
        wrap.setClipChildren(false);
        wrap.addView(c, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private static int band(int api) {
        int b = 0;
        while (b < API_TOP.length - 1 && api > API_TOP[b]) b++;
        return b;
    }

    /** "Bandaraya Melaka, MELAKA" as "Bandaraya Melaka". */
    private static String place(JSONObject station) {
        String s = station.optString("STATION_LOCATION");
        int comma = s.indexOf(',');
        return comma > 0 ? s.substring(0, comma).trim() : s.trim();
    }

    /** DOE marks the pollutant that sets the index: ** PM2.5, * PM10, a SO₂, b NO₂, c O₃, d CO. */
    private static String pollutant(String symbol) {
        switch (symbol == null ? "" : symbol.trim()) {
            case "**": return " · PM2.5";
            case "*": return " · PM10";
            case "a": return " · SO₂";
            case "b": return " · NO₂";
            case "c": return " · O₃";
            case "d": return " · CO";
            default: return "";
        }
    }

    /** Coloured bands across the card, sized by their share of 0 to {@code max}, and a white marker at the value. */
    private void drawScale(int value, int[] widths, int[] colors, int max) {
        FrameLayout scale = findViewById(R.id.aqiScale);
        scale.removeAllViews();
        LinearLayout bands = new LinearLayout(this);
        for (int i = 0; i < widths.length; i++) {
            View b = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(colors[i]);
            float r = 4 * density;
            g.setCornerRadii(i == 0 ? new float[]{r, r, 0, 0, 0, 0, r, r}
                    : i == widths.length - 1 ? new float[]{0, 0, r, r, r, r, 0, 0} : new float[8]);
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (8 * density), widths[i]);
            lp.gravity = Gravity.CENTER_VERTICAL;
            bands.addView(b, lp);
        }
        bands.setGravity(Gravity.CENTER_VERTICAL);
        scale.addView(bands, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        View marker = new View(this);
        GradientDrawable m = new GradientDrawable();
        m.setColor(0xFFFFFFFF);
        m.setCornerRadius(2 * density);
        marker.setBackground(m);
        scale.addView(marker, new FrameLayout.LayoutParams((int) (4 * density), FrameLayout.LayoutParams.MATCH_PARENT));
        scale.post(() -> marker.setTranslationX(Math.min(1f, Math.max(0f, value / (float) max)) * (scale.getWidth() - marker.getWidth())));
    }

    private void showHours(JSONObject hourly) throws Exception {
        LinearLayout row = findViewById(R.id.hours);
        row.removeAllViews();
        JSONArray time = hourly.getJSONArray("time"), temp = hourly.getJSONArray("temperature_2m");
        JSONArray code = hourly.getJSONArray("weather_code"), rain = hourly.getJSONArray("precipitation_probability");
        JSONArray day = hourly.optJSONArray("is_day");
        for (int i = 0; i < time.length(); i++) {
            String hour = i == 0 ? "Now" : clock(time.optString(i)).replace(":00", "");
            row.addView(column(hour, icon(code.optInt(i), day == null || day.optInt(i, 1) == 1),
                    Math.round(temp.optDouble(i)) + "°", rain.optInt(i) + "%"), weight());
        }
    }

    private void showDays(JSONObject daily) throws Exception {
        LinearLayout row = findViewById(R.id.days);
        row.removeAllViews();
        JSONArray time = daily.getJSONArray("time"), hi = daily.getJSONArray("temperature_2m_max");
        JSONArray lo = daily.getJSONArray("temperature_2m_min"), code = daily.getJSONArray("weather_code");
        JSONArray rain = daily.getJSONArray("precipitation_probability_max");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT), out = new SimpleDateFormat("EEE", Locale.ENGLISH);
        for (int i = 0; i < time.length(); i++) {
            String name = i == 0 ? "Today" : out.format(in.parse(time.optString(i)));
            row.addView(column(name, icon(code.optInt(i), true),
                    Math.round(hi.optDouble(i)) + "° / " + Math.round(lo.optDouble(i)) + "°", rain.optInt(i) + "%"), weight());
        }
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** A small glass column: label, icon, temperature and the chance of rain. */
    private View column(String label, int icon, String temp, String rain) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = (int) (5 * density);
        c.setPadding(p, p, p, p);
        c.addView(text(label, 12, 0xCCFFFFFF, 500));
        android.widget.ImageView i = new android.widget.ImageView(this);
        i.setImageResource(icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams((int) (22 * density), (int) (22 * density));
        ilp.topMargin = (int) (2 * density);
        ilp.bottomMargin = (int) (1 * density);
        c.addView(i, ilp);
        c.addView(text(temp, 15, 0xFFFFFFFF, 400));
        c.addView(text("Rain " + rain, 11, 0xFF80C8FF, 400));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding((int) (3 * density), 0, (int) (3 * density), 0);
        c.setBackground(glass(12));
        wrap.addView(c, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private TextView text(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    /** Frosted panel: a little white over the sky, with a faint edge. */
    private GradientDrawable glass(int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x24FFFFFF);
        g.setCornerRadius(radiusDp * density);
        g.setStroke(Math.max(1, (int) density), 0x33FFFFFF);
        return g;
    }

    /** The background follows the sky: blue by day, navy at night, grey when it rains. */
    private void setSky(boolean day, int code) {
        boolean wet = code >= 51;
        int top = !day ? 0xFF0E1A33 : wet ? 0xFF4A5A70 : 0xFF2F7FC4;
        int bottom = !day ? 0xFF050A16 : wet ? 0xFF1E2836 : 0xFF0F2E57;
        findViewById(R.id.root).setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom}));
    }

    /** "2026-10-22T19:00" or "...T07:12" as "7:00 PM" / "7:12 AM". */
    private static String clock(String iso) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT).parse(iso);
            return new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(d);
        } catch (Exception e) {
            return "";
        }
    }

    /** WMO weather codes, in words. */
    private static String describe(int code) {
        if (code == 0) return "Clear sky";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog / haze";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return code >= 65 ? "Heavy rain" : "Rain";
        if (code >= 80 && code <= 82) return code == 82 ? "Heavy showers" : "Showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    /** WMO weather codes as small vector symbols (not emoji: the colour emoji font alone costs megabytes). */
    private static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_sun : R.drawable.wx_moon;
        if (code == 1 || code == 2) return day ? R.drawable.wx_partly : R.drawable.wx_partly_night;
        if (code == 3) return R.drawable.wx_cloud;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 51 && code <= 57) return R.drawable.wx_drizzle;
        if (code >= 61 && code <= 67) return R.drawable.wx_rain;
        if (code >= 80 && code <= 82) return R.drawable.wx_rain;
        if (code >= 95) return R.drawable.wx_storm;
        return R.drawable.wx_cloud;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "GlassTV-Weather/1.0 (Android TV)");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > 512 * 1024) throw new IllegalStateException("response too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
