package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical. Once anyone has
 * moved a tile (hold OK, then Move), the order they made is kept instead, and apps installed later
 * join at the end.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** OK was held on a tile. Return false to open the app's info page, as before. */
        boolean onTileHeld(int position, View card);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** The app's own icon at full size, for its tvOS-style tile; loaded the first time it is needed. */
        Drawable glyph;
        boolean glyphLoaded, glyphIsLayer;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.07f;
    /** Shared by every tile's zoom, so a focus move makes no new objects. */
    private static final DecelerateInterpolator ZOOM_REDUCED = new DecelerateInterpolator();
    private static final OvershootInterpolator ZOOM_IN = new OvershootInterpolator(1.6f);
    private static final OvershootInterpolator ZOOM_OUT = new OvershootInterpolator(0f);
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    /**
     * tvOS-style tiles: the launcher draws these itself, a vivid gradient in the app's colour with the
     * app's own icon large in the middle, for apps whose own TV art is a small icon beside some text.
     * {top, middle, bottom, symbol colour}; a symbol colour of 0 keeps the icon's own colours.
     */
    private static final java.util.Map<String, int[]> TVOS = new java.util.HashMap<>();
    private static final java.util.Map<String, String> WORDMARK = new java.util.HashMap<>();
    /** Symbols the launcher draws itself, for apps whose own icon does not make a good tile. */
    private static final java.util.Map<String, Integer> GLYPHS = new java.util.HashMap<>();
    static {
        GLYPHS.put("com.glasstv.status", R.drawable.ic_gauge);
        GLYPHS.put("com.glasstv.quran", R.drawable.ic_rub_el_hizb);
        GLYPHS.put("com.glasstv.weather", R.drawable.wx_partly);
        GLYPHS.put("com.glasstv.quotes", R.drawable.ic_quote);
        GLYPHS.put("com.glasstv.doa", R.drawable.ic_doa);
        GLYPHS.put("com.glasstv.mathurat", R.drawable.ic_twilight);
    }
    static {
        TVOS.put("com.android.tv.settings", new int[]{0xFFF2F2F7, 0xFFC7C7CC, 0xFFA2A2A7, 0xFF48484A});
        TVOS.put("com.sony.dtv.timers", new int[]{0xFFFFB547, 0xFFFF8A00, 0xFFF06A00, 0xFFFFFFFF});
        TVOS.put("com.android.tv", new int[]{0xFF64D2FF, 0xFF1E90FF, 0xFF0A60E0, 0xFFFFFFFF});
        TVOS.put("com.realtek.tvmediaplay", new int[]{0xFFD68CFF, 0xFFA855F7, 0xFF7E3FD1, 0xFFFFFFFF});
        TVOS.put("my.gov.rtm.tv", new int[]{0xFFF4FF6A, 0xFFD2E000, 0xFFA9B800, 0});
        TVOS.put("com.sony.dtv.ecodashboard", new int[]{0xFF7CE38B, 0xFF34C759, 0xFF1F9E43, 0xFFFFFFFF});
        TVOS.put("bn.rtbgo.tv", new int[]{0xFF9B5CFF, 0xFF6A2BD9, 0xFF43129E, 0xFFFFC93C});
        TVOS.put("com.glasstv.status", new int[]{0xFF5A5E66, 0xFF2C2E33, 0xFF141518, 0xFF30D158});
        // The three cards: emerald with a gold star, sky blue with the day's sky, plum with a gold quote mark.
        TVOS.put("com.glasstv.quran", new int[]{0xFF22A07E, 0xFF0F6B52, 0xFF07402F, 0xFFE6C27A});
        TVOS.put("com.glasstv.weather", new int[]{0xFF6CC6FF, 0xFF2A8CF0, 0xFF1259C9, 0});
        TVOS.put("com.glasstv.quotes", new int[]{0xFFA77BE0, 0xFF6E44B0, 0xFF422474, 0xFFFFC98A});
        TVOS.put("com.glasstv.doa", new int[]{0xFF3FD3C2, 0xFF138F86, 0xFF075550, 0xFFFFFFFF});
        TVOS.put("com.glasstv.mathurat", new int[]{0xFFFFB86B, 0xFFE0705A, 0xFF7A3E8C, 0xFFFFFFFF});
        // Google's apps: their own logo, in its own colours, on a white tile.
        TVOS.put("com.android.vending", new int[]{0xFFFFFFFF, 0xFFF7F7F9, 0xFFE9E9EE, 0});
        TVOS.put("com.google.android.videos", new int[]{0xFFFFFFFF, 0xFFF7F7F9, 0xFFE9E9EE, 0});
        TVOS.put("com.google.android.youtube.tvkids", new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0});   // its logo sits on its own white square
        TVOS.put("com.google.android.youtube.tvmusic", new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0});   // its logo sits on its own white square
        TVOS.put("my.com.tonton.screen", new int[]{0xFF3A9BF0, 0xFF1A62CC, 0xFF0B3A8F, 0xFFFFFFFF});
        TVOS.put("com.tm.unifitv.tv", new int[]{0xFFFFA040, 0xFFFF7A00, 0xFFE05A00, 0xFFFFFFFF});
        // Apps whose only icon is a small picture of their name (or none): the name is set in sharp type instead.
        WORDMARK.put("bn.rtbgo.tv", "RTBgo");
        WORDMARK.put("com.tm.unifitv.tv", "unifi TV");
        WORDMARK.put("my.com.tonton.screen", "tonton");
    }
    private static final String STYLE_PREFIX = "tile_style_";

    /** Whether this app's tile is drawn tvOS-style: the owner's choice, else yes for the apps above. */
    boolean tvosStyle(String pkg) {
        String s = ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).getString(STYLE_PREFIX + pkg, null);
        return s != null ? s.equals("tvos") : TVOS.containsKey(pkg);
    }

    /** Switches an app between its own art and a tvOS-style tile, and redraws it. */
    void setTvosStyle(String pkg, boolean on) {
        ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).edit().putString(STYLE_PREFIX + pkg, on ? "tvos" : "original").apply();
        for (int i = 0; i < items.size(); i++) if (items.get(i).pkg.equals(pkg)) notifyItemChanged(i);
    }

    /** The app's icon at the highest density it has, and whether it is an adaptive icon's layer (drawn larger). */
    private void loadGlyph(Entry e) {
        if (e.glyphLoaded) return;
        e.glyphLoaded = true;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(e.pkg, 0);
            Drawable d = null;
            if (ai.icon != 0) {
                try {
                    d = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi), null);
                } catch (Exception ignored) {
                }
            }
            if (d == null) d = pm.getApplicationIcon(ai);
            if (d instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                android.graphics.drawable.AdaptiveIconDrawable a = (android.graphics.drawable.AdaptiveIconDrawable) d;
                Drawable mono = Build.VERSION.SDK_INT >= 33 ? a.getMonochrome() : null;
                e.glyph = mono != null ? mono : a.getForeground();
                e.glyphIsLayer = true;
                if (mono != null) e.glyph.setTint(0xFFFFFFFF);
            } else {
                e.glyph = d;
            }
        } catch (Exception ex) {
            e.glyph = e.icon;
        }
    }

    /** The tile's colours: hand-picked for the apps above, else a gradient from the icon's main colour. */
    private int[] tvosColours(Entry e) {
        int[] c = TVOS.get(e.pkg);
        if (c != null) return c;
        int base = e.glyph != null ? mainColour(e.glyph) : 0xFF5E5CE6;
        return new int[]{blend(base, 0xFFFFFFFF, .28f), base, blend(base, 0xFF000000, .28f), 0xFFFFFFFF};
    }

    private static int mainColour(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(cv);
            float best = -1;
            int pick = 0xFF5E5CE6;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 200) continue;
                    Color.colorToHSV(p, hsv);
                    float score = hsv[1] * hsv[2];
                    if (score > best) {
                        best = score;
                        pick = p;
                    }
                }
            }
            b.recycle();
            return pick | 0xFF000000;
        } catch (Exception ex) {
            return 0xFF5E5CE6;
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Gradient, lighter at the top, with a soft sheen over its upper half, in the tile's rounded shape. */
    private Drawable tvosBackground(int[] c, float radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{c[0], c[1], c[2]});
        g.setCornerRadius(radius);
        android.graphics.drawable.GradientDrawable sheen = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x38FFFFFF, 0x0FFFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radius);
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{g, sheen});
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Never shown as tiles: config hidden_apps, plus the apps hidden from the TV with Hide. */
    private final Set<String> hidden;
    /** The apps hidden from the TV (app menu, Hide), kept in the tiles preferences. */
    private final Set<String> userHidden = new HashSet<>();
    private static final String HIDDEN_KEY = "user_hidden";
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    private final List<Entry> items = new ArrayList<>();
    /** The order someone arranged the tiles in, by key (see {@link #keyAt}); empty until they do. */
    private final List<String> arranged = new ArrayList<>();
    private static final String ORDER_PREFS = "tiles", ORDER_KEY = "order";
    /** Rebind payload: only whether the tile's name shows, which depends on being in the dock. */
    private static final Object DOCK_CHANGED = new Object();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = cfg.dockApps;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        userHidden.addAll(ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).getStringSet(HIDDEN_KEY, new HashSet<>()));
        hidden.addAll(userHidden);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.reducedMotion = cfg.reducedMotion;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
        String saved = ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).getString(ORDER_KEY, "");
        if (!saved.isEmpty()) arranged.addAll(Arrays.asList(saved.split("\n")));
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    /** What the tiles were last built from; the same list next time means there is nothing to redo. */
    private String builtFrom;
    /**
     * The app tiles of the last build, by component and install path: a rebuild (one app updated,
     * installed or removed) loads only what changed, and the rest keep their artwork and sampled glow.
     */
    private Map<String, Entry> builtApps = new HashMap<>();

    /**
     * Rebuilds the tiles, unless the launchable apps, their installed versions and the cards are
     * exactly what the tiles were last built from. Loading every banner, icon and label is most of
     * what coming back to Home costs, it runs before Home can draw, and almost every return finds
     * nothing changed.
     *
     * @return whether the tiles were rebuilt
     */
    boolean reload(List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
        // A phone-only app named in dock_apps (e.g. the phone Play Store some boxes ship) has no leanback
        // entry; take its ordinary launcher activity instead. Apps not named stay hidden.
        Set<String> listed = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) listed.add(ri.activityInfo.packageName);
        for (String pkg : favourites) {
            if (listed.contains(pkg)) continue;
            Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> more = pm.queryIntentActivities(phone, 0);
            if (!more.isEmpty() && more.get(0).activityInfo != null) {
                found.add(more.get(0));
                listed.add(pkg);
            }
        }
        // The install path moves when an app is updated, so it stands in for the version: an update
        // that brought a new banner still gets a rebuild.
        List<String> parts = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            parts.add(ri.activityInfo.packageName + '/' + ri.activityInfo.name + '@'
                    + ri.activityInfo.applicationInfo.sourceDir);
        }
        for (Cards.Card c : cards) {
            parts.add('#' + c.pkg + '/' + c.component.flattenToShortString() + '|' + c.label + '|'
                    + c.caption + '|' + c.accent);
        }
        Collections.sort(parts);
        String signature = android.text.TextUtils.join(";", parts);
        if (signature.equals(builtFrom) && !items.isEmpty()) return false;
        builtFrom = signature;
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        List<Entry> fresh = new ArrayList<>();
        Map<String, Entry> apps = new HashMap<>();
        // Some apps list the same screen twice under one name (Sony's Timers & Clock: an activity and an
        // alias); one tile each per app and name.
        Set<String> named = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            if (!named.add(pkg + '|' + ri.loadLabel(pm))) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            String key = component.flattenToShortString() + '@' + ri.activityInfo.applicationInfo.sourceDir;
            Entry built = card == null ? builtApps.get(key) : null;
            if (built != null) {
                fresh.add(built);
                apps.put(key, built);
                continue;
            }
            Drawable banner = loadBanner(pm, ri);
            // The icon only shows on a tile without a banner.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent));
            } else {
                // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
                // redraw the tile name as something else (#117).
                Entry entry = new Entry(Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg), component, banner, icon);
                fresh.add(entry);
                apps.put(key, entry);
            }
        }
        builtApps = apps;
        Map<String, Integer> place = new HashMap<>();
        for (int i = 0; i < arranged.size(); i++) place.put(arranged.get(i), i);
        Collections.sort(fresh, (a, b) -> {
            // The order someone arranged comes first; apps it has not seen yet go after it.
            Integer pa = place.get(a.component.flattenToShortString()), pb = place.get(b.component.flattenToShortString());
            if (pa != null || pb != null) {
                if (pa == null) return 1;
                if (pb == null) return -1;
                return Integer.compare(pa, pb);
            }
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
        return true;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                return resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return ri.activityInfo.loadBanner(pm);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, reducedMotion);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        ViewGroup.MarginLayoutParams iconLp = (ViewGroup.MarginLayoutParams) h.icon.getLayoutParams();
        float density = ctx.getResources().getDisplayMetrics().density;
        if (e.kind != Entry.HOME_SETTINGS && tvosStyle(e.pkg)) {
            // tvOS style: the launcher's own gradient, the app's icon large in the middle, no text on the tile.
            loadGlyph(e);
            int[] c = tvosColours(e);
            if (e.ambient == 0) e.ambient = c[1];
            h.card.setBackground(tvosBackground(c, 16 * density));
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
            int tileH = h.card.getLayoutParams().height;
            // About two thirds of the tile's height; an adaptive icon's layer has a wide margin, so it is drawn larger.
            // Some icons carry a wide margin of their own (YouTube Kids and Music): those are drawn larger still.
            float extra = e.pkg.startsWith("com.google.android.youtube.tv") ? 1.6f : 1f;
            int size = Math.round(tileH * (e.glyphIsLayer ? 1.6f : 0.78f) * extra);
            iconLp.width = size;
            iconLp.height = size;
            iconLp.bottomMargin = 0;
            h.icon.setLayoutParams(iconLp);
            // Settings: a clean gear with a hole, as on tvOS, rather than the system's coloured logo.
            if (e.pkg.equals("com.android.tv.settings")) {
                e.glyph = ctx.getDrawable(R.drawable.ic_settings);
                e.glyphIsLayer = false;
            } else if (GLYPHS.containsKey(e.pkg)) {
                e.glyph = ctx.getDrawable(GLYPHS.get(e.pkg));
                e.glyphIsLayer = false;
            }
            String word = WORDMARK.get(e.pkg);
            if (word != null) {
                // A wordmark: the name in bold type, centred, in the tile's symbol colour.
                h.icon.setVisibility(View.GONE);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.label.getLayoutParams();
                lp.gravity = android.view.Gravity.CENTER;
                h.label.setLayoutParams(lp);
                h.label.setPadding(0, 0, 0, 0);
                h.label.setText(word);
                h.label.setTextColor(c[3] != 0 ? c[3] : 0xFFFFFFFF);
                h.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tileH * 0.34f);
                h.label.setFontVariationSettings("'wght' 700");
                h.label.setLetterSpacing(-0.03f);
                h.label.setVisibility(View.VISIBLE);
            } else {
                h.icon.setImageDrawable(e.glyph);
                // A one-colour symbol (monochrome layer, or the icon itself turned into a silhouette).
                if (c[3] != 0) h.icon.setColorFilter(c[3], android.graphics.PorterDuff.Mode.SRC_IN);
                else h.icon.clearColorFilter();
                h.icon.setVisibility(View.VISIBLE);
            }
        } else {
            h.card.setBackgroundResource(R.drawable.tile_bg);
            h.icon.clearColorFilter();
            // The label back as the layout has it, after a wordmark used it.
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.label.getLayoutParams();
            lp.gravity = android.view.Gravity.BOTTOM;
            h.label.setLayoutParams(lp);
            h.label.setPadding(Math.round(10 * density), 0, Math.round(10 * density), Math.round(8 * density));
            h.label.setTextColor(0xFFFFFFFF);
            h.label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            h.label.setFontVariationSettings("'wght' 400");
            h.label.setLetterSpacing(0f);
            iconLp.width = Math.round(48 * density);
            iconLp.height = Math.round(48 * density);
            iconLp.bottomMargin = Math.round(14 * density);
            h.icon.setLayoutParams(iconLp);
        }
        if (e.kind != Entry.HOME_SETTINGS && tvosStyle(e.pkg)) {
            // drawn above
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION && listener.onTileHeld(pos, v)) return true;
            openAppInfo(e);
            return true;
        });
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (payloads.size() == 1 && payloads.get(0) == DOCK_CHANGED) {
            h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);
            return;
        }
        onBindViewHolder(h, position);
    }

    /** Android's page for the app at {@code position}: storage, permissions, uninstall. */
    void openAppInfo(int position) {
        if (position >= 0 && position < items.size()) openAppInfo(items.get(position));
    }

    private void openAppInfo(Entry e) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** The package of the app at a position, or null. */
    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** Whether the tile at {@code position} is an app that can be hidden (not the Default home tile). */
    boolean canHide(int position) {
        return position >= 0 && position < items.size() && items.get(position).kind != Entry.HOME_SETTINGS;
    }

    /** The apps hidden from the TV, by package; empty when none. */
    Set<String> userHidden() {
        return new HashSet<>(userHidden);
    }

    /** Hides or shows an app again; the next reload() rebuilds the tiles without (or with) it. */
    void setHidden(String pkg, boolean hide) {
        if (pkg == null) return;
        if (hide) {
            userHidden.add(pkg);
            hidden.add(pkg);
        } else {
            userHidden.remove(pkg);
            hidden.remove(pkg);
        }
        ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(HIDDEN_KEY, new HashSet<>(userHidden)).apply();
        builtFrom = null;   // the installed apps did not change, the list did: rebuild
    }

    /** Whether the tile at {@code position} can be moved: every app can, the Default home tile cannot. */
    boolean canMove(int position) {
        return position >= 0 && position < items.size() && items.get(position).kind != Entry.HOME_SETTINGS;
    }

    /** The last position a moved tile can take: before the Default home tile, which stays last. */
    int lastMovable() {
        int last = items.size() - 1;
        while (last >= 0 && items.get(last).kind == Entry.HOME_SETTINGS) last--;
        return last;
    }

    /**
     * Moves the tile at {@code from} to {@code to}, the tiles between shifting to make room, and keeps
     * the new order. Returns false when it cannot go there.
     */
    boolean move(int from, int to) {
        if (from == to || !canMove(from) || to < 0 || to > lastMovable()) return false;
        Entry e = items.remove(from);
        items.add(to, e);
        notifyItemMoved(from, to);
        // A tile crossing into or out of the dock shows or hides its name.
        int lo = Math.min(from, to), hi = Math.max(from, to);
        if (lo < dockSize && hi >= dockSize) notifyItemRangeChanged(lo, hi - lo + 1, DOCK_CHANGED);
        arranged.clear();
        for (Entry x : items) if (x.kind != Entry.HOME_SETTINGS) arranged.add(x.component.flattenToShortString());
        ctx.getSharedPreferences(ORDER_PREFS, Context.MODE_PRIVATE).edit()
                .putString(ORDER_KEY, android.text.TextUtils.join("\n", arranged)).apply();
        return true;
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean reducedMotion;

        Holder(View v, TileListener listener, boolean reducedMotion) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.reducedMotion = reducedMotion;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().cancel();
                name.animate().cancel();
                if (reducedMotion) {
                    // No overshoot: the extra frames redraw the banner and the dock band on a weak GPU.
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(ZOOM_REDUCED)
                            .withLayer()
                            .start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100).start();
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .setDuration(focused ? 260 : 180)
                            .setInterpolator(focused ? ZOOM_IN : ZOOM_OUT)
                            .withLayer()
                            .start();
                    name.animate().alpha(focused ? 1f : 0f)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
