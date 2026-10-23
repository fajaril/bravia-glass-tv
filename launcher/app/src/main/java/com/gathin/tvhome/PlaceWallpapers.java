package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.FaceDetector;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Wallpapers of places to visit: once a week, takes the pictures Bing has shown on its home page over
 * the past days (landmarks, landscapes, wildlife), each with the place's name and a few lines about
 * it, keeps those with nobody in them, and deletes the week before. Needs no key and no account.
 * The Wallpaper button can switch the source to Wikimedia Commons' Picture of the Day or NASA's Image
 * of the Day instead; the same rules apply to all three. "places": false in config.json turns it off.
 *
 * Runs on a background thread; every failure just leaves the previous pictures in place.
 */
final class PlaceWallpapers {
    private static final String TAG = "TvHome.Places";
    static final String DIR = "place-wallpapers";
    private static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;
    /** Bing adds a photo every day, so it is checked daily and its pictures are kept for 60 days. */
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int POOL_DAYS = 60;
    /** Bing's English editions round the world: each shows some photos of its own beside the shared ones. */
    private static final String[] BING_MARKETS = {"en-US", "en-GB", "en-CA", "en-AU", "en-IN"};
    private static final long RETRY_MS = 30L * 60 * 1000;
    /**
     * Words in a picture's name or headline that mean someone is in it. Only those two are read:
     * they describe the picture, where the paragraph under them tells the place's story, and
     * mentions locals or hikers far more often than it shows them. Faces are looked for as well.
     */
    private static final Pattern PEOPLE = Pattern.compile("\\b(people|persons?|man|men|woman|women|girls?|boys?"
            + "|child|children|kids?|famil(y|ies)|crowds?|festivals?|parades?|carnivals?|danc(er|ers|ing)"
            + "|surf(er|ers|ing)|hik(er|ers|ing)|climb(er|ers)|ski(er|ers)|swimm(er|ers)|fisherm[ae]n|monks?"
            + "|pilgrims?|tourists?|visitors?|weddings?|brides?|processions?|performers?|athletes?|runners?"
            + "|cyclists?|worshipp?ers?|spectators?|audiences?|musicians?|farmers?|riders?|couples?|locals"
            + "|lad(y|ies)|portraits?|selfies?|residents?|travell?ers?|crew|pedestrians?|sailors?|divers?"
            + "|kayakers?|soldiers?|guards?|vendors?|shoppers?|players?|fans|astronauts?|saints?|cosmonauts?|snorkel\\w*|scuba|diving|swimming|bathers?|anglers?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Where the pictures come from, chosen with the Wallpaper button. */
    static final String BING = "bing", WIKIMEDIA = "wikimedia", WIKI_NATURE = "wikinature", NASA = "nasa",
            SMITHSONIAN = "smithsonian", UNSPLASH = "unsplash";
    /** In the Wallpaper menu's order. */
    static final String[] SOURCES = {BING, WIKIMEDIA, WIKI_NATURE, NASA, SMITHSONIAN, UNSPLASH};
    /** Wikimedia Commons' featured landscapes, and featured wildlife, for the Nature & Wildlife source. */
    private static final String FEATURED_LANDSCAPES = "incategory:Featured_pictures_of_landscapes|Featured_pictures_of_mountains"
            + "|Featured_pictures_of_coasts|Featured_pictures_of_forests|Featured_pictures_of_bodies_of_water"
            + "|Featured_pictures_of_volcanoes|Featured_pictures_of_islands filetype:bitmap";
    private static final String FEATURED_WILDLIFE = "incategory:Featured_pictures_of_Carnivora|Featured_pictures_of_Primates"
            + "|Featured_pictures_of_Artiodactyla|Featured_pictures_of_Perissodactyla|Featured_pictures_of_Elephantidae"
            + "|Featured_pictures_of_Cetacea|Featured_pictures_of_Marsupialia|Featured_pictures_of_birds_in_flight"
            + "|Featured_pictures_of_Accipitriformes|Featured_pictures_of_Strigiformes|Featured_pictures_of_Psittaciformes"
            + "|Featured_pictures_of_Coraciiformes|Featured_pictures_of_Pelecaniformes|Featured_pictures_of_Phoenicopteriformes"
            + "|Featured_pictures_of_Sphenisciformes|Featured_pictures_of_Bucerotiformes|Featured_pictures_of_reptiles filetype:bitmap";
    /** api.data.gov's shared demo key: enough for one fetch a week from one TV. */
    private static final String SMITHSONIAN_KEY = "DEMO_KEY";
    /** Pictures larger than this are scaled down before they are kept: the screen is 1920 wide. */
    private static final int MAX_WIDTH = 1920;
    private static final String WIKI_AGENT = "GlassTV/1.0 (Android TV home screen)";

    /** Raised whenever the rules for which pictures are kept change. */
    private static final int RULES = 3;

    private PlaceWallpapers() {
    }

    static File dir(Context ctx) {
        File base = Config.dir(ctx);
        return base != null ? new File(base, DIR) : null;
    }

    /** The pictures on disk, in the order Bing showed them; empty when none. */
    static List<File> list(Context ctx) {
        List<File> out = new ArrayList<>();
        File d = dir(ctx);
        File[] files = d != null ? d.listFiles() : null;
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && f.getName().endsWith(".jpg")) out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    static String source(Context ctx) {
        String s = prefs(ctx).getString("source", BING);
        return java.util.Arrays.asList(SOURCES).contains(s) ? s : BING;
    }

    static void setSource(Context ctx, String source) {
        // A new choice is fetched straight away, not after the half-hour wait between attempts.
        prefs(ctx).edit().putString("source", source).remove("tried_at").apply();
    }

    /**
     * The Unsplash Access Key: the one typed on the TV (Wallpaper menu), else unsplash_key from config.json;
     * empty when there is none. Kept in the launcher's private storage, never in a file others can read.
     */
    static String unsplashKey(Context ctx, Config cfg) {
        String typed = prefs(ctx).getString("unsplash_key", "");
        return !typed.isEmpty() ? typed : cfg.unsplashKey;
    }

    /** True when the key looks like an Access Key: letters, digits, - and _ only. */
    static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{20,80}");
    }

