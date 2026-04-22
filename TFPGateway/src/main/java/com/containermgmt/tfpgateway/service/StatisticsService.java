package com.containermgmt.tfpgateway.service;

import com.containermgmt.tfpgateway.config.ActiveJDBCConfig;

import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@Slf4j
public class StatisticsService {

    private static final Set<String> ALLOWED_GRANULARITIES =
            Set.of("minute", "hour", "day");

    private final ActiveJDBCConfig activeJDBCConfig;

    public StatisticsService(ActiveJDBCConfig activeJDBCConfig) {
        this.activeJDBCConfig = activeJDBCConfig;
    }

    /**
     * Time-series di messaggi per event_type, aggregati per bucket temporale.
     * Ritorna { buckets: [ISO timestamp...], series: { EVENT_TYPE: [count...] } }.
     */
    public Map<String, Object> eventsOverTime(String granularity, int lookbackHours) {
        String gran = ALLOWED_GRANULARITIES.contains(granularity) ? granularity : "hour";

        try {
            activeJDBCConfig.openConnection();

            String sql =
                    "SELECT date_trunc('" + gran + "', event_time) AS bucket, " +
                    "       event_type, " +
                    "       COUNT(*) AS cnt " +
                    "  FROM evt_raw_events " +
                    " WHERE event_time >= NOW() - (? || ' hours')::interval " +
                    " GROUP BY bucket, event_type " +
                    " ORDER BY bucket ASC";

            List<Map<String, Object>> rows = Base.findAll(sql, String.valueOf(lookbackHours));

            // Preserva ordine inserimento bucket; usa TreeSet per event_types (stabile)
            LinkedHashMap<String, Map<String, Long>> byBucket = new LinkedHashMap<>();
            TreeSet<String> eventTypes = new TreeSet<>();

            for (Map<String, Object> row : rows) {
                String bucket = toIso(row.get("bucket"));
                String eventType = (String) row.get("event_type");
                long count = ((Number) row.get("cnt")).longValue();

                eventTypes.add(eventType);
                byBucket
                        .computeIfAbsent(bucket, k -> new HashMap<>())
                        .put(eventType, count);
            }

            List<String> buckets = new ArrayList<>(byBucket.keySet());
            Map<String, List<Long>> series = new LinkedHashMap<>();
            for (String et : eventTypes) {
                List<Long> values = new ArrayList<>(buckets.size());
                for (String bucket : buckets) {
                    values.add(byBucket.get(bucket).getOrDefault(et, 0L));
                }
                series.put(et, values);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("granularity", gran);
            result.put("lookbackHours", lookbackHours);
            result.put("buckets", buckets);
            result.put("series", series);
            return result;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    /**
     * Summary globali: totali sull'intero DB e per finestre temporali recenti.
     */
    public Map<String, Object> summary() {
        try {
            activeJDBCConfig.openConnection();

            Map<String, Object> result = new HashMap<>();
            result.put("totalEvents", firstLong(
                    "SELECT COUNT(*) FROM evt_raw_events"));
            result.put("distinctEventTypes", firstLong(
                    "SELECT COUNT(DISTINCT event_type) FROM evt_raw_events"));
            result.put("eventsLastHour", firstLong(
                    "SELECT COUNT(*) FROM evt_raw_events WHERE event_time >= NOW() - interval '1 hour'"));
            result.put("eventsLast24h", firstLong(
                    "SELECT COUNT(*) FROM evt_raw_events WHERE event_time >= NOW() - interval '24 hours'"));
            result.put("eventsLast7d", firstLong(
                    "SELECT COUNT(*) FROM evt_raw_events WHERE event_time >= NOW() - interval '7 days'"));
            result.put("eventsToday", firstLong(
                    "SELECT COUNT(*) FROM evt_raw_events WHERE event_time >= date_trunc('day', NOW())"));

            Object oldest = Base.firstCell(
                    "SELECT MIN(event_time) FROM evt_raw_events");
            Object newest = Base.firstCell(
                    "SELECT MAX(event_time) FROM evt_raw_events");
            result.put("oldestEventTime", toIso(oldest));
            result.put("newestEventTime", toIso(newest));

            // Throughput medio (eventi/minuto) negli ultimi 60 min
            Long last60 = firstLong(
                    "SELECT COUNT(*) FROM evt_raw_events WHERE event_time >= NOW() - interval '60 minutes'");
            result.put("throughputPerMin", last60 != null ? Math.round(last60 / 60.0 * 100.0) / 100.0 : 0.0);

            return result;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    /**
     * Conteggi per event_type nell'intervallo di lookback.
     */
    public List<Map<String, Object>> countsByEventType(int lookbackHours) {
        try {
            activeJDBCConfig.openConnection();

            String sql =
                    "SELECT event_type, " +
                    "       COUNT(*) AS cnt, " +
                    "       MAX(event_time) AS last_seen " +
                    "  FROM evt_raw_events " +
                    " WHERE event_time >= NOW() - (? || ' hours')::interval " +
                    " GROUP BY event_type " +
                    " ORDER BY cnt DESC";

            List<Map<String, Object>> rows = Base.findAll(sql, String.valueOf(lookbackHours));
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("eventType", row.get("event_type"));
                entry.put("count", ((Number) row.get("cnt")).longValue());
                entry.put("lastSeen", toIso(row.get("last_seen")));
                result.add(entry);
            }
            return result;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    /**
     * Processing lag (processed_at - event_time) in secondi, per event_type:
     * p50, p95, p99, max. Utile per rilevare backpressure broker/consumer.
     */
    public List<Map<String, Object>> processingLag(int lookbackHours) {
        try {
            activeJDBCConfig.openConnection();

            String sql =
                    "SELECT event_type, " +
                    "       COUNT(*) AS cnt, " +
                    "       AVG(EXTRACT(EPOCH FROM (processed_at - event_time))) AS avg_lag, " +
                    "       PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (processed_at - event_time))) AS p50, " +
                    "       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (processed_at - event_time))) AS p95, " +
                    "       PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (processed_at - event_time))) AS p99, " +
                    "       MAX(EXTRACT(EPOCH FROM (processed_at - event_time))) AS max_lag " +
                    "  FROM evt_raw_events " +
                    " WHERE event_time >= NOW() - (? || ' hours')::interval " +
                    "   AND processed_at IS NOT NULL " +
                    "   AND event_time IS NOT NULL " +
                    " GROUP BY event_type " +
                    " ORDER BY p95 DESC NULLS LAST";

            List<Map<String, Object>> rows = Base.findAll(sql, String.valueOf(lookbackHours));
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("eventType", row.get("event_type"));
                entry.put("count", ((Number) row.get("cnt")).longValue());
                entry.put("avgSec", roundDouble(row.get("avg_lag")));
                entry.put("p50Sec", roundDouble(row.get("p50")));
                entry.put("p95Sec", roundDouble(row.get("p95")));
                entry.put("p99Sec", roundDouble(row.get("p99")));
                entry.put("maxSec", roundDouble(row.get("max_lag")));
                result.add(entry);
            }
            return result;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    /**
     * Arrival gap distribution: per ogni event_type calcola i delta tra eventi
     * consecutivi (secondi) e li classifica in bucket a scala logaritmica.
     * Utile per rilevare burst (molti gap piccoli) e starvation (pochi gap enormi).
     *
     * Ritorna per ogni event_type:
     *   - counts per bucket [0-1s, 1-5s, 5-30s, 30s-2m, 2-10m, 10-60m, >60m]
     *   - stats: min, p50, p95, max, avg (in secondi)
     */
    public Map<String, Object> arrivalGapDistribution(int lookbackHours) {
        try {
            activeJDBCConfig.openConnection();

            String sql =
                    "WITH gaps AS ( " +
                    "  SELECT event_type, " +
                    "         EXTRACT(EPOCH FROM (event_time - LAG(event_time) " +
                    "            OVER (PARTITION BY event_type ORDER BY event_time))) AS gap_sec " +
                    "    FROM evt_raw_events " +
                    "   WHERE event_time >= NOW() - (? || ' hours')::interval " +
                    ") " +
                    "SELECT event_type, " +
                    "       COUNT(gap_sec) AS samples, " +
                    "       MIN(gap_sec) AS min_gap, " +
                    "       AVG(gap_sec) AS avg_gap, " +
                    "       PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY gap_sec) AS p50, " +
                    "       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY gap_sec) AS p95, " +
                    "       MAX(gap_sec) AS max_gap, " +
                    "       COUNT(*) FILTER (WHERE gap_sec <  1)                           AS b1, " +
                    "       COUNT(*) FILTER (WHERE gap_sec >= 1    AND gap_sec < 5)        AS b2, " +
                    "       COUNT(*) FILTER (WHERE gap_sec >= 5    AND gap_sec < 30)       AS b3, " +
                    "       COUNT(*) FILTER (WHERE gap_sec >= 30   AND gap_sec < 120)      AS b4, " +
                    "       COUNT(*) FILTER (WHERE gap_sec >= 120  AND gap_sec < 600)      AS b5, " +
                    "       COUNT(*) FILTER (WHERE gap_sec >= 600  AND gap_sec < 3600)     AS b6, " +
                    "       COUNT(*) FILTER (WHERE gap_sec >= 3600)                        AS b7 " +
                    "  FROM gaps " +
                    " WHERE gap_sec IS NOT NULL " +
                    " GROUP BY event_type " +
                    " ORDER BY event_type";

            List<Map<String, Object>> rows = Base.findAll(sql, String.valueOf(lookbackHours));

            List<String> bucketLabels = List.of(
                    "<1s", "1-5s", "5-30s", "30s-2m", "2-10m", "10-60m", ">1h");

            List<Map<String, Object>> series = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("eventType", row.get("event_type"));
                entry.put("samples", ((Number) row.get("samples")).longValue());
                entry.put("minSec", roundDouble(row.get("min_gap")));
                entry.put("avgSec", roundDouble(row.get("avg_gap")));
                entry.put("p50Sec", roundDouble(row.get("p50")));
                entry.put("p95Sec", roundDouble(row.get("p95")));
                entry.put("maxSec", roundDouble(row.get("max_gap")));
                List<Long> counts = new ArrayList<>(7);
                for (int i = 1; i <= 7; i++) {
                    Object v = row.get("b" + i);
                    counts.add(v == null ? 0L : ((Number) v).longValue());
                }
                entry.put("counts", counts);
                series.add(entry);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("lookbackHours", lookbackHours);
            result.put("bucketLabels", bucketLabels);
            result.put("series", series);
            return result;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    /**
     * "Silence" monitor: per ogni event_type, ultimo event_time ricevuto e secondi
     * trascorsi da allora. Utile per capire se una coda e' ferma (consumer morto,
     * producer down, address non routata).
     */
    public List<Map<String, Object>> silenceByEventType() {
        try {
            activeJDBCConfig.openConnection();

            String sql =
                    "SELECT event_type, " +
                    "       MAX(event_time) AS last_seen, " +
                    "       EXTRACT(EPOCH FROM (NOW() - MAX(event_time))) AS silence_seconds, " +
                    "       COUNT(*) AS total " +
                    "  FROM evt_raw_events " +
                    " GROUP BY event_type " +
                    " ORDER BY silence_seconds DESC";

            List<Map<String, Object>> rows = Base.findAll(sql);
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("eventType", row.get("event_type"));
                entry.put("lastSeen", toIso(row.get("last_seen")));
                entry.put("silenceSec", roundDouble(row.get("silence_seconds")));
                entry.put("total", ((Number) row.get("total")).longValue());
                result.add(entry);
            }
            return result;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    private Long firstLong(String sql, Object... params) {
        Object v = Base.firstCell(sql, params);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static Double roundDouble(Object v) {
        if (v == null) return null;
        double d = ((Number) v).doubleValue();
        return Math.round(d * 1000.0) / 1000.0;
    }

    private static String toIso(Object ts) {
        if (ts == null) return null;
        if (ts instanceof Timestamp t) return t.toInstant().toString();
        if (ts instanceof Instant i) return i.toString();
        if (ts instanceof OffsetDateTime o) return o.toInstant().toString();
        return ts.toString();
    }
}
