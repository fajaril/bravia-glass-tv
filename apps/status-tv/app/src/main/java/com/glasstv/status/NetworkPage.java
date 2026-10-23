package com.glasstv.status;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Network tab: checks the link, the router, the internet line, DNS, the download speed and the
 * streaming services one after another (about 20 seconds), then says in plain words where a lag most
 * likely comes from. It goes online only while the check runs, on one background thread.
 */
final class NetworkPage extends LinearLayout {
    private static final int PENDING = 0, GOOD = 1, FAIR = 2, POOR = 3;
    private static final Pattern LOSS = Pattern.compile("([\\d.]+)% packet loss");
    private static final Pattern RTT = Pattern.compile("= [\\d.]+/([\\d.]+)/[\\d.]+/([\\d.]+) ms");
    private static final String[][] SERVICES = {
            {"YouTube", "https://www.youtube.com/generate_204"},
            {"Netflix", "https://www.netflix.com/"},
            {"Disney+", "https://www.disneyplus.com/"},
            {"Google", "https://www.google.com/generate_204"}};

    private final MainActivity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView verdictDot, verdict, advice, note;
    final TextView button;
    private final Row link, router, internet, dns, download, services;
    private volatile int run;
    private boolean ran;

    // Results of the current run, read by the verdict at the end.
    private int linkS, routerS, internetS, dnsS, downloadS, servicesS;
    private double mbps;