    /** Saves the key typed on the TV; null or empty removes it. */
    static void setUnsplashKey(Context ctx, String key) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (key == null || key.isEmpty()) e.remove("unsplash_key");
        else e.putString("unsplash_key", key);
        e.apply();
    }

    /** The source the pictures on disk came from. */
    static String fetchedSource(Context ctx) {
        return prefs(ctx).getString("fetched_source", BING);
    }

    /** True when a week has passed since the last successful refresh, or there are no pictures yet. */
    static boolean due(Context ctx) {
        // Offline, or a source with nothing usable this week: try again in half an hour, not every minute.
        if (Math.abs(System.currentTimeMillis() - prefs(ctx).getLong("tried_at", 0)) < RETRY_MS) return false;
        long last = prefs(ctx).getLong("refreshed_at", 0);
        // Pictures kept under earlier rules are fetched again once, under the current ones.
        if (prefs(ctx).getInt("rules", 0) < RULES) return true;
        if (!source(ctx).equals(fetchedSource(ctx))) return true;
        long every = BING.equals(source(ctx)) ? DAY_MS : REFRESH_MS;
        return System.currentTimeMillis() - last >= every || list(ctx).isEmpty();
    }

    /**
     * Downloads this week's places and replaces last week's with them. Blocking: call from a
     * background thread. Returns true when the pictures changed.
     */
    static boolean refresh(Context ctx) {
        File d = dir(ctx);
        if (d == null) return false;
        File fresh = new File(d.getParentFile(), DIR + ".new");
        try {
            deleteAll(fresh);
            if (!fresh.mkdirs()) return false;
            String source = source(ctx);
            prefs(ctx).edit().putLong("tried_at", System.currentTimeMillis()).apply();
            if (BING.equals(source)) {
                // Bing grows a collection in place rather than replacing a week's set.
                deleteAll(fresh);
                boolean restart = !BING.equals(fetchedSource(ctx)) || prefs(ctx).getInt("rules", 0) < RULES;
                int added = fetchBingPool(d, restart);
                if (list(ctx).isEmpty()) return false;
                deleteAll(new File(d.getParentFile(), "movie-wallpapers"));
                prefs(ctx).edit().putLong("refreshed_at", System.currentTimeMillis()).putInt("rules", RULES)
                        .putString("fetched_source", source).apply();
                return added > 0 || restart;
            }
            int n = WIKIMEDIA.equals(source) ? fetchWikimedia(fresh) : NASA.equals(source) ? fetchNasa(fresh)
                    : WIKI_NATURE.equals(source) ? fetchWikiNature(fresh) : SMITHSONIAN.equals(source) ? fetchSmithsonian(fresh)
                    : UNSPLASH.equals(source) ? fetchUnsplash(fresh, unsplashKey(ctx, Config.load(ctx)), Config.load(ctx).unsplashQuery)
                    : fetchBing(fresh);
            if (n == 0) {
                deleteAll(fresh);
                return false;
            }
            // Last week's pictures go once this week's are all in, and so do the film pictures
            // earlier versions kept.
            deleteAll(d);
            deleteAll(new File(d.getParentFile(), "movie-wallpapers"));
            if (!fresh.renameTo(d)) return false;
            prefs(ctx).edit().putLong("refreshed_at", System.currentTimeMillis()).putInt("rules", RULES)
                    .putString("fetched_source", source).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "refresh failed: " + e);
            deleteAll(fresh);
            return false;
        }
    }

    /** The pictures Bing has shown over the past days. Returns how many were kept. */
    private static int fetchBing(File fresh) throws Exception {
        int n = 0;
        String mkt = Locale.getDefault().toLanguageTag();
        JSONObject model = new JSONObject(new String(get("https://www.bing.com/hp/api/model?mkt="
                + URLEncoder.encode(mkt, "UTF-8"), 512 * 1024), StandardCharsets.UTF_8));
        JSONArray items = model.optJSONArray("MediaContents");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject about = item != null ? item.optJSONObject("ImageContent") : null;
            JSONObject image = about != null ? about.optJSONObject("Image") : null;
            if (image == null) continue;
            String place = about.optString("Title", "").trim();
            String headline = about.optString("Headline", "").trim();
            String text = about.optString("Description", "").trim();
            String url = image.optString("Url", "");
            if (place.isEmpty() || url.isEmpty()) continue;
            if (PEOPLE.matcher(place + " " + headline).find()) {
                Log.i(TAG, "skipped, names people: " + place);
                continue;
            }
            // The 1920 x 1080 picture, as a JPEG rather than the WebP Bing names first.
            byte[] jpg = get("https://www.bing.com" + url.replace(".webp", ".jpg"), 3 * 1024 * 1024);
            int faces = faces(jpg);
            if (faces != 0) {
                Log.i(TAG, "skipped, " + (faces < 0 ? "unreadable" : faces + " face(s)") + ": " + place);
                continue;
            }
            // Only somewhere: a picture whose caption names no country (fossils, say) is left out.
            String[] named = nameAndCountry(place);
            if (named[1].isEmpty()) {
                Log.i(TAG, "skipped, no country: " + place);
                continue;
            }
            String base = String.format(Locale.ROOT, "%02d", n++);
            write(new File(fresh, base + ".jpg"), jpg);
            // One line each, so a stray newline in the text cannot be read back as a name:
            // the thing's name, the description, the country.
            write(new File(fresh, base + ".txt"), (named[0].replace('\n', ' ') + "\n"
                    + text.replace('\n', ' ') + "\n" + named[1].replace('\n', ' ')).getBytes(StandardCharsets.UTF_8));
        }
        return n;
    }

    /**
     * Bing's photos of the day from several editions round the world, kept as a collection: each new
     * one is added as it appears, and those older than POOL_DAYS are removed. The editions' last week
     * comes with Bing's full story; the week before it, with the caption and headline only. Files are
     * named by date and photo, so a photo two editions share is kept once. Returns how many were added.
     */
    private static int fetchBingPool(File d, boolean restart) throws Exception {
        if (restart) deleteAll(d);
        if (!d.isDirectory() && !d.mkdirs()) return 0;
        // name -> {date, picture path, caption, story, headline}
        java.util.Map<String, String[]> found = new java.util.LinkedHashMap<>();
        for (String mkt : BING_MARKETS) {
            try {
                JSONArray items = new JSONObject(new String(get("https://www.bing.com/hp/api/model?mkt=" + mkt, 512 * 1024),
                        StandardCharsets.UTF_8)).optJSONArray("MediaContents");
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    JSONObject about = item != null ? item.optJSONObject("ImageContent") : null;
                    JSONObject image = about != null ? about.optJSONObject("Image") : null;
                    String name = item != null ? item.optString("Name", "") : "";
                    if (image == null || name.isEmpty()) continue;
                    String[] had = found.get(name);
                    if (had != null && !had[3].isEmpty()) continue;
                    found.put(name, new String[]{item.optString("Ssd", ""), image.optString("Url", "").replace(".webp", ".jpg"),
                            about.optString("Title", "").trim(), about.optString("Description", "").trim(),
                            about.optString("Headline", "").trim()});
                }
            } catch (Exception e) {
                Log.w(TAG, "Bing " + mkt + " model: " + e.getMessage());
            }
            for (int idx : new int[]{0, 7}) {
                try {
                    JSONArray images = new JSONObject(new String(get("https://www.bing.com/HPImageArchive.aspx?format=js&n=8&idx="
                            + idx + "&mkt=" + mkt, 256 * 1024), StandardCharsets.UTF_8)).optJSONArray("images");
                    for (int i = 0; images != null && i < images.length(); i++) {
                        JSONObject im = images.optJSONObject(i);
                        if (im == null) continue;
                        java.util.regex.Matcher m = Pattern.compile("OHR\\.([A-Za-z0-9]+)_").matcher(im.optString("urlbase"));
                        if (!m.find() || found.containsKey(m.group(1))) continue;
                        String caption = im.optString("copyright", "").replaceAll("\\s*\\(\u00A9[^)]*\\)\\s*$", "").trim();
                        found.put(m.group(1), new String[]{im.optString("startdate", ""), im.optString("urlbase") + "_1920x1080.jpg",
                                caption, "", im.optString("title", "").trim()});
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Bing " + mkt + " archive: " + e.getMessage());
                }
            }
        }
        // Older than the collection keeps: removed, with their captions.
        java.text.SimpleDateFormat ymd = new java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        String cutoff = ymd.format(new java.util.Date(System.currentTimeMillis() - POOL_DAYS * DAY_MS));
        File[] old = d.listFiles();
        for (int i = 0; old != null && i < old.length; i++) {
            String n = old[i].getName();
            if (n.length() < 8 || n.substring(0, 8).compareTo(cutoff) < 0) old[i].delete();
        }
        int added = 0;
        for (java.util.Map.Entry<String, String[]> e : found.entrySet()) {
            String[] v = e.getValue();
            String date = v[0], url = v[1], place = v[2], story = v[3], headline = v[4];
            if (!date.matches("\\d{8}") || date.compareTo(cutoff) < 0 || url.isEmpty() || place.isEmpty()) continue;
            String base = date + "-" + e.getKey();
            if (new File(d, base + ".jpg").isFile()) continue;
            if (PEOPLE.matcher(place + " " + headline).find()) {
                Log.i(TAG, "skipped, names people: " + place);
                continue;
            }
            String[] named = nameAndCountry(place);
            if (named[1].isEmpty()) {
                Log.i(TAG, "skipped, no country: " + place);
                continue;
            }
            try {
                byte[] jpg = get(url.startsWith("http") ? url : "https://www.bing.com" + url, 3 * 1024 * 1024);
                if (faces(jpg) != 0) {
                    Log.i(TAG, "skipped, faces or unreadable: " + place);
                    continue;
                }
                String text = !story.isEmpty() ? story : headline;
                write(new File(d, base + ".jpg"), jpg);
                write(new File(d, base + ".txt"), (named[0].replace('\n', ' ') + "\n" + text.replace('\n', ' ') + "\n"
                        + named[1].replace('\n', ' ')).getBytes(StandardCharsets.UTF_8));
                added++;
            } catch (Exception ex) {
                Log.w(TAG, "Bing photo skipped: " + ex.getMessage());
            }
        }
        return added;
    }

    /** Wikimedia Commons' Picture of the Day for the past days, newest first. */
    private static int fetchWikimedia(File fresh) throws Exception {
        int n = 0;
        Calendar day = Calendar.getInstance();
        for (int back = 0; back < 10 && n < 7; back++, day.add(Calendar.DAY_OF_MONTH, -1)) {
            try {
                String url = String.format(Locale.ROOT, "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/%04d/%02d/%02d",
                        day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
                JSONObject image = new JSONObject(new String(get(url, 1024 * 1024, WIKI_AGENT), StandardCharsets.UTF_8))
                        .optJSONObject("image");
                if (image == null) continue;
                JSONObject full = image.optJSONObject("image"), thumb = image.optJSONObject("thumbnail");
                JSONObject about = image.optJSONObject("description"), artist = image.optJSONObject("artist");
                String text = about != null ? about.optString("text", "").trim() : "";
                String src = full != null ? full.optString("source", "") : "";
                if (text.isEmpty() || !src.toLowerCase(Locale.ROOT).matches(".*\\.jpe?g(\\?.*)?")) continue;
                // A 1920-wide copy from Wikimedia's thumbnailer rather than the many-megabyte original.
                String pic = src.replaceAll("\\?.*$", "");
                if (full.optInt("width") > MAX_WIDTH && thumb != null) {
                    String t = thumb.optString("source", "").replaceAll("\\?.*$", "");
                    if (t.matches(".*/\\d+px-[^/]+$")) pic = t.replaceFirst("/\\d+px-([^/]+)$", "/" + MAX_WIDTH + "px-$1");
                }
                if (PEOPLE.matcher(text).find()) {
                    Log.i(TAG, "skipped, names people: " + text);
                    continue;
                }
                byte[] jpg = fit(get(pic, 8 * 1024 * 1024, WIKI_AGENT));
                if (jpg == null || faces(jpg) != 0) continue;
                // "Promenade, Norderney, Lower Saxony, Germany": the first part names it, the last says where.
                String plain = text.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s+", " ").trim();
                String[] parts = plain.split("\\s*[,.;]\\s+");
                String name = shorten(subject(parts[0]));
                String where = parts.length > 1 && parts[parts.length - 1].length() <= 30
                        ? parts[parts.length - 1].replaceAll("\\.$", "") : "Wikimedia Commons";
                String credit = artist != null && !artist.optString("name", "").isEmpty()
                        ? "Photo: " + artist.optString("name") + ", Wikimedia Commons" : "Wikimedia Commons";
                save(fresh, n++, jpg, name, text + " " + credit, where);
            } catch (Exception e) {
                Log.w(TAG, "Wikimedia day skipped: " + e.getMessage());
            }
        }
        return n;
    }

    /**
     * Wikimedia Commons' featured pictures of nature: a random handful of landscapes and of wildlife each
     * week, out of hundreds, each with its photographer and licence as Commons asks.
     */
    private static int fetchWikiNature(File fresh) throws Exception {
        int n = 0;
        for (String search : new String[]{FEATURED_LANDSCAPES, FEATURED_WILDLIFE, FEATURED_LANDSCAPES}) {
            String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6"
                    + "&gsrsort=random&gsrlimit=4&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=" + MAX_WIDTH
                    + "&iiextmetadatafilter=ImageDescription%7CArtist%7CLicenseShortName%7CObjectName"
                    + "&gsrsearch=" + URLEncoder.encode(search, "UTF-8")
                    // A random sort is still a cacheable URL: without a one-off parameter the same "random" set came back.
                    + "&nocache=" + System.nanoTime();
            JSONObject pages = new JSONObject(new String(get(url, 1024 * 1024, WIKI_AGENT), StandardCharsets.UTF_8))
                    .optJSONObject("query");
            pages = pages != null ? pages.optJSONObject("pages") : null;
            if (pages == null) continue;
            for (java.util.Iterator<String> it = pages.keys(); it.hasNext() && n < 8; ) {
                JSONObject page = pages.optJSONObject(it.next());
                JSONArray infos = page != null ? page.optJSONArray("imageinfo") : null;
                JSONObject info = infos != null ? infos.optJSONObject(0) : null;
                if (info == null || !"image/jpeg".equals(info.optString("mime"))) continue;
                // Wide pictures only: a tall one would be cropped to a strip on the screen.
                if (info.optInt("width") < info.optInt("height") * 1.2) continue;
                JSONObject meta = info.optJSONObject("extmetadata");
                String title = plain(meta, "ObjectName"), text = plain(meta, "ImageDescription");
                String artist = plain(meta, "Artist"), licence = plain(meta, "LicenseShortName");
                if (title.isEmpty() && text.isEmpty() || PEOPLE.matcher(title + " " + text).find()) continue;
                // The description's opening words name the subject ("Barn Owl in flight (Tyto alba)"); the
                // object name is often a file name ("Tyto alba 1 Luc Viatour"), so it is only the fallback.
                String lead = text.replaceAll("\\s*[,(.;].*$", "").trim();
                // Wiki Loves Earth's stock caption ("This is a photo of natural heritage of ...") names nothing.
                boolean stock = lead.matches("(?i)^this (is|was) an? (photo|photograph|photography|picture|image)\\b.*")
                        || lead.matches("^(Name|Title|Description)\\p{Lu}.*");   // a table flattened into one line
                String name = !lead.isEmpty() && lead.length() <= 60 && !stock ? lead : !title.isEmpty() ? title : text;
                String thumb = info.optString("thumburl", "").replaceAll("\\?.*$", "");
                if (thumb.isEmpty()) continue;
                try {
                    byte[] jpg = fit(get(thumb, 8 * 1024 * 1024, WIKI_AGENT));
                    if (jpg == null || faces(jpg) != 0) continue;
                    String[] parts = text.split("\\s*,\\s+");
                    // The last part says where only when it reads like a place name: "Namibia", not "with a stormy sea".
                    String last = parts.length > 1 ? parts[parts.length - 1].replaceAll("\\.$", "").trim() : "";
                    String where = last.matches("\\p{Lu}[\\p{L} '-]{2,29}") ? last : "Wikimedia Commons";
                    String credit = (artist.isEmpty() ? "" : "Photo: " + artist + ", ")
                            + (licence.isEmpty() ? "" : licence + ", ") + "Wikimedia Commons featured picture";
                    String about = text.length() > 220 ? text.substring(0, 220).replaceAll("\\s+\\S*$", "") + "\u2026" : text;
                    save(fresh, n++, jpg, shorten(subject(name.replaceAll("\\s*[,(].*$", ""))), about + " " + credit, where);
                } catch (Exception e) {
                    Log.w(TAG, "Wikimedia featured picture skipped: " + e.getMessage());
                }
            }
        }
        return n;
    }

    /** A Commons extmetadata value as plain text, without its HTML. */
    private static String plain(JSONObject meta, String key) {
        JSONObject v = meta != null ? meta.optJSONObject(key) : null;
        String s = v != null ? v.optString("value", "") : "";
        return android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * The Smithsonian American Art Museum's landscape paintings in oil, from its Open Access collection
     * (CC0): a random page of the collection each week, wide paintings only.
     */
    private static int fetchSmithsonian(File fresh) throws Exception {
        String base = "https://api.si.edu/openaccess/api/v1.0/search?api_key=" + SMITHSONIAN_KEY + "&rows=40&q="
                + URLEncoder.encode("unit_code:SAAM AND topic:Landscapes AND online_media_type:Images AND \"oil on canvas\"", "UTF-8");
        JSONObject first = new JSONObject(new String(get(base + "&start=0", 2 * 1024 * 1024), StandardCharsets.UTF_8))
                .getJSONObject("response");
        int total = first.optInt("rowCount", 40);
        int start = new java.util.Random().nextInt(Math.max(1, total - 40));
        JSONArray rows = new JSONObject(new String(get(base + "&start=" + start, 2 * 1024 * 1024), StandardCharsets.UTF_8))
                .getJSONObject("response").getJSONArray("rows");
        java.util.List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) list.add(rows.optJSONObject(i));
        Collections.shuffle(list);
        int n = 0;
        for (JSONObject row : list) {
            if (n >= 7) break;
            try {
                JSONObject content = row.getJSONObject("content");
                JSONArray media = content.getJSONObject("descriptiveNonRepeating").getJSONObject("online_media").getJSONArray("media");
                JSONObject m = media.getJSONObject(0);
                JSONObject usage = m.optJSONObject("usage");
                if (usage == null || !"CC0".equals(usage.optString("access"))) continue;
                JSONArray res = m.optJSONArray("resources");
                JSONObject size = res != null ? res.optJSONObject(0) : null;
                if (size == null || size.optInt("width") < size.optInt("height") * 1.2) continue;
                String title = row.optString("title", "").trim();
                if (title.isEmpty() || PEOPLE.matcher(title).find()) continue;
                byte[] jpg = fit(get(m.getString("content") + "&max=" + MAX_WIDTH, 6 * 1024 * 1024));
                if (jpg == null || faces(jpg) != 0) continue;
                JSONObject free = content.optJSONObject("freetext");
                String artist = first(free, "name").replaceAll(",\\s*(born|active|died).*$", "");
                String date = first(free, "date");
                String text = (artist.isEmpty() ? "" : artist) + (date.isEmpty() || date.equals("n.d.") ? "" : ", " + date)
                        + ". Oil on canvas. Smithsonian American Art Museum, Open Access (CC0).";
                save(fresh, n++, jpg, shorten(title.replaceAll("^\\((.*)\\)$", "$1")), text, "Smithsonian American Art");
            } catch (Exception e) {
                Log.w(TAG, "Smithsonian painting skipped: " + e.getMessage());
            }
        }
        return n;
    }

    private static String first(JSONObject free, String key) {
        JSONArray a = free != null ? free.optJSONArray(key) : null;
        JSONObject o = a != null ? a.optJSONObject(0) : null;
        return o != null ? o.optString("content", "").trim() : "";
    }

    /** NASA's Image of the Day feed: the latest pictures, JPEGs of a sensible size only. */
    private static int fetchNasa(File fresh) throws Exception {
        String rss = new String(get("https://www.nasa.gov/feeds/iotd-feed/", 1024 * 1024), StandardCharsets.UTF_8);
        java.util.regex.Matcher item = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL).matcher(rss);
        int n = 0;
        while (item.find() && n < 7) {
            String it = item.group(1);
            String title = tag(it, "title"), text = tag(it, "description");
            java.util.regex.Matcher enc = Pattern.compile("<enclosure url=\"([^\"]+)\" length=\"(\\d+)\" type=\"([^\"]+)\"").matcher(it);
            if (title.isEmpty() || !enc.find()) continue;
            // Some are full-resolution PNGs of hundreds of megabytes: JPEGs up to 12 MB only.
            if (!enc.group(3).equals("image/jpeg") || Long.parseLong(enc.group(2)) > 12L * 1024 * 1024) continue;
            if (PEOPLE.matcher(title + " " + text).find()) {
                Log.i(TAG, "skipped, names people: " + title);
                continue;
            }
            try {
                byte[] jpg = fit(get(enc.group(1), 13 * 1024 * 1024));
                if (jpg == null || faces(jpg) != 0) continue;
                save(fresh, n++, jpg, shorten(title), text, "NASA Image of the Day");
            } catch (Exception e) {
                Log.w(TAG, "NASA picture skipped: " + e.getMessage());
            }
        }
        return n;
    }

    /**
     * Unsplash: landscape photos on the owner's topics (unsplash_query), with the photographer credited
     * as Unsplash asks. Needs the owner's own Access Key (typed on the TV, or unsplash_key in config.json).
     */
    private static int fetchUnsplash(File fresh, String key, String query) throws Exception {
        if (!validKey(key)) return 0;
        String auth = "Client-ID " + key;
        String url = "https://api.unsplash.com/photos/random?count=12&orientation=landscape&content_filter=high&query="
                + URLEncoder.encode(query, "UTF-8");
        JSONArray photos = new JSONArray(new String(get(url, 1024 * 1024, null, auth), StandardCharsets.UTF_8));
        int n = 0;
        for (int i = 0; i < photos.length() && n < 7; i++) {
            JSONObject p = photos.optJSONObject(i);
            if (p == null) continue;
            JSONObject urls = p.optJSONObject("urls"), user = p.optJSONObject("user"), links = p.optJSONObject("links");
            JSONObject loc = p.optJSONObject("location");
            String raw = urls != null ? urls.optString("raw", "") : "";
            String alt = p.optString("alt_description", "").trim(), desc = p.optString("description", "").trim();
            if (raw.isEmpty() || "null".equals(alt)) continue;
            if ("null".equals(desc)) desc = "";
            String about = desc.isEmpty() ? alt : desc;
            if (PEOPLE.matcher(alt + " " + desc).find()) {
                Log.i(TAG, "skipped, names people: " + about);
                continue;
            }
            try {
                // Unsplash's image server sizes it for the screen; image requests do not count against the key.
                byte[] jpg = fit(get(raw + (raw.contains("?") ? "&" : "?") + "w=" + MAX_WIDTH + "&q=85&fm=jpg&fit=max",
                        6 * 1024 * 1024));
                if (jpg == null || faces(jpg) != 0) continue;
                // Unsplash asks that each use of a photo be reported to its download endpoint.
                String track = links != null ? links.optString("download_location", "") : "";
                if (!track.isEmpty()) get(track, 64 * 1024, null, auth);
                String place = loc != null ? loc.optString("name", "") : "";
                String country = loc != null ? loc.optString("country", "") : "";
                if ("null".equals(place)) place = "";
                if ("null".equals(country)) country = "";
                String name = shorten(subject(about.replaceAll("\\s*[,.;].*$", "")));
                String credit = user != null && !user.optString("name", "").isEmpty()
                        ? "Photo by " + user.optString("name").trim() + " on Unsplash" : "Unsplash";
                String text = (place.isEmpty() ? "" : place + ". ") + credit;
                save(fresh, n++, jpg, name, text, country.isEmpty() ? "Unsplash" : country);
            } catch (Exception e) {
                Log.w(TAG, "Unsplash photo skipped: " + e.getMessage());
            }
        }
        return n;
    }

    /** The subject alone: "Desert elephant spraying sand on herself while..." is "Desert elephant". */
    private static String subject(String s) {
        java.util.regex.Matcher cut = Pattern.compile("\\s+(is|are|was|were|in|near|at|on|from|over|during|while|with|by|under|"
                + "hunting|spraying|flying|sitting|standing|eating|feeding|seen|covered|surrounded)\\s+",
                Pattern.CASE_INSENSITIVE).matcher(s);
        return cut.find() && cut.start() > 2 ? s.substring(0, cut.start()) : s;
    }

    private static String tag(String xml, String name) {
        java.util.regex.Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        if (!m.find()) return "";
        String v = m.group(1).replaceAll("^<!\\[CDATA\\[|\\]\\]>$", "");
        return android.text.Html.fromHtml(v, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    /** A name for the shelf: one line, cut at a word. */
    private static String shorten(String s) {
        s = s.trim();
        if (s.length() <= 48) return s;
        int cut = s.lastIndexOf(' ', 46);
        return s.substring(0, cut > 20 ? cut : 46) + "\u2026";
    }

    /** Writes the picture and its three lines (name, description, where), as the Bing pictures are kept. */
    private static void save(File fresh, int n, byte[] jpg, String name, String text, String where) throws Exception {
        String base = String.format(Locale.ROOT, "%02d", n);
        write(new File(fresh, base + ".jpg"), jpg);
        write(new File(fresh, base + ".txt"), (name.replace('\n', ' ') + "\n" + text.replace('\n', ' ') + "\n"
                + where.replace('\n', ' ')).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The picture as a JPEG no wider than the screen, or null when it cannot be read. A large one is
     * decoded at a fraction of its size first, so a 6000-pixel photo never has to fit in memory whole.
     */
    private static byte[] fit(byte[] jpg) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, bounds);
        if (bounds.outWidth <= 0) return null;
        if (bounds.outWidth <= MAX_WIDTH) return jpg;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= MAX_WIDTH) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (b == null) return null;
        Bitmap scaled = Bitmap.createScaledBitmap(b, MAX_WIDTH, Math.round(b.getHeight() * (MAX_WIDTH / (float) b.getWidth())), true);
        if (scaled != b) b.recycle();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            scaled.recycle();
        }
    }

    private static final Pattern LEAD = Pattern.compile(
            "^((aerial|panoramic|close-up|night|winter|autumn|summer|spring)\\s+)?(views?|replicas?|life-size replica)\\s+of\\s+(the\\s+)?|^(the|a|an)\\s+",
            Pattern.CASE_INSENSITIVE);
    private static final java.util.Set<String> SMALL = new java.util.HashSet<>(java.util.Arrays.asList(
            "a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "by", "with", "de", "del", "la", "le", "du"));
    private static final java.util.Set<String> US_STATES = new java.util.HashSet<>(java.util.Arrays.asList(
            "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut", "Delaware", "Florida",
            "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas", "Kentucky", "Louisiana", "Maine",
            "Maryland", "Massachusetts", "Michigan", "Minnesota", "Mississippi", "Missouri", "Montana", "Nebraska",
            "Nevada", "New Hampshire", "New Jersey", "New Mexico", "New York", "North Carolina", "North Dakota", "Ohio",
            "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island", "South Carolina", "South Dakota", "Tennessee", "Texas",
            "Utah", "Vermont", "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming"));

    /**
     * Splits Bing's caption ("Mount Sneffels in the San Juan Mountains near Ridgway, Colorado") into
     * the thing's name ("Mount Sneffels") and its country ("United States"), the country empty
     * when the caption names none ("Ammonite fossils from the Jurassic Period").
     */
    static String[] nameAndCountry(String caption) {
        String c = caption.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s+", " ").trim();
        int comma = c.indexOf(", ");
        String country = comma >= 0 ? c.substring(c.lastIndexOf(", ") + 2).trim() : "";
        if (US_STATES.contains(country)) country = "United States";
        // The name runs up to where the caption starts saying where or when.
        String name = comma >= 0 ? c.substring(0, comma) : c;
        java.util.regex.Matcher cut = Pattern.compile("\\s+(in|near|at|on|from|over|above|under|along|off|across)\\s+",
                Pattern.CASE_INSENSITIVE).matcher(name);
        if (cut.find() && cut.start() > 0) name = name.substring(0, cut.start());
        name = LEAD.matcher(name).replaceFirst("").trim();
        if (name.isEmpty()) name = c;
        return new String[]{titleCase(name), country};
    }

    /** Capitals for each word but the small ones, and for each part of a hyphenated word. */
    private static String titleCase(String s) {
        StringBuilder out = new StringBuilder();
        String[] words = s.split(" ");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (i > 0) out.append(' ');
            if (i > 0 && SMALL.contains(w.toLowerCase(Locale.ROOT))) {
                out.append(w.toLowerCase(Locale.ROOT));
                continue;
            }
            String[] parts = w.split("-", -1);
            for (int j = 0; j < parts.length; j++) {
                if (j > 0) out.append('-');
                String p = parts[j];
                out.append(p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1));
            }
        }
        return out.toString();
    }

    /**
     * How many faces can be seen in the picture, or -1 when it cannot be read. Android's own
     * detector, on a half-size copy: no download, no model, and run only when the week's pictures
     * arrive.
     */
    private static int faces(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // what the detector reads
        o.inSampleSize = 2;
        Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (b == null) return -1;
        try {
            if (b.getWidth() % 2 != 0) {   // the detector needs an even width
                Bitmap even = Bitmap.createBitmap(b, 0, 0, b.getWidth() - 1, b.getHeight());
                b.recycle();
                b = even;
            }
            FaceDetector.Face[] faces = new FaceDetector.Face[4];
            return new FaceDetector(b.getWidth(), b.getHeight(), faces.length).findFaces(b, faces);
        } finally {
            b.recycle();
        }
    }

    /**
     * The thing's name, its description and its country for a picture saved here, or null when
     * there is none: one line each in the matching .txt.
     */
    static String[] describe(File picture) {
        if (picture == null) return null;
        String name = picture.getName();
        if (!name.endsWith(".jpg")) return null;
        File text = new File(picture.getParentFile(), name.substring(0, name.length() - 4) + ".txt");
        if (!text.isFile() || text.length() == 0 || text.length() > 8192) return null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(text), StandardCharsets.UTF_8))) {
            String title = r.readLine();
            if (title == null || title.trim().isEmpty()) return null;
            String overview = r.readLine(), country = r.readLine();
            return new String[]{title.trim(), overview == null ? "" : overview.trim(),
                    country == null ? "" : country.trim()};
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File out, byte[] data) throws Exception {
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(data);
        }
    }

    private static void deleteAll(File d) {
        File[] files = d.listFiles();
        if (files != null) for (File f : files) f.delete();
        d.delete();
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        // Asked as Android's own HTTP client, Bing answers with today's picture only; as a browser,
        // with the whole week.
        return get(url, maxBytes, "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
    }

    /** Wikimedia asks every client to name itself instead of posing as a browser. */
    private static byte[] get(String url, int maxBytes, String agent) throws Exception {
        return get(url, maxBytes, agent, null);
    }

    /** With {@code auth}, sent as the Authorization header (Unsplash's Client-ID). */
    private static byte[] get(String url, int maxBytes, String agent, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json, application/rss+xml, image/jpeg");
        if (agent != null) c.setRequestProperty("User-Agent", agent);
        if (auth != null) {
            c.setRequestProperty("Authorization", auth);
            c.setRequestProperty("Accept-Version", "v1");
        }
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("place_wallpapers", Context.MODE_PRIVATE);
    }
}
