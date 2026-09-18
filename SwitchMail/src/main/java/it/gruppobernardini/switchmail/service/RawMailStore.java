package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.dao.RawMailDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Archivia il MIME grezzo gzippato.
 *
 * <p>Attivo di default: finche' i formati sono ignoti, poter scaricare un .eml reale dal browser di
 * log e metterlo in src/test/resources/mail/ e' l'affordance di debug con il rapporto valore/costo
 * piu' alto del progetto. Rende anche il retry indipendente dal fatto che la mail sia ancora in
 * casella. Su mail testuali il gzip fa 5-10x: a ~50 mail/giorno per 30 giorni sono pochi MB.
 *
 * <p>Best effort: un fallimento si logga e <b>non</b> blocca l'elaborazione.
 */
@Service
@Slf4j
public class RawMailStore {

    private final RawMailDao dao;
    private final SwitchMailProperties props;

    public RawMailStore(RawMailDao dao, SwitchMailProperties props) {
        this.dao = dao;
        this.props = props;
    }

    public void store(long logId, byte[] raw) {
        if (!props.getMail().isStoreRaw() || raw == null || raw.length == 0) {
            return;
        }
        if (raw.length > props.getMail().getRawMaxBytes()) {
            log.warn("MIME di {} byte oltre il limite di {}: non archiviato per il log #{}",
                    raw.length, props.getMail().getRawMaxBytes(), logId);
            return;
        }
        try {
            dao.save(logId, gzip(raw), raw.length);
        } catch (Exception e) {
            log.error("Archiviazione del MIME fallita per il log #{}: {}", logId, e.getMessage());
        }
    }

    /** Il .eml scaricabile da /logs e la sorgente del retry quando la mail non e' piu' in casella. */
    public Optional<byte[]> load(long logId) {
        try {
            return dao.findGzip(logId).map(RawMailStore::gunzip);
        } catch (Exception e) {
            log.error("Lettura del MIME fallita per il log #{}: {}", logId, e.getMessage());
            return Optional.empty();
        }
    }

    static byte[] gzip(byte[] data) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
            gz.finish();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("compressione del MIME fallita", e);
        }
    }

    static byte[] gunzip(byte[] data) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("decompressione del MIME fallita", e);
        }
    }
}
