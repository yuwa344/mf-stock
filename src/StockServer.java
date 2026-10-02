import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Zero-dependency HTTP server exposing search / watchlist / kline detail / full-market scan. */
public final class StockServer {

    private static Map<String, Map<String, Object>> stockByCode;

    public static void main(String[] args) throws Exception {
        int port;
        try {
            port = Integer.parseInt(System.getenv().getOrDefault("PORT", "0"));
        } catch (NumberFormatException e) { port = 0; }
        if (port <= 0) port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        System.out.println("MF-Stock multi-factor analyzer starting ...");
        System.out.println("Loading universe (cached on first use) ...");
        warmup();
        Scanner.loadPersisted();

        HttpServer srv = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        srv.createContext("/", StockServer::route);
        srv.setExecutor(Executors.newFixedThreadPool(32));
        srv.start();

        System.out.println("Serving on port " + port);
        for (String ip : lanIps()) {
            System.out.println("  -> http://" + ip + ":" + port + "   (phone/iOS: open in Safari)");
        }
    }

    private static void warmup() {
        try {
            universe();
        } catch (Exception e) {
            System.out.println("Universe warmup failed (will retry on first request): " + e);
        }
    }

    private static void universe() throws Exception {
        List<Map<String, Object>> u = Api.universe();
        Map<String, Map<String, Object>> m = new HashMap<>();
        for (Map<String, Object> s : u) m.put(Json.str(s.get("code")), s);
        synchronized (StockServer.class) { stockByCode = m; }
    }

    private static List<String> lanIps() {
        List<String> out = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces(); it.hasMoreElements(); ) {
                NetworkInterface ni = it.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<java.net.InetAddress> ad = ni.getInetAddresses(); ad.hasMoreElements(); ) {
                    java.net.InetAddress a = ad.nextElement();
                    if (a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignore) { }
        if (out.isEmpty()) out.add("127.0.0.1");
        return out;
    }

    // ---------------- routing ----------------
    private static void route(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String q = x.getRequestURI().getQuery();
        Map<String, String> p = queryMap(q);
        try {
            if (path.equals("/")) serveIndex(x);
            else if (path.equals("/api/icon")) serveIcon(x);
            else if (path.equals("/api/profile")) serveProfile(x);
            else if (path.equals("/api/search")) apiSearch(x, p.getOrDefault("q", ""));
            else if (path.equals("/api/watchlist") && x.getRequestMethod().equalsIgnoreCase("GET")) apiWatchlistGet(x);
            else if (path.equals("/api/watchlist") && x.getRequestMethod().equalsIgnoreCase("POST")) apiWatchlistPost(x);
            else if (path.equals("/api/detail")) apiDetail(x, p.get("code"));
            else if (path.equals("/api/scan") && x.getRequestMethod().equalsIgnoreCase("POST")) apiScanStart(x);
            else if (path.equals("/api/scan")) apiScanStatus(x);
            else send(x, 404, Json.write(Map.of("error", "not found")));
        } catch (Exception e) {
            e.printStackTrace();
            send(x, 500, Json.write(Map.of("error", String.valueOf(e))));
        } finally {
            x.close();
        }
    }

