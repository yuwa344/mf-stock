import java.util.*;

/** Six-factor engine: Trend / Fundamentals / RS / VCP / Chip / Pivot-Breakout. */
public final class Factors {

    /** Precompute all per-stock metrics from daily kline + snapshot fundamentals. */
    public static Map<String, Object> precompute(Api.K k, Map<String, Object> stock) {
        int n = k.n();
        double[] c = col(k, 1), h = col(k, 2), l = col(k, 3), v = col(k, 4), turn = col(k, 9);
        Map<String, Object> r = new LinkedHashMap<>();

        // ---- moving averages ----
        double ma5 = ma(c, 5, n - 1), ma10 = ma(c, 10, n - 1), ma20 = ma(c, 20, n - 1),
               ma60 = ma(c, 60, n - 1), ma200 = ma(c, 200, n - 1);
        double ma200prev = ma(c, 200, n - 21);

        // ---- 52 week high/low (last ~250 bars) ----
        int w52 = Math.min(n, 250);
        double hi52 = 0, lo52 = Double.MAX_VALUE;
        for (int i = n - w52; i < n; i++) { hi52 = Math.max(hi52, h[i]); lo52 = Math.min(lo52, l[i]); }

        boolean maBull = ma5 > ma10 && ma10 > ma20 && ma20 > ma60;
        boolean ma200Up = ma200 > ma200prev;
        double close = c[n - 1];
        boolean nearHigh = close >= hi52 * 0.75;
        r.put("f1_maBull", maBull);
        r.put("f1_ma200Up", ma200Up);
        r.put("f1_nearHigh", nearHigh);
        r.put("f1_offHigh", pctOff(close, hi52));
        r.put("f1_pass", maBull && ma200Up && nearHigh);

        // ---- fundamentals (snapshot proxy for EPS acceleration) ----
        Double revYoy = Json.dbl(stock.get("revYoy"));
        Double profYoy = Json.dbl(stock.get("profitYoy"));
        boolean f2 = revYoy != null && profYoy != null && revYoy >= 0 && profYoy >= 20 && profYoy >= revYoy;
        r.put("f2_revYoy", revYoy);
        r.put("f2_profitYoy", profYoy);
        r.put("f2_accel", (revYoy != null && profYoy != null) && profYoy > revYoy);
        r.put("f2_pass", f2);

        // ---- RS score (O'Neil style weighted multi-period return) ----
        double r63 = ret(c, 63), r126 = ret(c, 126), r189 = ret(c, 189), r250 = ret(c, 250);
        double score = wsum(r63, 0.4, r126, 0.2, r189, 0.2, r250, 0.2);
        r.put("f3_score", round(score));
        r.put("f3_pct", null);          // filled by cross-section ranking
        r.put("f3_pass", null);

        // ---- VCP ----
        List<Double> ratios = new ArrayList<>();
        double finalRange = -1;
        double volDry = avg(v, n - 5, n - 1) / safeAvg(v, n - 50, n - 1);
        List<int[]> pivots = fractalPivots(k, Math.max(0, n - 90), n - 1, 4);
        // alternating pivot sequence
        List<int[]> alt = new ArrayList<>();
        for (int[] p : pivots) {
            if (!alt.isEmpty() && alt.get(alt.size() - 1)[1] == p[1]) {
                // keep the more extreme one of same type
                int[] q = alt.get(alt.size() - 1);
                boolean hiType = p[1] == 1;
                if (hiType ? k.b[p[0]][2] > k.b[q[0]][2] : k.b[p[0]][3] < k.b[q[0]][3]) alt.set(alt.size() - 1, p);
            } else alt.add(p);
        }
        if (alt.size() >= 3) {
            double prevRange = -1;
            for (int i = 1; i < alt.size(); i++) {
                int a = alt.get(i - 1)[0], bIdx = alt.get(i)[0];
                double ra = Math.abs(k.b[a][2] - k.b[a][3]);
                double rb = Math.abs(k.b[bIdx][2] - k.b[bIdx][3]);
                if (prevRange > 0 && rb / prevRange > 0) ratios.add(round(rb / prevRange));
                prevRange = rb;
                ra = rb; // keep flow simple
            }
            int lastIdx = alt.get(alt.size() - 1)[0];
            double lastHigh = k.b[lastIdx][2], lastLow = k.b[lastIdx][3];
            finalRange = round((lastHigh - lastLow) / close * 100);
        }
        boolean vcpOk = false;
        if (ratios.size() >= 2) {
            boolean allShrink = true;
            int tight = 0;
            for (double x : ratios) { if (x > 0.85) allShrink = false; if (x <= 0.7) tight++; }
            vcpOk = allShrink && tight >= 1 && volDry <= 0.75;
        }
        r.put("f4_ratios", ratios);
        r.put("f4_volDry", round(volDry));
        r.put("f4_finalRange", finalRange);
        r.put("f4_pass", vcpOk);

        // ---- chip profile ----
        Chip ch = chip(k);
        r.put("f5_conc90", round(ch.conc90));
        r.put("f5_profitRatio", round(ch.profitRatio));
        r.put("f5_overhead", round(ch.overhead));
        r.put("f5_pass", ch.conc90 < 10 && ch.profitRatio > 85 && ch.overhead < 0.35);

        // ---- pivot breakout ----
        double pivot = 0;
        for (int i = Math.max(0, n - 60); i <= n - 4; i++) pivot = Math.max(pivot, h[i]);
        double volRatio = v[n - 1] / safeAvg(v, n - 50, n - 1);
        boolean breakout = pivot > 0 && close >= pivot * 0.995;
        r.put("f6_pivot", round(pivot));
        r.put("f6_volRatio", round(volRatio));
        r.put("f6_breakout", breakout);
        r.put("f6_pass", breakout && volRatio >= 1.5);

        return r;
    }

