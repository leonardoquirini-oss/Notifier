package com.containermgmt.tfpeventingester.service;

import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Risolve la "mission" di un unit event tutto su DB (BERLink evt_unit_events + TIR ElencoRichieste3).
 * La chiamata diretta a TFP e' stata disabilitata (vedi {@link TfpMissionLookupService}, non piu' usato).
 *
 * <p>Algoritmo (CURR_EVT = evento corrente):
 * <ol>
 *   <li>Se CURR_EVT ha transportOrderShortCode → mission = refNum estratto. FINE.</li>
 *   <li>Altrimenti cerca START_EVT: evento piu' recente con event_time &lt;= CURR_EVT.eventTime,
 *       stesso unit_number, type=(PICKUP+loadStatus=FULL) o BEGIN_LOAD.
 *       <ul>
 *         <li>trovato → BG = refNum di START_EVT; query TIR DataS/DaProcessare per BG;
 *             se DaProcessare=0 → niente mission FINE; altrimenti vai a 3.</li>
 *         <li>non trovato → niente mission. FINE.</li>
 *       </ul></li>
 *   <li>Cerca END_EVT dopo START_EVT con type IN (DROP, END_UNLOAD):
 *       <ul>
 *         <li>END &gt;= CURR → mission = BG. FINE.</li>
 *         <li>END &lt; CURR → niente mission (evento fra due missioni). FINE.</li>
 *         <li>nessun END: DataS &lt; CURR → niente mission; DataS &gt;= CURR e DaProcessare=1 → mission = BG.</li>
 *       </ul></li>
 * </ol>
 */
@Component
@Slf4j
public class MissionResolutionService {

    private static final ZoneId TIR_ZONE = ZoneId.of("Europe/Rome");

    private static final String START_SQL =
            "SELECT event_time, payload->>'transportOrderShortCode' AS short_code " +
            "FROM evt_unit_events " +
            "WHERE unit_number = ? AND event_time <= ? " +
            "AND ( (payload->>'type' = 'PICKUP' AND payload->>'loadStatus' = 'FULL') " +
            "      OR payload->>'type' = 'BEGIN_LOAD' ) " +
            "ORDER BY event_time DESC LIMIT 1";

    private static final String END_SQL =
            "SELECT event_time FROM evt_unit_events " +
            "WHERE unit_number = ? AND event_time > ? " +
            "AND payload->>'type' IN ('DROP','END_UNLOAD') " +
            "ORDER BY event_time ASC LIMIT 1";

    private final TirConnectorClient tirConnectorClient;

    public MissionResolutionService(TirConnectorClient tirConnectorClient) {
        this.tirConnectorClient = tirConnectorClient;
    }

    /**
     * @param unitNumber     CURR_EVT.unit_number
     * @param eventTime      CURR_EVT.eventTime
     * @param currType       CURR_EVT.payload.type (per riconoscere gli eventi di chiusura)
     * @param currShortCode  CURR_EVT.payload.transportOrderShortCode (può essere null)
     * @return mission (refNum) o null se nessuna missione associata
     */
    public String resolve(String unitNumber, Instant eventTime, String currType, String currShortCode) {
        String tag = "[mission unit=" + unitNumber + " evt=" + eventTime + " type=" + currType + "]";
        log.debug("{} START resolve, currShortCode={}", tag, currShortCode);

        // 1) evento corrente con transportOrderShortCode
        if (isNotBlank(currShortCode)) {
            String m = extractRefNum(currShortCode);
            log.debug("{} EXIT step1 (CURR ha transportOrderShortCode) → mission={}", tag, m);
            return m;
        }
        if (unitNumber == null || unitNumber.isBlank() || eventTime == null) {
            log.debug("{} EXIT input invalido → mission=null", tag);
            return null;
        }

        // 2) cerca START_EVT
        Timestamp curr = Timestamp.from(eventTime);
        List<Map<String, Object>> startRows = Base.findAll(START_SQL, unitNumber, curr);
        if (startRows.isEmpty()) {
            log.debug("{} EXIT step2b (nessun START_EVT trovato) → mission=null", tag);
            return null;
        }
        Map<String, Object> start = startRows.get(0);
        String startShortCode = (String) start.get("short_code");
        String bg = extractRefNum(startShortCode);
        Timestamp startTime = (Timestamp) start.get("event_time");
        log.debug("{} step2 START_EVT trovato: event_time={}, short_code='{}', BG='{}'",
                tag, startTime, startShortCode, bg);
        if (isBlank(bg)) {
            log.debug("{} EXIT BG vuoto da START_EVT → mission=null", tag);
            return null;
        }

        // Eventi di chiusura (DROP / END_UNLOAD) ereditano la mission dello START,
        // come lo START stesso (che usa il proprio transportOrderShortCode, senza TIR).
        // Ma solo se non c'e' gia' un END piu' vecchio tra START e questo evento: in tal
        // caso il ciclo aperto dallo START era gia' chiuso e questo evento appartiene a un
        // ciclo successivo privo di START → niente mission.
        if (isEndEvent(currType)) {
            List<Map<String, Object>> priorEndRows = Base.findAll(END_SQL, unitNumber, startTime);
            if (!priorEndRows.isEmpty()) {
                Instant priorEnd = ((Timestamp) priorEndRows.get(0).get("event_time")).toInstant();
                if (priorEnd.isBefore(eventTime)) {
                    log.debug("{} EXIT evento di chiusura ma END precedente ({}) < CURR → mission=null (ciclo gia' chiuso)",
                            tag, priorEnd);
                    return null;
                }
            }
            log.debug("{} EXIT evento di chiusura ({}) → mission={} (eredita da START)", tag, currType, bg);
            return bg;
        }

        // 2a) interroga TIR per BG (DataS = DataConsegnaEffettiva, DaProcessare = flag)
        TirRow tir = queryTir(bg);
        log.debug("{} step2a TIR per BG='{}' → {}", tag, bg, tir);
        if (tir.daProcessare != null && tir.daProcessare == 0) {
            log.debug("{} EXIT step2a (DaProcessare=0) → mission=null", tag);
            return null;
        }

        // 3) cerca END_EVT dopo START_EVT
        List<Map<String, Object>> endRows = Base.findAll(END_SQL, unitNumber, startTime);
        if (!endRows.isEmpty()) {
            Instant end = ((Timestamp) endRows.get(0).get("event_time")).toInstant();
            log.debug("{} step3 END_EVT trovato: event_time={}", tag, end);
            if (!end.isBefore(eventTime)) {
                log.debug("{} EXIT step3a (END>=CURR) → mission={}", tag, bg);
                return bg;
            }
            log.debug("{} EXIT step3b (END<CURR, fra due missioni) → mission=null", tag);
            return null;
        }
        log.debug("{} step3c nessun END_EVT, valuto DataS", tag);

        // 3c) nessun END_EVT: usa DataS (DataConsegnaEffettiva)
        if (tir.dataS == null) {
            log.debug("{} EXIT step3c DataS null → mission=null", tag);
            return null;
        }
        if (tir.dataS.isBefore(eventTime)) {
            log.debug("{} EXIT step3c.i (DataS={} < CURR) → mission=null", tag, tir.dataS);
            return null;
        }
        if (tir.daProcessare != null && tir.daProcessare == 1) {
            log.debug("{} EXIT step3c.ii (DataS={} >= CURR AND DaProcessare=1) → mission={}",
                    tag, tir.dataS, bg);
            return bg;
        }
        log.debug("{} EXIT step3c condizioni 3c.ii non soddisfatte (DaProcessare={}) → mission=null",
                tag, tir.daProcessare);
        return null;
    }