    private static Map<String, String> queryMap(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(urlDecode(kv.substring(0, i)), urlDecode(kv.substring(i + 1)));
        }
        return m;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void serveIndex(HttpExchange x) throws IOException {
        Path f = Paths.get("web", "index.html");
        if (!Files.exists(f)) { send(x, 500, "index.html missing"); return; }
        byte[] b = Files.readAllBytes(f);
        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    // ---------------- iOS profile (WebClip) ----------------
    private static byte[] iconPng() {
        try {
            int size = 180;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    size, size, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(0x0B0F1A),
                    size, size, new java.awt.Color(0x1E2A4A)));
            g.fillRect(0, 0, size, size);
            // candlesticks: red up / green down (A-share convention)
            int[][] candles = {{28, 96, 20, 34}, {58, 74, 22, -18}, {88, 62, 22, 24},
                    {118, 44, 22, 14}, {148, 30, 22, -8}};
            for (int[] c : candles) {
                boolean up = c[3] >= 0;
                g.setColor(up ? new java.awt.Color(0xFF5C5C) : new java.awt.Color(0x4ADE80));
                int w = c[2], top = c[1], bodyH = Math.abs(c[3]) + 16;
                g.fillRect(c[0] + w / 2 - 2, top - 14, 4, bodyH + 28);   // wick
                g.fillRect(c[0], top, w, bodyH);                          // body
            }
            g.dispose();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void serveIcon(HttpExchange x) throws IOException {
        byte[] b = iconPng();
        x.getResponseHeaders().set("Content-Type", "image/png");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    private static void serveProfile(HttpExchange x) throws IOException {
        String host = x.getRequestHeaders().getFirst("Host");
        if (host == null || host.isEmpty()) host = "127.0.0.1:8080";
        String proto = x.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if (proto == null || proto.isEmpty()) proto = "http";
        String url = proto + "://" + host + "/";
        String name = "多因子股票分析";
        String icon64 = Base64.getEncoder().encodeToString(iconPng());
        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n<dict>\n"
                + "  <key>PayloadDisplayName</key><string>" + name + "</string>\n"
                + "  <key>PayloadDescription</key><string>安装「" + name + "」Web App 到主屏幕（内网行情分析工具）</string>\n"
                + "  <key>PayloadIdentifier</key><string>com.huang.mfstock.webclip</string>\n"
                + "  <key>PayloadOrganization</key><string>Huang</string>\n"
                + "  <key>PayloadRemovalDisallowed</key><false/>\n"
                + "  <key>PayloadType</key><string>Configuration</string>\n"
                + "  <key>PayloadUUID</key><string>7E2A1B4C-9F3D-4A56-B8C1-2D5E8F0A3B61</string>\n"
                + "  <key>PayloadVersion</key><integer>1</integer>\n"
                + "  <key>PayloadContent</key>\n  <array>\n   <dict>\n"
                + "    <key>PayloadType</key><string>com.apple.webclip</string>\n"
                + "    <key>PayloadIdentifier</key><string>com.huang.mfstock.webclip.clip</string>\n"
                + "    <key>PayloadUUID</key><string>3C8D5F2E-1A6B-4C7D-9E0F-5A2B8C4D7E91</string>\n"
                + "    <key>PayloadVersion</key><integer>1</integer>\n"
                + "    <key>PayloadDisplayName</key><string>" + name + "</string>\n"
                + "    <key>URL</key><string>" + url + "</string>\n"
                + "    <key>Label</key><string>" + name + "</string>\n"
                + "    <key>Icon</key><data>" + icon64 + "</data>\n"
                + "    <key>Precomposed</key><true/>\n"
                + "    <key>FullScreen</key><true/>\n"
                + "   </dict>\n  </array>\n</dict>\n</plist>\n";
        byte[] b = plist.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/x-apple-aspen-config");
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"mfstock.mobileconfig\"");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    // ---------------- handlers ----------------
    private static void apiSearch(HttpExchange x, String q) throws Exception {
        if (stockByCode == null) universe();
        String needle = q.trim().toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!needle.isEmpty()) {
            for (Map<String, Object> s : Api.universe()) {
                String code = Json.str(s.get("code")), name = Json.str(s.get("name"));
                if (code.contains(needle) || (name != null && name.toLowerCase().contains(needle))) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("code", code);
                    r.put("name", name);
                    r.put("price", s.get("price"));
                    r.put("pct", s.get("pct"));
                    r.put("industry", s.get("industry"));
                    out.add(r);
                    if (out.size() >= 30) break;
                }
            }
        }
        send(x, 200, Json.write(out));
    }