    NetworkPage(MainActivity a) {
        super(a);
        this.a = a;
        setOrientation(VERTICAL);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(a.dp(22), a.dp(14), a.dp(22), a.dp(14));
        card.setBackground(a.glass());
        verdictDot = a.text("●", 30, 0x66FFFFFF, 400);
        card.addView(verdictDot);
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(VERTICAL);
        words.setPadding(a.dp(16), 0, 0, 0);
        verdict = a.text("", 22, 0xFFFFFFFF, 500);
        advice = a.text("", 14, MainActivity.MUTE, 400);
        advice.setLineSpacing(a.dp(2), 1f);
        words.addView(verdict);
        words.addView(advice);
        card.addView(words, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(card);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(VERTICAL);
        box.setPadding(a.dp(22), a.dp(10), a.dp(22), a.dp(10));
        box.setBackground(a.glass());
        link = new Row(box, "Connection");
        router = new Row(box, "Router");
        internet = new Row(box, "Internet");
        dns = new Row(box, "DNS");
        download = new Row(box, "Download");
        services = new Row(box, "Streaming");
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = a.dp(14);
        addView(box, blp);

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, a.dp(16), 0, 0);
        button = a.text("Check again", 18, 0xFF000000, 600);
        button.setPadding(a.dp(26), a.dp(12), a.dp(26), a.dp(12));
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(a.buttonBackground());
        button.setOnClickListener(v -> start());
        bottom.addView(button);
        note = a.text("", 14, MainActivity.MUTE, 400);
        note.setPadding(a.dp(18), 0, 0, 0);
        bottom.addView(note, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(bottom);
    }

    /** The first time the tab is shown: check straight away. */
    void runOnce() {
        if (!ran) start();
    }

    /** Stops a check in progress (the app is being left): its results are dropped. */
    void cancel() {
        run++;
        ran = false;
    }

    private void start() {
        ran = true;
        final int me = ++run;
        for (Row r : new Row[]{link, router, internet, dns, download, services}) r.set(PENDING, "Checking…", "");
        verdictDot.setTextColor(0x66FFFFFF);
        verdict.setText("Checking the network…");
        advice.setText("About 20 seconds. Router, internet line, DNS, download speed, then the streaming services.");
        note.setText("");
        Thread t = new Thread(() -> check(me), "network-check");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    private boolean live(int me) {
        return me == run;
    }

    private void post(int me, Runnable r) {
        ui.post(() -> {
            if (live(me)) r.run();
        });
    }

    // ---- The checks, on the background thread ----

    private void check(int me) {
        ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        boolean ethernet = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);

        // Connection: which link, whether Android reached the internet over it, and a second link if any.
        String linkText = a.network();
        String extra = "";
        linkS = n == null ? POOR : caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? GOOD : FAIR;
        if (n == null) extra = "No network";
        else if (linkS == FAIR) extra = "No internet reached";
        String wifi = wifiDetails();
        if (ethernet && wifi != null) linkText += "   (Wi-Fi also on: " + wifi + ")";
        else if (!ethernet && wifi != null) linkText += "  ·  " + wifi;
        if (!ethernet && caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            int rssi = wifiRssi();
            if (rssi != 0 && rssi < -70) {
                linkS = Math.max(linkS, FAIR);
                extra = "Weak Wi-Fi";
            }
        }
        final String lt = linkText, le = extra.isEmpty() ? label(linkS) : extra;
        post(me, () -> link.set(linkS, lt, le));
        if (n == null) {
            routerS = internetS = dnsS = downloadS = servicesS = POOR;
            post(me, () -> {
                for (Row r : new Row[]{router, internet, dns, download, services}) r.set(POOR, "Not checked: no network", "");
                finish();
            });
            return;
        }

        // Router: the home network itself (cable or Wi-Fi to the router).
        String gw = gateway(lp);
        if (gw == null) {
            routerS = FAIR;
            post(me, () -> router.set(FAIR, "No router address found", "Unknown"));
        } else {
            double[] p = ping(gw, 10, 80);
            routerS = p == null ? FAIR : p[2] > 0 ? POOR : p[0] > 20 || p[1] > 15 ? POOR : p[0] > 6 || p[1] > 5 ? FAIR : GOOD;
            final String t = p == null ? gw + "  ·  does not answer pings" : gw + "  ·  " + pingText(p);
            final int s = routerS;
            post(me, () -> router.set(s, t, p == null ? "Unknown" : label(s)));
        }
        if (!live(me)) return;

        // Internet: a steady line matters more than a fast one for video (jitter and loss cause pauses).
        double[] p = ping("8.8.8.8", 20, 53);
        internetS = p == null ? POOR : p[2] >= 3 || p[0] > 150 || p[1] > 40 ? POOR : p[2] > 0 || p[0] > 60 || p[1] > 15 ? FAIR : GOOD;
        final String it = p == null ? "Google DNS (8.8.8.8) not reached" : "To Google (8.8.8.8)  ·  " + pingText(p);
        final int is = internetS;
        post(me, () -> internet.set(is, it, label(is)));
        if (!live(me)) return;

        // DNS: how long names take to look up (slow DNS = apps slow to start videos).
        String[] names = {"www.youtube.com", "www.netflix.com", "api.themoviedb.org", "speed.cloudflare.com"};
        long worst = 0, sum = 0;
        int ok = 0;
        for (String name : names) {
            long t0 = System.nanoTime();
            try {
                InetAddress.getAllByName(name);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                worst = Math.max(worst, ms);
                sum += ms;
                ok++;
            } catch (Exception ignored) {
            }
        }
        dnsS = ok == 0 ? POOR : ok < names.length || worst > 400 ? POOR : worst > 150 ? FAIR : GOOD;
        final String dt = ok == 0 ? "Names could not be looked up" : "Average " + (sum / ok) + " ms, slowest " + worst + " ms"
                + (ok < names.length ? "  ·  " + (names.length - ok) + " failed" : "");
        final int ds = dnsS;
        post(me, () -> dns.set(ds, dt, label(ds)));
        if (!live(me)) return;

        // Download: four streams from Cloudflare for 8 seconds, the first second left out (ramp-up).
        mbps = downloadSpeed(me);
        downloadS = mbps <= 0 ? POOR : mbps >= 25 ? GOOD : mbps >= 8 ? FAIR : POOR;
        String use = mbps >= 25 ? "enough for 4K" : mbps >= 8 ? "enough for HD, not 4K" : mbps > 0 ? "may buffer even in HD" : "test failed";
        String cap = ethernet && mbps >= 85 && mbps <= 100 ? "  ·  near this TV's 100 Mbps Ethernet limit" : "";
        final String wt = mbps <= 0 ? "Could not download" : String.format(Locale.ROOT, "%.0f Mbps  ·  %s%s", mbps, use, cap);
        final int ws = downloadS;
        post(me, () -> download.set(ws, wt, label(ws)));
        if (!live(me)) return;

        // Streaming services: time to the first answer from each (a secure connection included).
        StringBuilder st = new StringBuilder();
        int slow = 0, failed = 0;
        for (String[] s : SERVICES) {
            long ms = firstAnswer(s[1]);
            if (st.length() > 0) st.append("  ·  ");
            if (ms < 0) {
                st.append(s[0]).append(" failed");
                failed++;
            } else {
                st.append(s[0]).append(' ').append(ms).append(" ms");
                if (ms > 800) slow++;
            }
            if (!live(me)) return;
        }
        servicesS = failed > 0 ? POOR : slow > 0 ? FAIR : GOOD;
        final String sv = st.toString();
        final int ss = servicesS;
        post(me, () -> {
            services.set(ss, sv, label(ss));
            finish();
        });
    }

    /** The verdict: the first part that is poor (or fair) is the likely cause, nearest the TV first. */
    private void finish() {
        String title, words;
        int worst = Math.max(Math.max(Math.max(linkS, routerS), Math.max(internetS, dnsS)), Math.max(downloadS, servicesS));
        if (linkS == POOR) {
            title = "Not connected";
            words = "The TV has no network. Check the cable or Wi-Fi in Settings → Network & Internet.";
        } else if (routerS == POOR) {
            title = "Problem inside the home network";
            words = "The TV to the router is slow or losing data. Check the cable and router; restart the router.";
        } else if (internetS == POOR) {
            title = "The internet line is unstable";
            words = "Delays or lost data between the router and the internet: video pauses or drops quality. "
                    + "If it stays like this, it is the provider's line.";
        } else if (downloadS == POOR) {
            title = "The internet is slow right now";
            words = "Video may buffer. Other devices downloading at home, or the provider, can cause this.";
        } else if (dnsS == POOR) {
            title = "Name lookups are slow";
            words = "Apps take long to start a video. Setting the router's DNS to 1.1.1.1 or 8.8.8.8 usually helps.";
        } else if (servicesS == POOR) {
            title = "A streaming service did not answer";
            words = "The network is fine; that service may be down or blocked right now.";
        } else if (worst == FAIR) {
            title = "The network is OK";
            words = "Some parts are only fair (marked yellow). HD plays well; 4K may drop quality at times.";
        } else {
            title = "The network is good";
            words = "Fast and steady, enough for 4K. If video still lags, the cause is the app or the TV itself: "
                    + "try Free up memory on Overview, or restart the app.";
        }
        verdictDot.setTextColor(colour(worst == PENDING ? GOOD : worst));
        verdict.setText(title);
        advice.setText(words);
        note.setText("Checked at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date())
                + ". Run it again while a video lags to see what changes.");
    }

    /** {average ms, jitter ms, loss %} from the system ping, or from timed connections if ping is not allowed. */
    private static double[] ping(String host, int count, int tcpPort) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/ping", "-n", "-c", String.valueOf(count), "-i", "0.2", "-W", "1", host)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor(count + 5, TimeUnit.SECONDS);
            Matcher loss = LOSS.matcher(out), rtt = RTT.matcher(out);
            if (loss.find()) {
                double l = Double.parseDouble(loss.group(1));
                if (rtt.find()) return new double[]{Double.parseDouble(rtt.group(1)), Double.parseDouble(rtt.group(2)), l};
                if (l >= 100) return tcpPing(host, count, tcpPort);
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return tcpPing(host, count, tcpPort);
    }

    /** The same figures from timed TCP connections, for when ICMP ping is not allowed or not answered. */
    private static double[] tcpPing(String host, int count, int port) {
        double sum = 0, sumSq = 0;
        int ok = 0;
        for (int i = 0; i < count; i++) {
            long t0 = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 1000);
                double ms = (System.nanoTime() - t0) / 1e6;
                sum += ms;
                sumSq += ms * ms;
                ok++;
            } catch (Exception ignored) {
            }
        }
        if (ok == 0) return null;
        double avg = sum / ok;
        return new double[]{avg, Math.sqrt(Math.max(0, sumSq / ok - avg * avg)), 100.0 * (count - ok) / count};
    }

    private static String pingText(double[] p) {
        return String.format(Locale.ROOT, "%.0f ms  ·  jitter %.0f ms  ·  %s lost", p[0], p[1],
                p[2] == 0 ? "nothing" : String.format(Locale.ROOT, "%.0f%%", p[2]));
    }

    private double downloadSpeed(int me) {
        final AtomicLong bytes = new AtomicLong();
        final long start = System.nanoTime(), end = start + 8_000_000_000L;
        Thread[] streams = new Thread[4];
        for (int i = 0; i < streams.length; i++) {
            streams[i] = new Thread(() -> {
                byte[] buf = new byte[32 * 1024];
                while (System.nanoTime() < end && live(me)) {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=50000000").openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(4000);
                        try (InputStream in = c.getInputStream()) {
                            int r;
                            while ((r = in.read(buf)) > 0) {
                                bytes.addAndGet(r);
                                if (System.nanoTime() >= end || !live(me)) break;
                            }
                        }
                    } catch (Exception e) {
                        return;
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            });
            streams[i].start();
        }
        long atOneSecond = -1;
        try {
            Thread.sleep(1000);
            atOneSecond = bytes.get();
            for (Thread t : streams) t.join(9000);
        } catch (InterruptedException ignored) {
        }
        double seconds = (System.nanoTime() - start) / 1e9 - 1;
        long counted = bytes.get() - Math.max(0, atOneSecond);
        return seconds <= 0.5 || counted <= 0 ? 0 : counted * 8 / seconds / 1e6;
    }

    /** Milliseconds to the first answer from {@code url} (any HTTP status counts), or -1. */
    private static long firstAnswer(String url) {
        HttpURLConnection c = null;
        long t0 = System.nanoTime();
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.getResponseCode();
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String gateway(LinkProperties lp) {
        if (lp == null) return null;
        for (RouteInfo r : lp.getRoutes()) {
            if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) return r.getGateway().getHostAddress();
        }
        return null;
    }

    /**
     * "2.4 GHz, -50 dBm strong" when Wi-Fi is connected, else null. Read from the Wi-Fi network's own
     * details, which (unlike WifiManager) need no location permission; the TV can be on Wi-Fi and Ethernet at once.
     */
    private String wifiDetails() {
        WifiInfo w = wifiInfo();
        if (w == null) return null;
        int rssi = w.getRssi();
        String band = w.getFrequency() >= 5900 ? "6 GHz" : w.getFrequency() >= 4900 ? "5 GHz" : "2.4 GHz";
        String strength = rssi >= -55 ? "strong" : rssi >= -67 ? "good" : rssi >= -75 ? "weak" : "very weak";
        return band + ", " + rssi + " dBm " + strength;
    }

    private int wifiRssi() {
        WifiInfo w = wifiInfo();
        return w == null ? 0 : w.getRssi();
    }

    private WifiInfo wifiInfo() {
        if (android.os.Build.VERSION.SDK_INT < 29) return null;   // transport details arrived in Android 10
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.getTransportInfo() instanceof WifiInfo) {
                    return (WifiInfo) c.getTransportInfo();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String label(int s) {
        return s == GOOD ? "Good" : s == FAIR ? "Fair" : s == POOR ? "Poor" : "";
    }

    private static int colour(int s) {
        return s == GOOD ? MainActivity.GREEN : s == FAIR ? MainActivity.YELLOW : s == POOR ? MainActivity.RED : 0x66FFFFFF;
    }

    /** One line of the list: a coloured dot, the part's name, what was measured and Good / Fair / Poor. */
    private final class Row {
        final TextView dot, value, mark;

        Row(LinearLayout box, String name) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, a.dp(5), 0, a.dp(5));
            dot = a.text("●", 14, 0x66FFFFFF, 400);
            r.addView(dot, new LayoutParams(a.dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(a.text(name, 15, MainActivity.MUTE, 500), new LayoutParams(a.dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
            value = a.text("", 15, 0xE6FFFFFF, 400);
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(value, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mark = a.text("", 14, 0xFFFFFFFF, 600);
            mark.setGravity(Gravity.END);
            r.addView(mark, new LayoutParams(a.dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(r);
        }

        void set(int s, String v, String m) {
            dot.setTextColor(colour(s));
            value.setText(v);
            mark.setText(m);
            mark.setTextColor(colour(s));
        }
    }
}
