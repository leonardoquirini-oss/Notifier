package it.gruppobernardini.switchmail.dao;

import it.gruppobernardini.switchmail.dto.StorageStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Misure e manutenzione del file SQLite. */
@Repository
@Slf4j
public class MaintenanceDao {

    private final JdbcTemplate jdbc;

    public MaintenanceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StorageStats stats() {
        long pageSize = scalar("PRAGMA page_size");
        long pageCount = scalar("PRAGMA page_count");
        long freeList = scalar("PRAGMA freelist_count");

        return new StorageStats(
                (int) scalar("SELECT COUNT(*) FROM mail_processing_log"),
                (int) scalar("SELECT COUNT(*) FROM mail_processing_attempt"),
                (int) scalar("SELECT COUNT(*) FROM mail_raw"),
                scalar("SELECT COALESCE(SUM(LENGTH(content_gzip)), 0) FROM mail_raw"),
                scalar("SELECT COALESCE(SUM(original_size), 0) FROM mail_raw"),
                pageSize * pageCount,
                pageSize * freeList);
    }

    /**
     * Restituisce al filesystem lo spazio delle righe cancellate.
     *
     * <p>Serve perche' SQLite non rimpicciolisce il file da solo: le pagine liberate restano nel
     * file e vengono riusate. Senza questo, chi cancella mezzo archivio vede il file identico e
     * pensa che la cancellazione non abbia funzionato.
     *
     * <p>VACUUM riscrive il database e non puo' girare dentro una transazione; con il pool a una
     * connessione blocca il resto per la sua durata, che su un archivio di questa taglia e' un
     * istante.
     */
    public void vacuum() {
        long before = scalar("PRAGMA page_size") * scalar("PRAGMA page_count");
        jdbc.execute("VACUUM");
        long after = scalar("PRAGMA page_size") * scalar("PRAGMA page_count");
        log.info("VACUUM: {} -> {} byte", before, after);
    }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
