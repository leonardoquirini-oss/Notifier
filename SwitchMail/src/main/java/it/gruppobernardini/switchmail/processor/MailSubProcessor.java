package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.model.RuleConfig;

import java.util.List;
import java.util.Set;

/**
 * Il contratto SPI di un sub-processore: un tipo di mail, un'azione verso BERLink.
 *
 * <p>Non c'e' nessun {@code supports(rule)}: la regola punta al processore per id, scelto
 * esplicitamente dalla UI. Un secondo meccanismo di selezione implicito potrebbe solo contraddire
 * quello esplicito. Al suo posto c'e' {@link #validateRule}, che non <i>sceglie</i> ma
 * <i>dichiara</i> di saper lavorare con quella regola, al salvataggio (400 con feedback immediato)
 * e al boot.
 */
public interface MailSubProcessor {

    /**
     * Identificatore stabile, scritto a mano come costante, <b>mai derivato dal nome della classe</b>:
     * cosi' sopravvive a rinomine e spostamenti di package. E' quello che finisce in
     * mail_rule.processor_id.
     */
    String id();

    /** Id precedenti dopo una rinomina: il registry li risolve con un WARN e la UI riscrive il canonico. */
    default Set<String> aliasIds() {
        return Set.of();
    }

    String displayName();

    default String description() {
        return "";
    }

    /** Da qui la UI genera il form dei parametri e il server li valida. */
    default List<ParamSpec> paramSpecs() {
        return List.of();
    }

    /**
     * Chiamato al salvataggio della regola e al boot.
     *
     * @throws IllegalArgumentException con un messaggio per l'operatore, se la regola non e'
     *                                  utilizzabile con questo processore
     */
    default void validateRule(RuleConfig rule, ProcessorParams params) {
    }

    /**
     * Esegue. Ritorna SUCCESS o SKIPPED; <b>ogni fallimento si lancia</b>.
     *
     * <p>Le sottoclassi implementano {@code handle()}: qui il metodo e' reso final dalla base
     * astratta, cosi' il wrapper che classifica le eccezioni non e' aggirabile.
     */
    ProcessingOutcome process(MailContext ctx);
}
