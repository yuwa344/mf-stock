import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Eastmoney data layer: universe list, daily klines (disk-cached), batch quotes. */
public final class Api {
    private static final HttpClient HC = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private static final DateTimeFormatter DF = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String[] KHOSTS = {"", "91.", "92."};
    private static long nextKlineSlot = 0;

    /** Global rate limiter for kline requests (~4 req/s) to avoid Eastmoney throttling. */
    private static void throttleKline() {
        long slot;
        synchronized (Api.class) {
            long now = System.currentTimeMillis();
            slot = Math.max(nextKlineSlot, now);
            nextKlineSlot = slot + 250;
        }
        long wait = slot - System.currentTimeMillis();
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    static String get(String url) throws Exception {
        boolean kline = url.contains("push2his.");
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                String u = url;
                if (kline && i > 0) u = url.replace("https://push2his.", "https://" + KHOSTS[i] + "push2his.");
                if (kline) throttleKline();
                HttpRequest req = HttpRequest.newBuilder(URI.create(u))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36")
                        .header("Referer", "https://quote.eastmoney.com/")
                        .timeout(java.time.Duration.ofSeconds(15))
                        .GET().build();
                HttpResponse<String> r = HC.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return r.body();
                last = new IOException("HTTP " + r.statusCode() + " for " + u);
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(500L * (i + 1));
        }
        throw last;
    }

    // ---------------- universe ----------------
    private static volatile List<Map<String, Object>> universeCache;
    private static final Object UNI_LOCK = new Object();

