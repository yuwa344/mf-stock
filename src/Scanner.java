import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Full-market multi-factor pipeline with async progress + result persistence. */
public final class Scanner {

    public static class State {
        public volatile boolean running = false;
        public volatile int total = 0, done = 0;
        public volatile String phase = "";
        public volatile String error = null;
        public volatile String finishedAt = null;
        public final int[] stageCounts = new int[7]; // index 1..6
        public volatile List<Map<String, Object>> results = new ArrayList<>();
    }

    private static final State STATE = new State();
    private static final Object RUN_LOCK = new Object();

    public static State state() { return STATE; }

    /** Kick off a scan; returns false if already running. */
    public static boolean start() {
        synchronized (RUN_LOCK) {
            if (STATE.running) return false;
            STATE.running = true;
            STATE.error = null;
            STATE.done = 0;
            STATE.total = 0;
            STATE.phase = "准备中";
            Thread t = new Thread(Scanner::run, "scanner");
            t.setDaemon(true);
            t.start();
            return true;
        }
    }

    private static void run() {
        try {
            List<Map<String, Object>> universe = Api.universe();
            STATE.total = universe.size();
            STATE.phase = "拉取K线并计算因子";

            ExecutorService ex = Executors.newFixedThreadPool(8);
            Map<String, Map<String, Object>> metrics = new ConcurrentHashMap<>();
            Map<String, Api.K> klines = new ConcurrentHashMap<>();
            AtomicInteger done = new AtomicInteger();

            for (Map<String, Object> s : universe) {
                String code = Json.str(s.get("code"));
                ex.submit(() -> {
                    try {
                        Api.K k = Api.kline(code, Api.floatShares(s));
                        klines.put(code, k);
                        metrics.put(code, Factors.precompute(k, s));
                    } catch (Exception ignore) { }
                    done.incrementAndGet();
                    STATE.done = done.get();
                });
            }
            // wait via polling executor completion
            ex.shutdown();
            try {
                ex.awaitTermination(30, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            STATE.phase = "截面排名与漏斗分级";

            // factor 1+2 pool
            List<Map<String, Object>> pool = new ArrayList<>();
            for (Map<String, Object> s : universe) {
                String code = Json.str(s.get("code"));
                Map<String, Object> m = metrics.get(code);
                if (m == null) continue;
                if (Boolean.TRUE.equals(m.get("f1_pass")) && Boolean.TRUE.equals(m.get("f2_pass"))) pool.add(s);
            }

            // cross-sectional RS ranking within pool: top 15%
            pool.sort((a, b) -> Double.compare(
                    (Double) metrics.get(Json.str(b.get("code"))).get("f3_score"),
                    (Double) metrics.get(Json.str(a.get("code"))).get("f3_score")));
            int topN = Math.max(10, (int) Math.ceil(pool.size() * 0.15));

            // build result rows for all evaluated stocks
            List<Map<String, Object>> rows = new ArrayList<>();
            int rank = 0;
            Set<String> rsPassed = new HashSet<>();
            for (Map<String, Object> s : pool) {
                rank++;
                if (rank <= topN) rsPassed.add(Json.str(s.get("code")));
            }

            for (Map<String, Object> s : universe) {
                String code = Json.str(s.get("code"));
                Map<String, Object> m = metrics.get(code);
                if (m == null) continue;
                boolean f3 = rsPassed.contains(code);
                m.put("f3_pass", f3);
                if (f3) {
                    // percentile within pool
                    m.put("f3_pct", rankOf(pool, code, pool.size()));
                } else {
                    m.put("f3_pct", null);
                }
                int stage = Factors.stage(m);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("code", code);
                row.put("name", s.get("name"));
                row.put("industry", s.get("industry"));
                row.put("price", s.get("price"));
                row.put("pct", s.get("pct"));
                row.put("rsScore", m.get("f3_score"));
                row.put("stage", stage);
                row.put("f", m);
                rows.add(row);
            }
            rows.sort((a, b) -> {
                int sa = (Integer) a.get("stage"), sb = (Integer) b.get("stage");
                if (sa != sb) return Integer.compare(sb, sa);
                return Double.compare((Double) b.get("rsScore"), (Double) a.get("rsScore"));
            });

            int[] counts = new int[7];
            for (Map<String, Object> row : rows) counts[(Integer) row.get("stage")]++;
            // cumulative: stage>=N
            for (int i = 5; i >= 1; i--) counts[i] += counts[i + 1];
            System.arraycopy(counts, 0, STATE.stageCounts, 0, 7);

            STATE.results = rows;
            STATE.phase = "完成";
            STATE.finishedAt = java.time.LocalDateTime.now().toString();
            persist(rows, counts);
        } catch (Exception e) {
            STATE.error = String.valueOf(e);
            e.printStackTrace();
        } finally {
            STATE.running = false;
        }
    }

    private static double rankOf(List<Map<String, Object>> pool, String code, int size) {
        for (int i = 0; i < pool.size(); i++) {
            if (code.equals(Json.str(pool.get(i).get("code")))) return Math.round((double) (i + 1) / size * 10000) / 100.0;
        }
        return 100;
    }

    private static void persist(List<Map<String, Object>> rows, int[] counts) {
        try {
            Path f = Paths.get("data", "scan.json");
            Files.createDirectories(f.getParent());
            List<Integer> boxed = new ArrayList<>();
            for (int v : counts) boxed.add(v);
            Map<String, Object> save = new LinkedHashMap<>();
            save.put("finishedAt", STATE.finishedAt);
            save.put("counts", boxed);
            save.put("rows", rows);
            Files.writeString(f, Json.write(save), StandardCharsets.UTF_8);
        } catch (Exception ignore) { }
    }

    /** Load last persisted scan at boot. */
    @SuppressWarnings("unchecked")
    public static void loadPersisted() {
        try {
            Path f = Paths.get("data", "scan.json");
            if (!Files.exists(f)) return;
            Map<String, Object> save = Json.map(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
            List<Object> rows = Json.list(save.get("rows"));
            if (rows == null) return;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : rows) out.add((Map<String, Object>) o);
            STATE.results = out;
            List<Object> counts = Json.list(save.get("counts"));
            if (counts != null) for (int i = 0; i < Math.min(7, counts.size()); i++) STATE.stageCounts[i] = Json.dbl(counts.get(i)).intValue();
            STATE.finishedAt = Json.str(save.get("finishedAt"));
            STATE.phase = "完成";
        } catch (Exception ignore) { }
    }

    private Scanner() { }
}