    /** Consecutive passed stage 1..6 (f3 must be pre-set before calling). */
    public static int stage(Map<String, Object> m) {
        int s = 0;
        for (int i = 1; i <= 6; i++) {
            Object p = m.get("f" + i + "_pass");
            if (Boolean.TRUE.equals(p)) s = i; else break;
        }
        return s;
    }

    // ================= chip distribution =================
    public static class Chip {
        double conc90, profitRatio, overhead;
    }

    /** Volume-at-price triangular chip model with turnover decay, last ~250 bars. */
    public static Chip chip(Api.K k) {
        int n = k.n();
        int from = Math.max(0, n - 250);
        double[] c = col(k, 1), h = col(k, 2), l = col(k, 3), o = col(k, 0), v = col(k, 4), turn = col(k, 9);
        double minP = Double.MAX_VALUE, maxP = 0;
        for (int i = from; i < n; i++) { minP = Math.min(minP, l[i]); maxP = Math.max(maxP, h[i]); }
        if (!(maxP > minP)) { Chip e = new Chip(); e.conc90 = 999; e.profitRatio = 0; e.overhead = 0; return e; }
        int BINS = 240;
        double step = (maxP - minP) / (BINS - 1);
        double[] d = new double[BINS];
        for (int i = from; i < n; i++) {
            double t = turn[i] > 0 ? Math.min(turn[i] / 100.0, 0.99) : 0.01;
            if (t > 0) for (int b = 0; b < BINS; b++) d[b] *= (1 - t);
            double lo = l[i], hi = h[i];
            double peak = (o[i] + h[i] + l[i] + 2 * c[i]) / 4.0;
            peak = Math.max(lo, Math.min(hi, peak));
            int loB = (int) Math.round((lo - minP) / step), hiB = (int) Math.round((hi - minP) / step);
            loB = Math.max(0, Math.min(BINS - 1, loB));
            hiB = Math.max(0, Math.min(BINS - 1, hiB));
            int pkB = (int) Math.round((peak - minP) / step);
            pkB = Math.max(loB, Math.min(hiB, pkB));
            double vol = Math.max(v[i], 1);
            // triangular distribution over [loB, hiB] with peak at pkB, total = vol
            int leftSpan = pkB - loB, rightSpan = hiB - pkB;
            double totalW = 0;
            double[] w = new double[hiB - loB + 1];
            for (int b = loB; b <= hiB; b++) {
                int dist = b <= pkB ? (leftSpan == 0 ? 0 : pkB - b) : (rightSpan == 0 ? 0 : b - pkB);
                int span = b <= pkB ? Math.max(leftSpan, 1) : Math.max(rightSpan, 1);
                w[b - loB] = 1.0 - (double) dist / (span + 1);
                if (w[b - loB] < 0.05) w[b - loB] = 0.05;
                totalW += w[b - loB];
            }
            if (totalW <= 0) { w[0] = 1; totalW = 1; }
            for (int b = loB; b <= hiB; b++) d[b] += vol * w[b - loB] / totalW;
        }
        double total = 0;
        for (double x : d) total += x;
        if (total <= 0) { Chip e = new Chip(); e.conc90 = 999; e.profitRatio = 0; e.overhead = 0; return e; }
        double close = c[n - 1];
        // profit ratio: chips strictly below current close
        double cum = 0, below = 0;
        double maxDensity = 0, overheadPeak = 0;
        int closeB = (int) Math.round((close - minP) / step);
        for (int b = 0; b < BINS; b++) {
            if (b < closeB) below += d[b];
            maxDensity = Math.max(maxDensity, d[b]);
            if (close < minP + b * step && (minP + b * step) <= close * 1.2) overheadPeak = Math.max(overheadPeak, d[b]);
        }
        cum = below / total;
        // 90% cost range: cumulative 5% .. 95%
        double lo90 = 0, hi90 = 0, acc = 0;
        boolean loFound = false;
        for (int b = 0; b < BINS; b++) {
            acc += d[b];
            if (!loFound && acc >= total * 0.05) { lo90 = minP + b * step; loFound = true; }
            if (acc >= total * 0.95) { hi90 = minP + b * step; break; }
        }
        Chip ch = new Chip();
        ch.conc90 = (lo90 + hi90) > 0 ? (hi90 - lo90) / (hi90 + lo90) * 2 * 100 : 999;
        ch.profitRatio = cum * 100;
        ch.overhead = maxDensity > 0 ? overheadPeak / maxDensity : 0;
        return ch;
    }

