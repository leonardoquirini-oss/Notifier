package it.gruppobernardini.switchmail.dao;

import it.gruppobernardini.switchmail.util.TimestampUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Il MIME grezzo gzippato. Persistenza pura: comprimere e decomprimere e' compito di RawMailStore. */
@Repository
public class RawMailDao {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RawMailDao(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void save(long logId, byte[] gzip, int originalSize) {
        jdbc.update("""
                INSERT INTO mail_raw (log_id, content_gzip, original_size, stored_at)
                VALUES (?,?,?,?)
                ON CONFLICT (log_id) DO UPDATE SET
                    content_gzip = excluded.content_gzip,
                    original_size = excluded.original_size,
                    stored_at = excluded.stored_at
                """, logId, gzip, originalSize, TimestampUtil.now(clock));
    }

    public Optional<byte[]> findGzip(long logId) {
        return jdbc.query("SELECT content_gzip FROM mail_raw WHERE log_id = ?",
                        (rs, n) -> rs.getBytes("content_gzip"), logId)
                .stream().findFirst();
    }

    public boolean exists(long logId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM mail_raw WHERE log_id = ?", Integer.class, logId);
        return n != null && n > 0;
    }

    /**
     * Elimina solo il MIME archiviato delle righe indicate: il log resta, con la sua memoria di
     * dedup e il suo storico. E' la cancellazione senza controindicazioni, ed e' anche quella che
     * libera quasi tutto lo spazio, perche' il peso sta nei blob.
     */
    public int deleteByLogIds(List<Long> logIds) {
        if (logIds == null || logIds.isEmpty()) {
            return 0;
        }
        String placeholders = "?,".repeat(logIds.size() - 1) + "?";
        return jdbc.update("DELETE FROM mail_raw WHERE log_id IN (" + placeholders + ")", logIds.toArray());
    }

    /** Retention: il log resta consultabile, il blob no. */
    public int deleteOlderThan(Instant threshold) {
        return jdbc.update("DELETE FROM mail_raw WHERE stored_at < ?", TimestampUtil.format(threshold));
    }
}
