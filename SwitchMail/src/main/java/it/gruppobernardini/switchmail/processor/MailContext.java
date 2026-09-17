package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.service.BerlinkApiClient;

/**
 * Tutto cio' che un processore puo' vedere: la mail, la regola che l'ha selezionata, i parametri
 * effettivi, il numero di tentativo e il client BERLink <b>gia' scelto</b>.
 *
 * <p>Il client sta qui e non dentro il processore: cosi' la scelta tra client reale e client di
 * registrazione avviene in un solo punto (chi costruisce il contesto), e un processore non puo'
 * fisicamente fare una chiamata reale dalla schermata di test.
 *
 * @param attempt 1 per il primo tentativo
 */
public record MailContext(
        ParsedMail mail,
        RuleConfig rule,
        ProcessorParams params,
        int attempt,
        boolean dryRun,
        BerlinkApiClient api) {

    public MailContext {
        if (mail == null) {
            throw new IllegalArgumentException("MailContext senza mail");
        }
        if (params == null) {
            params = ProcessorParams.empty();
        }
    }
}
