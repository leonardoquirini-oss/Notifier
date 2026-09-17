package it.gruppobernardini.switchmail.dao;

import it.gruppobernardini.switchmail.model.MailFolderState;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Optional;

/** High-water mark del fetch incrementale. */
@Repository
public class MailFolderStateDao {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MailFolderStateDao(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static final RowMapper<MailFolderState> MAPPER = (rs, n) -> new MailFolderState(
            rs.getLong("account_id"),
            rs.getString("folder"),
            rs.getLong("uid_validity"),
            rs.getLong("last_uid"),
            TimestampUtil.parse(rs.getString("updated_at")));

    public Optional<MailFolderState> find(long accountId, String folder) {
        return jdbc.query("""
                SELECT account_id, folder, uid_validity, last_uid, updated_at
                FROM mail_folder_state WHERE account_id = ? AND folder = ?
                """, MAPPER, accountId, folder).stream().findFirst();
    }

    public void upsert(long accountId, String folder, long uidValidity, long lastUid) {
        jdbc.update("""
                INSERT INTO mail_folder_state (account_id, folder, uid_validity, last_uid, updated_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT (account_id, folder) DO UPDATE SET
                    uid_validity = excluded.uid_validity,
                    last_uid = excluded.last_uid,
                    updated_at = excluded.updated_at
                """, accountId, folder, uidValidity, lastUid, TimestampUtil.now(clock));
    }

    /** Avanza il high-water mark solo in avanti: un batch fuori ordine non deve farlo arretrare. */
    public void advanceLastUid(long accountId, String folder, long lastUid) {
        jdbc.update("""
                UPDATE mail_folder_state SET last_uid = ?, updated_at = ?
                WHERE account_id = ? AND folder = ? AND last_uid < ?
                """, lastUid, TimestampUtil.now(clock), accountId, folder, lastUid);
    }
}