    /** Full A-share universe (SH/SZ/BJ), disk-cached per day; falls back to bundled seed file. */
    public static List<Map<String, Object>> universe() throws Exception {
        List<Map<String, Object>> c = universeCache;
        if (c != null) return c;
        synchronized (UNI_LOCK) {
            if (universeCache != null) return universeCache;
            Path f = Paths.get("data", "universe.json");
            String today = LocalDate.now().format(DF);
            if (Files.exists(f)) {
                try {
                    Map<String, Object> saved = Json.map(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                    if (today.equals(Json.str(saved.get("date")))) {
                        List<Map<String, Object>> list = castList(saved.get("stocks"));
                        universeCache = list;
                        return list;
                    }
                } catch (Exception ignore) { }
            }
            try {
                List<Map<String, Object>> all = fetchUniverse();
                if (all.isEmpty()) throw new IOException("universe fetch returned empty");
                Map<String, Object> save = new LinkedHashMap<>();
                save.put("date", today);
                save.put("stocks", all);
                Files.createDirectories(f.getParent());
                Files.writeString(f, Json.write(save), StandardCharsets.UTF_8);
                universeCache = all;
                return all;
            } catch (Exception e) {
                // network blocked (e.g. datacenter IP) -> use bundled seed snapshot
                List<Map<String, Object>> seed = loadSeedUniverse();
                if (seed != null && !seed.isEmpty()) {
                    universeCache = seed;
                    return seed;
                }
                throw e;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadSeedUniverse() {
        try {
            Path sp = Paths.get("seed", "universe.json");
            if (!Files.exists(sp)) return null;
            Map<String, Object> saved = Json.map(Json.parse(Files.readString(sp, StandardCharsets.UTF_8)));
            List<Object> l = Json.list(saved.get("stocks"));
            if (l == null) return null;
            List<Map<String, Object>> out = new ArrayList<>(l.size());
            for (Object o : l) out.add((Map<String, Object>) o);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> fetchUniverse() throws Exception {
            List<Map<String, Object>> all = new ArrayList<>(6000);
            int pn = 1;
            while (pn <= 90) {
                String url = "https://push2.eastmoney.com/api/qt/clist/get?pn=" + pn + "&pz=100&po=1&np=1&fltt=2&invt=2&fid=f12"
                        + "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"
                        + "&fields=f2,f3,f6,f8,f9,f12,f14,f20,f21,f23,f37,f40,f41,f45,f46,f100,f115";
                Map<String, Object> root = Json.map(Json.parse(get(url)));
                Map<String, Object> data = Json.map(root == null ? null : root.get("data"));
                if (data == null) break;
                List<Object> diff = Json.list(data.get("diff"));
                if (diff == null || diff.isEmpty()) break;
                for (Object o : diff) {
                    Map<String, Object> m = Json.map(o);
                    if (m == null) continue;
                    String code = Json.str(m.get("f12"));
                    if (code == null) continue;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("code", code);
                    r.put("name", Json.str(m.get("f14")));
                    r.put("price", Json.dbl(m.get("f2")));
                    r.put("pct", Json.dbl(m.get("f3")));
                    r.put("amount", Json.dbl(m.get("f6")));
                    r.put("turnover", Json.dbl(m.get("f8")));
                    r.put("pe", Json.dbl(m.get("f115")));
                    r.put("pb", Json.dbl(m.get("f23")));
                    r.put("roe", Json.dbl(m.get("f37")));
                    r.put("rev", Json.dbl(m.get("f40")));          // revenue (latest report)
                    r.put("revYoy", Json.dbl(m.get("f41")));       // revenue yoy %
                    r.put("profit", Json.dbl(m.get("f45")));       // net profit (latest report)
                    r.put("profitYoy", Json.dbl(m.get("f46")));    // net profit yoy % (latest report)
                    r.put("mktcap", Json.dbl(m.get("f20")));
                    r.put("floatCap", Json.dbl(m.get("f21")));
                    r.put("industry", Json.str(m.get("f100")));
                    all.add(r);
                }
                if (diff.size() < 100) break;
                pn++;
            }
            if (all.isEmpty()) throw new IOException("universe fetch failed");
            return all;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        List<Object> l = Json.list(o);
        List<Map<String, Object>> out = new ArrayList<>();
        if (l != null) for (Object x : l) out.add((Map<String, Object>) x);
        return out;
    }

    // ---------------- kline ----------------
    public static final int BARS = 320; // enough for MA200(20d ago) + RS250

    public static class K {
        public String[] dates;
        // cols: 0 open,1 close,2 high,3 low,4 vol,5 amount,6 amp,7 pct,8 chg,9 turnover
        public double[][] b;
        public int n() { return dates.length; }
    }

    /** secid prefix: SH=1 (6xxxxx), SZ/BJ=0 (0/2/3/4/8/92) */
    public static String secid(String code) {
        return code.startsWith("6") ? "1." + code : "0." + code;
    }

    /** Latest expected trading day (weekends removed). */
    public static String lastTradingDay() {
        LocalDate d = LocalDate.now();
        DayOfWeek w = d.getDayOfWeek();
        if (w == DayOfWeek.SATURDAY) d = d.minusDays(1);
        else if (w == DayOfWeek.SUNDAY) d = d.minusDays(2);
        return d.format(DF);
    }

    public static K kline(String code) throws Exception {
        return kline(code, null);
    }

    /** Eastmoney kline with Tencent qfq fallback + circuit breaker for blocked hosts. */
    /** Per-source circuit breaker: after 5 consecutive failures skip source for 10 min. */
    private static class Breaker {
        volatile long blockedUntil = 0;
        int fails = 0;
        synchronized boolean open() { return System.currentTimeMillis() < blockedUntil; }
        synchronized void fail() {
            if (++fails >= 5) { blockedUntil = System.currentTimeMillis() + 10 * 60_000L; fails = 0; }
        }
        synchronized void ok() { fails = 0; }
    }

    private static final Breaker EAST_BR = new Breaker(), TX_BR = new Breaker();

    public static K kline(String code, Double floatShares) throws Exception {
        Path f = Paths.get("data", "klines", code + ".csv");
        if (Files.exists(f)) {
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                if (lines.size() > 5 && cacheFresh(lines, f)) return parseCsv(lines);
            } catch (Exception ignore) { }
        }
        List<String> lines = null;
        if (!EAST_BR.open()) {
            try {
                lines = fetchKlineEast(code);
                EAST_BR.ok();
            } catch (Exception e) {
                EAST_BR.fail();
            }
        }
        if (lines == null && !TX_BR.open()) {
            try {
                lines = fetchKlineTencent(code, floatShares);
                TX_BR.ok();
            } catch (Exception e) {
                TX_BR.fail();
            }
        }
        if (lines == null) lines = fetchKlineSina(code, floatShares);
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        return parseCsv(lines);
    }

    private static List<String> fetchKlineEast(String code) throws Exception {
        String url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=" + secid(code)
                + "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                + "&klt=101&fqt=1&beg=0&end=20500101&lmt=" + BARS;
        Map<String, Object> root = Json.map(Json.parse(get(url)));
        Map<String, Object> data = Json.map(root == null ? null : root.get("data"));
        if (data == null) throw new IOException("no kline data " + code);
        List<Object> kl = Json.list(data.get("klines"));
        if (kl == null || kl.isEmpty()) throw new IOException("empty kline " + code);
        List<String> lines = new ArrayList<>(kl.size());
        for (Object o : kl) lines.add(Json.str(o).replace(",", " "));
        return lines;
    }

    /** Tencent qfq daily kline fallback. Volume in lots; turnover derived from float shares. */
    private static List<String> fetchKlineTencent(String code, Double floatShares) throws Exception {
        String sym = (code.startsWith("6") || code.startsWith("9") ? "sh" : (code.startsWith("8") || code.startsWith("4") || code.startsWith("92") ? "bj" : "sz")) + code;
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" + sym + ",day,,," + BARS + ",qfq";
        throttleKline();
        String body = get(url);
        Map<String, Object> root = Json.map(Json.parse(body));
        Map<String, Object> data = Json.map(root == null ? null : root.get("data"));
        Map<String, Object> symData = data == null ? null : Json.map(data.get(sym));
        List<Object> rows = symData == null ? null : Json.list(symData.get("qfqday"));
        if (rows == null || rows.isEmpty()) rows = symData == null ? null : Json.list(symData.get("day"));
        if (rows == null || rows.isEmpty()) throw new IOException("no tencent kline " + code);
        List<String> lines = new ArrayList<>(rows.size());
        double prevC = 0;
        for (Object o : rows) {
            List<Object> r = Json.list(o);
            if (r == null || r.size() < 6) continue;
            String date = Json.str(r.get(0));
            Double open = Json.dbl(r.get(1)), close = Json.dbl(r.get(2)),
                   high = Json.dbl(r.get(3)), low = Json.dbl(r.get(4)), vol = Json.dbl(r.get(5));
            if (date == null || open == null || close == null) continue;
            double pct = prevC > 0 ? (close / prevC - 1) * 100 : 0;
            double amp = prevC > 0 ? (high - low) / prevC * 100 : 0;
            double turn = floatShares != null && floatShares > 0 ? Math.min(vol * 100 / floatShares * 100, 50) : 1.5;
            lines.add(date + " " + open + " " + close + " " + high + " " + low + " " + vol + " 0 "
                    + round2(amp) + " " + round2(pct) + " 0 " + round2(turn));
            prevC = close;
        }
        if (lines.isEmpty()) throw new IOException("no tencent kline rows " + code);
        return lines;
    }

    private static double round2(double x) { return Math.round(x * 100.0) / 100.0; }

    /** Sina daily kline fallback (unadjusted). Volume in shares. */
    private static List<String> fetchKlineSina(String code, Double floatShares) throws Exception {
        String sym = (code.startsWith("6") ? "sh"
                : (code.startsWith("8") || code.startsWith("4") || code.startsWith("92") ? "bj" : "sz")) + code;
        String url = "https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData?symbol=" + sym
                + "&scale=240&ma=no&datalen=" + BARS;
        throttleKline();
        Object parsed = Json.parse(get(url));
        List<Object> rows = Json.list(parsed);
        if (rows == null || rows.isEmpty()) throw new IOException("no sina kline " + code);
        List<String> lines = new ArrayList<>(rows.size());
        double prevC = 0;
        for (Object o : rows) {
            Map<String, Object> m = Json.map(o);
            if (m == null) continue;
            String date = Json.str(m.get("day"));
            Double open = Json.dbl(m.get("open")), close = Json.dbl(m.get("close")),
                   high = Json.dbl(m.get("high")), low = Json.dbl(m.get("low")), vol = Json.dbl(m.get("volume"));
            if (date == null || open == null || close == null) continue;
            double pct = prevC > 0 ? (close / prevC - 1) * 100 : 0;
            double amp = prevC > 0 ? (high - low) / prevC * 100 : 0;
            double turn = floatShares != null && floatShares > 0 ? Math.min(vol / floatShares * 100, 50) : 1.5;
            lines.add(date + " " + open + " " + close + " " + high + " " + low + " " + vol + " 0 "
                    + round2(amp) + " " + round2(pct) + " 0 " + round2(turn));
            prevC = close;
        }
        if (lines.isEmpty()) throw new IOException("no sina kline rows " + code);
        return lines;
    }

    /**
     * Cache reuse rule:
     * - market open (weekday 09:15-15:05): only fresh if last bar is today
     * - otherwise: last bar is today, or is lastTradingDay(), or cache was written today
     *   and last bar is within 7 days (covers long holidays like Golden Week)
     */
    private static boolean cacheFresh(List<String> lines, Path f) {
        try {
            String last = lines.get(lines.size() - 1).split("[ ,]")[0];
            LocalDate today = LocalDate.now();
            String todayStr = today.format(DF);
            if (last.equals(todayStr)) return true;
            if (last.equals(lastTradingDay())) return true;
            DayOfWeek w = today.getDayOfWeek();
            java.time.LocalTime t = java.time.LocalTime.now();
            boolean marketOpen = w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY
                    && !t.isBefore(java.time.LocalTime.of(9, 15)) && t.isBefore(java.time.LocalTime.of(15, 5));
            if (marketOpen) return false;
            if (Files.getLastModifiedTime(f).toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().equals(today)
                    && last.compareTo(today.minusDays(7).format(DF)) >= 0) return true;
        } catch (Exception ignore) { }
        return false;
    }

    private static K parseCsv(List<String> lines) {        K k = new K();
        int n = lines.size();
        k.dates = new String[n];
        k.b = new double[n][10];
        for (int i = 0; i < n; i++) {
            String[] p = lines.get(i).trim().split("[ ,]");
            k.dates[i] = p[0];
            for (int c = 0; c < 10; c++) {
                double v = 0;
                if (c + 1 < p.length) {
                    try { v = Double.parseDouble(p[c + 1]); } catch (Exception ignore) { }
                }
                k.b[i][c] = v;
            }
        }
        return k;
    }

    /** Derive float share count from snapshot: floatCap / price. */
    public static Double floatShares(Map<String, Object> stock) {
        Double cap = Json.dbl(stock.get("floatCap"));
        Double price = Json.dbl(stock.get("price"));
        return (cap != null && price != null && price > 0) ? cap / price : null;
    }

    // ---------------- batch quotes ----------------
    /** Live quotes for a batch of codes (<=60 each call internally). */
    public static List<Map<String, Object>> quotes(List<String> codes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int from = 0; from < codes.size(); from += 60) {
            List<String> part = codes.subList(from, Math.min(codes.size(), from + 60));
            StringBuilder sb = new StringBuilder();
            for (String c : part) {
                if (sb.length() > 0) sb.append(',');
                sb.append(secid(c));
            }
            try {
                String url = "https://push2.eastmoney.com/api/qt/ulist.np/get?fltt=2&invt=2&np=1"
                        + "&secids=" + sb + "&fields=f2,f3,f12,f14,f15,f16,f18,f20";
                Map<String, Object> root = Json.map(Json.parse(get(url)));
                Map<String, Object> data = Json.map(root == null ? null : root.get("data"));
                List<Object> diff = data == null ? null : Json.list(data.get("diff"));
                if (diff != null) {
                    for (Object o : diff) {
                        Map<String, Object> m = Json.map(o);
                        if (m == null) continue;
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("code", Json.str(m.get("f12")));
                        r.put("name", Json.str(m.get("f14")));
                        r.put("price", Json.dbl(m.get("f2")));
                        r.put("pct", Json.dbl(m.get("f3")));
                        r.put("high", Json.dbl(m.get("f15")));
                        r.put("low", Json.dbl(m.get("f16")));
                        r.put("prevClose", Json.dbl(m.get("f18")));
                        r.put("mktcap", Json.dbl(m.get("f20")));
                        out.add(r);
                    }
                }
            } catch (Exception ignore) { }
        }
        return out;
    }

    private Api() { }
}
