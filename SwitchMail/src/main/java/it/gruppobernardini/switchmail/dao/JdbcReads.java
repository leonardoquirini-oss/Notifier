package it.gruppobernardini.switchmail.dao;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Letture nullable di colonne numeriche.
 *
 * <p>Serve perche' {@code rs.getObject()} non garantisce il tipo Java: il driver SQLite restituisce
 * un Integer per i valori che ci stanno, quindi un cast a Long esplode a runtime su una riga
 * qualsiasi. Postgres restituirebbe Long per un bigint. Con getLong/getInt + wasNull il tipo lo
 * decide il codice, non il driver - ed e' una delle cose che rendono sostituibile il database.
 */
final class JdbcReads {

    private JdbcReads() {
    }

    static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