    private static synchronized List<String> readWatchlist() {
        try {
            Path f = Paths.get("data", "watchlist.json");
            if (!Files.exists(f)) return new ArrayList<>();
            List<Object> l = Json.list(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
            List<String> out = new ArrayList<>();
            if (l != null) for (Object o : l) out.add(String.valueOf(o));
            return out;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private static synchronized void writeWatchlist(List<String> codes) {
        try {
            Path f = Paths.get("data", "watchlist.json");
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(codes), StandardCharsets.UTF_8);
        } catch (Exception ignore) { }
    }

    private static void apiWatchlistPost(HttpExchange x) throws Exception {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> m = Json.map(Json.parse(body));
        String action = Json.str(m.get("action"));
        String code = Json.str(m.get("code"));
        if (code != null) {
            List<String> wl = readWatchlist();
            if ("add".equals(action)) {
                if (!wl.contains(code)) wl.add(code);
            } else if ("remove".equals(action)) wl.remove(code);
            writeWatchlist(wl);
        }
        send(x, 200, Json.write(Map.of("ok", true)));
    }

    private static void apiWatchlistGet(HttpExchange x) throws Exception {
        if (stockByCode == null) universe();
        List<String> wl = readWatchlist();
        Map<String, Map<String, Object>> quotes = new HashMap<>();
        if (!wl.isEmpty()) {
            for (Map<String, Object> q : Api.quotes(wl)) quotes.put(Json.str(q.get("code")), q);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String code : wl) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", code);
            Map<String, Object> meta = stockByCode.get(code);
            Map<String, Object> qt = quotes.get(code);
            r.put("name", qt != null ? qt.get("name") : (meta != null ? meta.get("name") : code));
            r.put("price", qt != null ? qt.get("price") : (meta != null ? meta.get("price") : null));
            r.put("pct", qt != null ? qt.get("pct") : (meta != null ? meta.get("pct") : null));
            r.put("mktcap", qt != null ? qt.get("mktcap") : null);
            // factor stage if we can evaluate
            try {
                Map<String, Object> st = stockByCode.get(code);
                Api.K k = Api.kline(code, st != null ? Api.floatShares(st) : null);
                Map<String, Object> f = Factors.precompute(k, st != null ? st : new HashMap<>());
                applyScanVerdict(code, f);
                int stage = Factors.stage(f);
                r.put("stage", stage);
                r.put("f", compactFactors(f));
            } catch (Exception e) {
                r.put("stage", null);
            }
            out.add(r);
        }
        send(x, 200, Json.write(out));
    }

    /** If a scan exists, adopt its cross-sectional RS verdict for this code. */
    private static void applyScanVerdict(String code, Map<String, Object> f) {
        for (Map<String, Object> row : Scanner.state().results) {
            if (code.equals(Json.str(row.get("code")))) {
                Map<String, Object> rf = Json.map(row.get("f"));
                if (rf != null) {
                    f.put("f3_pass", rf.get("f3_pass"));
                    f.put("f3_pct", rf.get("f3_pct"));
                }
                return;
            }
        }
    }

    private static Map<String, Object> compactFactors(Map<String, Object> f) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (int i = 1; i <= 6; i++) c.put("f" + i, f.get("f" + i + "_pass"));
        c.put("rsScore", f.get("f3_score"));
        return c;
    }

    private static void apiDetail(HttpExchange x, String code) throws Exception {
        if (code == null || code.isEmpty()) { send(x, 400, Json.write(Map.of("error", "code required"))); return; }
        if (stockByCode == null) universe();
        Map<String, Object> meta = stockByCode.get(code);
        if (meta == null) { send(x, 404, Json.write(Map.of("error", "unknown code"))); return; }
        Api.K k = Api.kline(code, Api.floatShares(meta));
        Map<String, Object> f = Factors.precompute(k, meta);
        applyScanVerdict(code, f);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("factors", f);
        int n = k.n();
        Map<String, Object> kl = new LinkedHashMap<>();
        kl.put("dates", Arrays.asList(k.dates).subList(Math.max(0, n - 250), n));
        kl.put("o", box(k.b, 0, n)); kl.put("c", box(k.b, 1, n)); kl.put("h", box(k.b, 2, n));
        kl.put("l", box(k.b, 3, n)); kl.put("v", box(k.b, 4, n)); kl.put("turn", box(k.b, 9, n));
        kl.put("pct", box(k.b, 7, n));
        out.put("kline", kl);
        send(x, 200, Json.write(out));
    }

    private static List<Object> box(double[][] b, int col, int n) {
        int from = Math.max(0, n - 250);
        List<Object> out = new ArrayList<>(n - from);
        for (int i = from; i < n; i++) out.add(b[i][col]);
        return out;
    }

    private static void apiScanStart(HttpExchange x) throws Exception {
        boolean started = Scanner.start();
        send(x, 200, Json.write(Map.of("ok", started, "running", Scanner.state().running)));
    }

    private static void apiScanStatus(HttpExchange x) throws Exception {
        Scanner.State s = Scanner.state();
        List<Integer> counts = new ArrayList<>();
        for (int v : s.stageCounts) counts.add(v);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", s.running);
        out.put("total", s.total);
        out.put("done", s.done);
        out.put("phase", s.phase);
        out.put("error", s.error);
        out.put("finishedAt", s.finishedAt);
        out.put("counts", counts);
        out.put("results", s.results);
        send(x, 200, Json.write(out));
    }

    // ---------------- io ----------------
    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    private StockServer() { }
}
