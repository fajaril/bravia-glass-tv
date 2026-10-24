package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;

/**
 * The azan, full screen over whatever was on: video apps pause when this comes in front, and music apps
 * pause because the azan takes the sound (audio focus). When it ends, or on Back, it closes and the app
 * underneath is where it was.
 */
public class AzanActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        density = getResources().getDisplayMetrics().density;
        int prayer = getIntent().getIntExtra("prayer", 4);
        Typeface outfit = getResources().getFont(R.font.outfit);
        Typeface amiri = getResources().getFont(R.font.amiri_quran);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B2440, 0xFF0F1A2E, 0xFF06090F}));
        TextView label = text("AZAN", outfit, 14, 0xB3FFFFFF, 600);
        label.setLetterSpacing(0.3f);
        page.addView(label);
        TextView takbir = text("ٱللَّهُ أَكْبَرُ", amiri, 64, 0xFFF5D489, 400);
        takbir.setPadding(0, dp(18), 0, dp(6));
        page.addView(takbir);
        page.addView(text(PrayerTimes.NAMES[Math.max(0, Math.min(prayer, PrayerTimes.NAMES.length - 1))], outfit, 44, 0xFFFFFFFF, 500));
        String town = Location.prefs(this).getString("now_town", "");
        String when = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        TextView sub = text(town.isEmpty() ? when : when + "  ·  " + town, outfit, 18, 0xB3FFFFFF, 400);
        sub.setPadding(0, dp(6), 0, dp(40));
        page.addView(sub);
        TextView hint = text("Back: stop the azan", outfit, 14, 0x80FFFFFF, 400);
        page.addView(hint);
        setContentView(page);

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

    /** Left in any way (Home, another app): the azan stops with it. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
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
