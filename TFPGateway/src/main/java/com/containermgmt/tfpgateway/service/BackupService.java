package com.containermgmt.tfpgateway.service;

import com.containermgmt.tfpgateway.config.ActiveJDBCConfig;

import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class BackupService {

    private final ActiveJDBCConfig activeJDBCConfig;

    public BackupService(ActiveJDBCConfig activeJDBCConfig) {
        this.activeJDBCConfig = activeJDBCConfig;
    }

    public long countForBackup(LocalDate dateFrom, LocalDate dateTo) {
        try {
            activeJDBCConfig.openConnection();
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS cnt FROM evt_raw_events");
            List<Object> params = appendDateWhere(sql, dateFrom, dateTo);
            Object result = Base.firstCell(sql.toString(), params.toArray());
            return result != null ? ((Number) result).longValue() : 0;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    public void exportAndOptionallyDelete(LocalDate dateFrom, LocalDate dateTo,
                                          String format, boolean deleteAfterBackup,
                                          int sqlCommitEvery, OutputStream out) throws IOException {
        List<Map<String, Object>> rows;
        try {
            activeJDBCConfig.openConnection();
            StringBuilder sql = new StringBuilder(
                    "SELECT id_event, message_id, event_type, event_time, processed_at, created_at, " +
                    "checksum, payload::text AS payload FROM evt_raw_events");
            List<Object> params = appendDateWhere(sql, dateFrom, dateTo);
            sql.append(" ORDER BY event_time ASC");
            rows = Base.findAll(sql.toString(), params.toArray());
        } finally {
            activeJDBCConfig.closeConnection();
        }

        switch (format.toUpperCase()) {
            case "CSV"    -> writeCsv(rows, out);
            case "JSON"   -> writeJson(rows, out);
            case "NDJSON" -> writeNdjson(rows, out);
            default       -> writeSql(rows, out, sqlCommitEvery);
        }

        if (deleteAfterBackup) {
            deleteRange(dateFrom, dateTo);
        }
    }

    private void deleteRange(LocalDate dateFrom, LocalDate dateTo) {
        try {
            activeJDBCConfig.openConnection();
            Base.exec("BEGIN");

            StringBuilder subSql = new StringBuilder(
                    "SELECT message_id FROM evt_raw_events");
            List<Object> subParams = appendDateWhere(subSql, dateFrom, dateTo);
            Base.exec(
                "DELETE FROM evt_error_ingestion WHERE message_id IN (" + subSql + ")",
                subParams.toArray()
            );

            StringBuilder delSql = new StringBuilder("DELETE FROM evt_raw_events");
            List<Object> delParams = appendDateWhere(delSql, dateFrom, dateTo);
            Base.exec(delSql.toString(), delParams.toArray());

            Base.exec("COMMIT");
            log.info("Backup delete completed for range {} - {}", dateFrom, dateTo);
        } catch (Exception e) {
            try { Base.exec("ROLLBACK"); } catch (Exception ignored) {}
            log.error("Backup delete failed, rolled back", e);
            throw e;
        } finally {
            activeJDBCConfig.closeConnection();
        }
    }

    // Appends WHERE clause to sql and returns params list
    private List<Object> appendDateWhere(StringBuilder sql, LocalDate dateFrom, LocalDate dateTo) {
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();

        if (dateFrom != null) {
            conditions.add("event_time >= ?::timestamp");
            params.add(dateFrom.toString());
        }
        if (dateTo != null) {
            conditions.add("event_time < (?::date + interval '1 day')");
            params.add(dateTo.toString());
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        return params;
    }

    // ── Writers ────────────────────────────────────────────────────────────────

    private void writeSql(List<Map<String, Object>> rows, OutputStream out, int commitEvery) throws IOException {
        int batchSize = commitEvery > 0 ? commitEvery : 1000;
        int count = 0;
        write(out, "BEGIN;\n\n");
        for (Map<String, Object> row : rows) {
            String payload = str(row.get("payload"));
            String checksum = str(row.get("checksum"));
            String line = "INSERT INTO evt_raw_events " +
                    "(id_event, message_id, event_type, event_time, processed_at, created_at, checksum, payload) VALUES (" +
                    sqlLiteral(row.get("id_event")) + ", " +
                    sqlLiteral(str(row.get("message_id"))) + ", " +
                    sqlLiteral(str(row.get("event_type"))) + ", " +
                    sqlLiteral(str(row.get("event_time"))) + ", " +
                    sqlLiteral(str(row.get("processed_at"))) + ", " +
                    sqlLiteral(str(row.get("created_at"))) + ", " +
                    sqlLiteral(checksum) + ", " +
                    "'" + (payload != null ? payload.replace("'", "''") : "") + "'::jsonb" +
                    ") ON CONFLICT (message_id) DO NOTHING;\n";
            write(out, line);
            count++;
            if (count % batchSize == 0) {
                write(out, "\nCOMMIT;\n\nBEGIN;\n\n");
            }
        }
        write(out, "\nCOMMIT;\n");
    }

    private void writeCsv(List<Map<String, Object>> rows, OutputStream out) throws IOException {
        write(out, "id_event,message_id,event_type,event_time,processed_at,created_at,checksum,payload\n");
        for (Map<String, Object> row : rows) {
            String line = csvField(row.get("id_event")) + "," +
                    csvField(row.get("message_id")) + "," +
                    csvField(row.get("event_type")) + "," +
                    csvField(row.get("event_time")) + "," +
                    csvField(row.get("processed_at")) + "," +
                    csvField(row.get("created_at")) + "," +
                    csvField(row.get("checksum")) + "," +
                    csvField(row.get("payload")) + "\n";
            write(out, line);
        }
    }

    private void writeJson(List<Map<String, Object>> rows, OutputStream out) throws IOException {
        write(out, "[\n");
        for (int i = 0; i < rows.size(); i++) {
            write(out, rowToJson(rows.get(i)));
            if (i < rows.size() - 1) write(out, ",");
            write(out, "\n");
        }
        write(out, "]\n");
    }

    private void writeNdjson(List<Map<String, Object>> rows, OutputStream out) throws IOException {
        for (Map<String, Object> row : rows) {
            write(out, rowToJson(row) + "\n");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private String sqlLiteral(Object o) {
        if (o == null) return "NULL";
        return "'" + o.toString().replace("'", "''") + "'";
    }

    private String csvField(Object o) {
        if (o == null) return "";
        String s = o.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String rowToJson(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id_event\":").append(jsonValue(row.get("id_event"), false));
        sb.append(",\"message_id\":").append(jsonValue(row.get("message_id"), true));
        sb.append(",\"event_type\":").append(jsonValue(row.get("event_type"), true));
        sb.append(",\"event_time\":").append(jsonValue(row.get("event_time"), true));
        sb.append(",\"processed_at\":").append(jsonValue(row.get("processed_at"), true));
        sb.append(",\"created_at\":").append(jsonValue(row.get("created_at"), true));
        sb.append(",\"checksum\":").append(jsonValue(row.get("checksum"), true));
        // payload is already a valid JSON string — embed it as-is
        String payload = str(row.get("payload"));
        sb.append(",\"payload\":").append(payload != null ? payload : "null");
        sb.append("}");
        return sb.toString();
    }

    private String jsonValue(Object value, boolean asString) {
        if (value == null) return "null";
        if (!asString && value instanceof Number) return value.toString();
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
