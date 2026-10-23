package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Now Playing on Home: when music plays in another app (Spotify, YouTube Music…), a small card at the
 * top of the shelf shows the song, the artist and the cover, with previous, play/pause and next. Up from
 * the dock reaches it. It listens only while Home is on screen, and shows nothing until the TV has
 * allowed {@link MediaAccess}; a stopped or closed player takes the card away.
 */
final class NowPlaying {
    private final Activity a;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName access;
    private final Runnable onDown, onUp;
    private final LinearLayout card;
    private final ImageView art, prev, play, next;
    private final TextView app, title, artist;
    private MediaSessionManager msm;
    private MediaController current;
    private boolean listening;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> pick(list);
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (!playable(current)) pick(null);
            else render();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    /** Builds the card as the shelf's first line. {@code onDown}/{@code onUp} move the focus off it. */
    NowPlaying(Activity a, LinearLayout shelf, Runnable onDown, Runnable onUp) {
        this.a = a;
        this.onDown = onDown;
        this.onUp = onUp;
        density = a.getResources().getDisplayMetrics().density;
        access = new ComponentName(a, MediaAccess.class);
        Typeface outfit = a.getResources().getFont(R.font.outfit);

        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x52000000);
        bg.setCornerRadius(dp(16));
        bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        card.setBackground(bg);

        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8));
            }
        });
        art.setClipToOutline(true);
        card.addView(art, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, dp(14), 0);
        app = text(outfit, 10.5f, 0xB3FFFFFF, 600);
        app.setLetterSpacing(0.18f);
        title = text(outfit, 16, 0xFFFFFFFF, 500);
        artist = text(outfit, 12.5f, 0xC7FFFFFF, 400);
        words.addView(app);
        words.addView(title);
        words.addView(artist);
        // Narrow enough to stay clear of the ayat across from the shelf.
        title.setMaxWidth(dp(165));
        artist.setMaxWidth(dp(165));
        app.setMaxWidth(dp(165));
        card.addView(words);

        prev = button(R.drawable.ic_np_prev);
        play = button(R.drawable.ic_np_play);
        next = button(R.drawable.ic_np_next);
        prev.setOnClickListener(v -> control(c -> c.getTransportControls().skipToPrevious()));
        next.setOnClickListener(v -> control(c -> c.getTransportControls().skipToNext()));
        play.setOnClickListener(v -> control(c -> {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
            else c.getTransportControls().play();
        }));
        for (ImageView b : new ImageView[]{prev, play, next}) card.addView(b);

        card.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(18);
        shelf.addView(card, 0, lp);
    }

    /** Home is on screen: follow the players. Without the TV's permission it simply stays hidden. */
    void start() {
        if (listening) return;
        try {
            if (msm == null) msm = a.getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessions, access, handler);
            listening = true;
            pick(null);
        } catch (SecurityException e) {
            // Not allowed yet (cmd notification allow_listener …): no card.
            hide();
        }
    }

    /** Home has gone behind another app: stop listening, keep nothing. */
    void stop() {
        if (!listening) return;
        listening = false;
        try {
            msm.removeOnActiveSessionsChangedListener(sessions);
        } catch (Exception ignored) {
        }
        follow(null);
    }

    boolean isShown() {
        return card.getVisibility() == View.VISIBLE;
    }

    /** Up from the dock: onto play/pause. */
    void focus() {
        play.requestFocus();
    }

    /** The player that is playing, else one paused with a song on it; never Home itself. */
    private void pick(List<MediaController> list) {
        if (!listening) return;
        try {
            if (list == null) list = msm.getActiveSessions(access);
        } catch (SecurityException e) {
            follow(null);
            return;
        }
        MediaController best = null;
        for (MediaController c : list) {
            if (c.getPackageName().equals(a.getPackageName()) || !playable(c)) continue;
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                best = c;
                break;
            }
            if (best == null) best = c;
        }
        follow(best);
    }

    /** Playing, buffering or paused, with something to name. */
    private static boolean playable(MediaController c) {
        if (c == null) return false;
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (s == null || m == null || TextUtils.isEmpty(titleOf(m))) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED
                || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_SKIPPING_TO_NEXT
                || st == PlaybackState.STATE_SKIPPING_TO_PREVIOUS || st == PlaybackState.STATE_CONNECTING;
    }

    private void follow(MediaController c) {
        if (current != null && (c == null || !c.getSessionToken().equals(current.getSessionToken()))) {
            current.unregisterCallback(callback);
            current = null;
        }
        if (c != null && current == null) {
            current = c;
            current.registerCallback(callback, handler);
        }
        if (current == null) hide();
        else render();
    }

    private void render() {
        MediaController c = current;
        if (c == null) return;
        MediaMetadata m = c.getMetadata();
        PlaybackState s = c.getPlaybackState();
        if (m == null) return;
        String appName = c.getPackageName();
        try {
            PackageManager pm = a.getPackageManager();
            appName = pm.getApplicationLabel(pm.getApplicationInfo(c.getPackageName(), 0)).toString();
        } catch (Exception ignored) {
        }
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        app.setText((playing ? "Now playing · " : "Paused · ") + appName.toUpperCase(Locale.ROOT));
        String name = titleOf(m);
        title.setText(name);
        // Who it is by; a line that only repeats the title (an album named after its song) is skipped.
        String who = "";
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_AUTHOR, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM}) {
            String v = first(m, k);
            if (!v.isEmpty() && !v.equalsIgnoreCase(name)) {
                who = v;
                break;
            }
        }
        artist.setText(who);
        artist.setVisibility(TextUtils.isEmpty(who) ? View.GONE : View.VISIBLE);
        play.setImageResource(playing ? R.drawable.ic_np_pause : R.drawable.ic_np_play);
        long actions = s == null ? 0 : s.getActions();
        prev.setVisibility((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0 ? View.VISIBLE : View.GONE);
        next.setVisibility((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0 ? View.VISIBLE : View.GONE);
        setArt(m);
        card.setVisibility(View.VISIBLE);
    }

    /** The cover, shrunk to the card's size so a large picture is not kept in memory. */
    private void setArt(MediaMetadata m) {
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null) {
            art.setImageDrawable(null);
            art.setVisibility(View.GONE);
            return;
        }
        int size = dp(48);
        Bitmap small = b.getWidth() > size * 2 ? Bitmap.createScaledBitmap(b, size, size, true) : b;
        art.setImageBitmap(small);
        art.setVisibility(View.VISIBLE);
    }

    private void hide() {
        boolean hadFocus = card.hasFocus();
        card.setVisibility(View.GONE);
        art.setImageDrawable(null);
        if (hadFocus) onDown.run();
    }

    private interface Action {
        void run(MediaController c);
    }

    private void control(Action action) {
        if (current == null) return;
        try {
            action.run(current);
        } catch (Exception ignored) {
        }
    }

    private static String titleOf(MediaMetadata m) {
        return first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString();
        }
        return "";
    }

    /** A round control: white icon, and white circle with a dark icon when selected. */
    private ImageView button(int icon) {
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setFocusable(true);
        b.setClickable(true);
        b.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF000000, 0xFFFFFFFF}));
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(0xFFFFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new GradientDrawable());
        b.setBackground(s);
        b.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                onDown.run();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                onUp.run();
                return true;
            }
            // Left and right stay among the three controls.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v == firstShown();
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return v == lastShown();
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private View firstShown() {
        return prev.getVisibility() == View.VISIBLE ? prev : play;
    }

    private View lastShown() {
        return next.getVisibility() == View.VISIBLE ? next : play;
    }

    private TextView text(Typeface font, float sp, int color, int weight) {
        TextView t = new TextView(a);
        t.setTypeface(font);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setFontVariationSettings("'wght' " + weight);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(1), 0, dp(1));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
