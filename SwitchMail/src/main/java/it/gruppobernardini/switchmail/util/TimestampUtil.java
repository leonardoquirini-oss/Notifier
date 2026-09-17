package it.gruppobernardini.switchmail.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Conversione {@link Instant} &lt;-&gt; colonna temporale.
 *
 * <p>I timestamp si scrivono come TEXT ISO-8601 UTC con millisecondi ("2026-09-17T14:03:11.482Z"):
 * ordinabili lessicograficamente, e con abbastanza risoluzione da distinguere due mail arrivate
 * nello stesso secondo. Sono prodotti qui, in Java, sopra il bean {@link Clock}; lo schema non ha
 * nessun DEFAULT con strftime()/datetime('now').
 *
 * <p>Passando a Postgres (colonne timestamptz) questo e' l'unico file da toccare per la mappatura
 * del tipo temporale: vedi IMPLEMENTATION_PLAN.md sezione 8.
 */
public final class TimestampUtil {

    public static final DateTimeFormatter ISO_UTC_MS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private TimestampUtil() {
    }

    /** Valore da scrivere in colonna. Null-safe. */
    public static String format(Instant instant) {
        return instant == null ? null : ISO_UTC_MS.format(instant);
    }

    /** "Adesso" secondo il Clock iniettato, gia' formattato per il DB. */
    public static String now(Clock clock) {
        return format(clock.instant());
    }

    /** Valore letto da colonna. Null-safe; una stringa non parsabile e' un bug, non un dato. */
    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
