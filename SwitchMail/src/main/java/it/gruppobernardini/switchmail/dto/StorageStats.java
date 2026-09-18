package it.gruppobernardini.switchmail.dto;

/**
 * Quanto spazio occupa l'archivio, e quanto se ne recupera compattando.
 *
 * @param reclaimableBytes spazio gia' liberato dalle cancellazioni ma non ancora restituito al
 *                         filesystem: SQLite non rimpicciolisce il file da solo, serve un VACUUM
 */
public record StorageStats(
        int logRows,
        int attemptRows,
        int rawRows,
        long rawBytes,
        long rawOriginalBytes,
        long dbSizeBytes,
        long reclaimableBytes) {
}