    /**
     * Estrae il refNum da un transportOrderShortCode tipo "id:210994+refNum:26A03044_06" → "26A03044_06".
     */
    public String extractRefNum(String shortCode) {
        if (isBlank(shortCode)) {
            return null;
        }
        int idx = shortCode.indexOf("refNum:");
        if (idx < 0) {
            return shortCode.trim();
        }
        String after = shortCode.substring(idx + "refNum:".length());
        int plus = after.indexOf('+');
        if (plus >= 0) {
            after = after.substring(0, plus);
        }
        return after.trim();
    }

    private TirRow queryTir(String bg) {
        if (!tirConnectorClient.isConfigured()) {
            return TirRow.empty();
        }
        try {
            // TIR usa il BG senza suffisso "_xx" (es. "26A01234_02" → "26A01234")
            String tirBg = stripSuffix(bg);
            String sql = "SELECT DataS, DaProcessare FROM ElencoRichieste3 WHERE NumRic = '"
                    + tirBg.replace("'", "''") + "'";
            List<Map<String, Object>> rows = tirConnectorClient.executeQuery(sql);
            if (rows.isEmpty()) {
                return TirRow.empty();
            }
            Map<String, Object> row = rows.get(0);
            log.debug("TIR raw row per BG='{}': {}", tirBg, row);
            return new TirRow(parseInstant(getIgnoreCase(row, "DataS")),
                    parseInteger(getIgnoreCase(row, "DaProcessare")));
        } catch (Exception e) {
            log.warn("Query TIR fallita per BG={}: {}", bg, e.getMessage());
            return TirRow.empty();
        }
    }

    private Object getIgnoreCase(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) {
            return v;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private Integer parseInteger(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String s = value.toString().trim();
        // SQL Server bit può arrivare come "true"/"false"
        if (s.equalsIgnoreCase("true"))  return 1;
        if (s.equalsIgnoreCase("false")) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parsing tollerante di DataS (ISO instant, datetime o date), interpretato in zona Europe/Rome. */
    private Instant parseInstant(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String s = value.toString().trim().replace(' ', 'T');
        try {
            return Instant.parse(s);
        } catch (Exception ignore) { /* not an instant */ }
        try {
            return LocalDateTime.parse(s).atZone(TIR_ZONE).toInstant();
        } catch (Exception ignore) { /* not a datetime */ }
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())))
                    .atStartOfDay(TIR_ZONE).toInstant();
        } catch (Exception e) {
            log.warn("Impossibile parsare DataS TIR: {}", value);
            return null;
        }
    }

    /** Rimuove eventuale suffisso "_xx" (es. "26A01234_02" → "26A01234"). */
    private String stripSuffix(String bg) {
        int idx = bg.lastIndexOf('_');
        return idx >= 0 ? bg.substring(0, idx) : bg;
    }

    private boolean isEndEvent(String type) {
        return "DROP".equals(type) || "END_UNLOAD".equals(type);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private record TirRow(Instant dataS, Integer daProcessare) {
        static TirRow empty() {
            return new TirRow(null, null);
        }
        @Override
        public String toString() {
            return "TirRow{DataS=" + dataS + ", DaProcessare=" + daProcessare + "}";
        }
    }
}
