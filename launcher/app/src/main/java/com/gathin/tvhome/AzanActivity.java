package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;

/**
 * The azan, as a floating glass card at the top of the screen over whatever was on (the app or Home stays
 * in view behind it, blurred through the card where the TV can blur). Video and music pause because the
 * azan takes the sound (audio focus). When it ends, or on Back, it closes and the app
 * underneath is where it was.
 */
public class AzanActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private float density;
    private TextView hint;
    /** True once the card has been on screen: until then a stop is the screensaver going, not the owner. */
    private boolean shown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Over the screensaver too: turn the screen on and come in front of it.
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        density = getResources().getDisplayMetrics().density;
        int prayer = getIntent().getIntExtra("prayer", 4);
        Typeface outfit = getResources().getFont(R.font.outfit);
        Typeface amiri = getResources().getFont(R.font.amiri_quran);

        // A floating card at the top of the screen; the app or Home behind it stays in view.
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.y = dp(40);
        w.setAttributes(lp);
        boolean blur = false;
        if (Build.VERSION.SDK_INT >= 31) {
            WindowManager wm = getSystemService(WindowManager.class);
            if (wm != null && wm.isCrossWindowBlurEnabled()) {
                // Frosted glass: what is behind the card shows through it, blurred.
                w.setBackgroundBlurRadius(dp(30));
                blur = true;
            }
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(30), dp(20), dp(34), dp(20));
        GradientDrawable glass = new GradientDrawable();
        // Tinted enough to read over a bright film even where the TV does not blur (this BRAVIA reports
        // blur on but draws the background sharp).
        glass.setColor(blur ? 0xB3101826 : 0xE0141C2C);
        glass.setCornerRadius(dp(28));
        glass.setStroke(Math.max(1, dp(1.5f)), 0x59FFFFFF);
        card.setBackground(glass);
        card.setClipToOutline(true);
        card.setElevation(dp(16));

        TextView takbir = text("ٱللَّهُ أَكْبَرُ", amiri, 40, 0xFFF5D489, 400);
        card.addView(takbir);
        View line = new View(this);
        line.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(70));
        llp.leftMargin = dp(26);
        llp.rightMargin = dp(26);
        card.addView(line, llp);

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        String town = Location.prefs(this).getString("now_town", "");
        TextView label = text(town.isEmpty() ? "AZAN" : "AZAN  ·  " + town.toUpperCase(java.util.Locale.ROOT), outfit, 12, 0xB3FFFFFF, 600);
        label.setLetterSpacing(0.22f);
        label.setGravity(Gravity.START);
        words.addView(label);
        String when = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.BOTTOM);
        nameRow.setPadding(0, dp(2), 0, dp(2));
        TextView name = text(PrayerTimes.NAMES[Math.max(0, Math.min(prayer, PrayerTimes.NAMES.length - 1))], outfit, 28, 0xFFFFFFFF, 500);
        name.setSingleLine(true);
        nameRow.addView(name);
        TextView time = text(when, outfit, 20, 0xCCFFFFFF, 300);
        time.setSingleLine(true);
        time.setPadding(dp(14), 0, 0, dp(3));
        nameRow.addView(time);
        words.addView(nameRow);
        hint = text("Back: stop the azan", outfit, 12, 0x99FFFFFF, 400);
        hint.setGravity(Gravity.START);
        words.addView(hint);
        card.addView(words);
        setContentView(card);

        File file = Azan.recording(this);
        if (file == null) {
            // Not fetched yet (no internet so far): say so briefly, and fetch it for next time.
            hint.setText("The azan recording is not on the TV yet. It will be ready next time.");
            Azan.fetch(this);
            handler.postDelayed(this::finish, 8000);
            return;
        }
        play(file);
    }

    private void play(File file) {
        audio = getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        // Transient focus: Spotify and the like pause now and carry on when the azan is over.
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> { })
                .build();
        audio.requestAudioFocus(focus);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(attrs);
            player.setDataSource(file.getPath());
            player.setOnCompletionListener(mp -> finish());
            player.setOnErrorListener((mp, what, extra) -> {
                finish();
                return true;
            });
            player.prepare();
            // The KLCC recording opens with 12 seconds of birds and the park before the azan itself.
            if (file.getName().equals("azan-klcc.mp3")) player.seekTo(Azan.KLCC_START_MS);
            player.start();
        } catch (Exception e) {
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = true;
    }

    /**
     * Left in any way (Home, another app): the azan stops with it. Not before it has been shown, though:
     * started while the screensaver was ending, it is stopped once on the way in, and must keep playing.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (shown && !isChangingConfigurations()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
    }

    private TextView text(String s, Typeface font, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(font);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (font != null) t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