    // ================= helpers =================
    static double[] col(Api.K k, int c) {
        double[] a = new double[k.n()];
        for (int i = 0; i < a.length; i++) a[i] = k.b[i][c];
        return a;
    }

    static double ma(double[] a, int len, int end) {
        if (end + 1 < len) return Double.NaN;
        double s = 0;
        for (int i = end - len + 1; i <= end; i++) s += a[i];
        return s / len;
    }

    static double avg(double[] a, int from, int to) {
        double s = 0; int cnt = 0;
        from = Math.max(0, from);
        for (int i = from; i <= to; i++) { s += a[i]; cnt++; }
        return cnt == 0 ? 0 : s / cnt;
    }

    static double safeAvg(double[] a, int from, int to) {
        double x = avg(a, from, to);
        return x <= 0 ? 1 : x;
    }

    static double ret(double[] c, int days) {
        int n = c.length;
        int i0 = n - 1 - days;
        if (i0 < 0) return Double.NaN;
        return (c[n - 1] / c[i0] - 1) * 100;
    }

    static double wsum(Double a, double wa, Double b, double wb, Double c2, double wc, Double d, double wd) {
        double s = 0, w = 0;
        if (!a.isNaN()) { s += a * wa; w += wa; }
        if (!b.isNaN()) { s += b * wb; w += wb; }
        if (!c2.isNaN()) { s += c2 * wc; w += wc; }
        if (!d.isNaN()) { s += d * wd; w += wd; }
        return w == 0 ? 0 : s / w;
    }

    static double pctOff(double close, double hi) {
        return hi > 0 ? round((hi - close) / hi * 100) : 999;
    }

    static double round(double x) {
        if (Double.isNaN(x)) return 0;
        return Math.round(x * 100.0) / 100.0;
    }

    /** Fractal pivots: index+type array, type 1=high 0=low. */
    static List<int[]> fractalPivots(Api.K k, int from, int to, int kk) {
        List<int[]> out = new ArrayList<>();
        for (int i = from + kk; i <= to - kk; i++) {
            boolean isHi = true, isLo = true;
            for (int j = i - kk; j <= i + kk; j++) {
                if (j == i) continue;
                if (k.b[j][2] >= k.b[i][2]) isHi = false;
                if (k.b[j][3] <= k.b[i][3]) isLo = false;
            }
            if (isHi) out.add(new int[]{i, 1});
            if (isLo) out.add(new int[]{i, 0});
        }
        out.sort((a, b) -> a[0] - b[0]);
        return out;
    }

    private Factors() { }
}
