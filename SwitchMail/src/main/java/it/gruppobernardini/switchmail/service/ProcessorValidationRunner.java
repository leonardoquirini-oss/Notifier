package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.dao.RuleDao;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.processor.MailSubProcessor;
import it.gruppobernardini.switchmail.processor.MailSubProcessorRegistry;
import it.gruppobernardini.switchmail.processor.ProcessorParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifica al boot che ogni regola punti a un processore esistente e con parametri validi.
 *
 * <p><b>Il boot non fallisce.</b> E' l'unica deviazione dal riflesso fail-fast, ed e' motivata: la UI
 * che ripara un processor_id rotto gira dentro questo stesso processo, quindi un boot fallito
 * lascerebbe l'operatore senza lo strumento per aggiustare il dato, costretto a entrare in sqlite3
 * dentro un volume Docker. La risposta e' a strati: errore in log, notifica admin, badge rosso nella
 * lista regole, DOWN in /api/health/ready, e a runtime una dead-letter esplicita - mai uno skip
 * silenzioso.
 */
@Component
@Slf4j
public class ProcessorValidationRunner implements ApplicationRunner {

    private final RuleDao ruleDao;
    private final MailSubProcessorRegistry registry;
    private final AdminNotifier notifier;

    /** ruleId -> motivo, letto da /api/health/ready e dalla lista regole. */
    private volatile Map<Long, String> broken = Map.of();

    public ProcessorValidationRunner(RuleDao ruleDao, MailSubProcessorRegistry registry, AdminNotifier notifier) {
        this.ruleDao = ruleDao;
        this.registry = registry;
        this.notifier = notifier;
    }

    @Override
    public void run(ApplicationArguments args) {
        revalidate();
    }

    public Map<Long, String> revalidate() {
        Map<Long, String> found = new LinkedHashMap<>();
        for (RuleConfig rule : ruleDao.findAll()) {
            String problem = problemOf(rule);
            if (problem != null) {
                found.put(rule.id(), problem);
                log.error("Regola {} non utilizzabile: {}", rule.label(), problem);
            }
        }
        this.broken = Map.copyOf(found);

        if (!found.isEmpty()) {
            List<String> lines = new ArrayList<>();
            found.forEach((id, why) -> lines.add("#" + id + ": " + why));
            String detail = String.join("\n", lines);
            log.error("{} regole non utilizzabili su {} totali", found.size(), ruleDao.findAll().size());
            notifier.brokenRules(detail);
        }
        return this.broken;
    }

    private String problemOf(RuleConfig rule) {
        var processor = registry.find(rule.processorId());
        if (processor.isEmpty()) {
            return "processore sconosciuto '" + rule.processorId() + "'";
        }
        MailSubProcessor p = processor.get();
        try {
            ProcessorParams params = ProcessorParams.ofJson(rule.paramsJson(), p.paramSpecs());
            p.validateRule(rule, params);
            return null;
        } catch (IllegalArgumentException e) {
            return "parametri non validi: " + e.getMessage();
        } catch (RuntimeException e) {
            return "validazione fallita: " + e.getMessage();
        }
    }

    public Map<Long, String> brokenRules() {
        return broken;
    }

    public boolean healthy() {
        return broken.isEmpty();
    }
}
