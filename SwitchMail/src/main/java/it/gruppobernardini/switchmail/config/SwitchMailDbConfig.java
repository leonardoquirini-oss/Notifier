package it.gruppobernardini.switchmail.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DataSource SQLite, esposto come UNICO bean {@link DataSource} dell'applicazione.
 *
 * <p>Diversamente da GeofencingDbConfig di TFPEventIngester, qui il DataSource non va nascosto:
 * quel workaround serviva a non far arretrare DataSourceAutoConfiguration e lasciare ActiveJDBC
 * senza il Postgres. SwitchMail non ha ne' Postgres ne' ActiveJDBC, quindi esporlo fa guadagnare
 * gratis DataSourceHealthIndicator (l'HEALTHCHECK del container si accorge di un DB corrotto o di
 * un volume smontato), le metriche hikaricp.*, il DataSourceTransactionManager usato dalla pipeline
 * e il JdbcTemplate auto-configurato senza @Qualifier in ogni costruttore.
 *
 * <p>Tutto lo SQL sta nel package dao/. Questo file e' l'altro unico posto dove compaiono dettagli
 * specifici di SQLite (URL, PRAGMA, pool) - vedi IMPLEMENTATION_PLAN.md sezione 8.
 */
@Configuration
@Slf4j
public class SwitchMailDbConfig {

    private final SwitchMailProperties props;

    public SwitchMailDbConfig(SwitchMailProperties props) {
        this.props = props;
    }

    @Bean
    public DataSource dataSource() {
        Path dbFile = new File(props.getDb().getPath()).getAbsoluteFile().toPath();
        try {
            Path parent = dbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Impossibile creare la directory del DB SQLite: " + dbFile, e);
        }

        // connectionInitSql di Hikari accetta UNA sola statement, quindi il set completo di PRAGMA
        // va sull'URL: il driver xerial le applica a ogni connessione fisica.
        //
        // TRAPPOLA (verificata): xerial applica solo le PRAGMA che conosce e lascia le altre
        // ATTACCATE AL NOME DEL FILE - "wal_autocheckpoint=2000" produceva un file chiamato
        // letteralmente "switchmail.db?wal_autocheckpoint=2000", con il DB vero da nessuna parte.
        // Qui restano solo pragma riconosciute; wal_autocheckpoint tiene il suo default (1000
        // pagine, ~4 MB di WAL), piu' che sufficiente a questo volume. SchemaMigrations verifica
        // comunque a ogni avvio quale file sia stato aperto davvero.
        String url = "jdbc:sqlite:" + dbFile
                + "?journal_mode=WAL"
                + "&synchronous=NORMAL"
                + "&foreign_keys=on"
                + "&busy_timeout=5000"
                + "&journal_size_limit=536870912";

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setPoolName("SwitchMail-SQLite-Pool");
        // Un poll ogni due minuti e una manciata di utenti UI: un solo scrittore basta.
        // Se la pagina dei log rallentasse su tabella grande, la risposta e' un secondo pool
        // read-only (in WAL i lettori girano insieme allo scrittore), non alzare questo.
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA foreign_keys = ON");

        log.info("DB SQLite su {}", dbFile);
        return new HikariDataSource(cfg);
    }
}
