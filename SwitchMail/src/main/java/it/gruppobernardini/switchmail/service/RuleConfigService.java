package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.dao.RuleDao;
import it.gruppobernardini.switchmail.model.FieldCheck;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.processor.MailSubProcessor;
import it.gruppobernardini.switchmail.processor.MailSubProcessorRegistry;
import it.gruppobernardini.switchmail.processor.ProcessorParams;
import it.gruppobernardini.switchmail.util.JsonUtil;
import it.gruppobernardini.switchmail.util.RegexUtil;
import it.gruppobernardini.switchmail.util.TimestampUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD delle regole, con la validazione che chiude il ciclo di feedback: quello che il processore
 * dichiara con {@code paramSpecs()} e {@code validateRule()} viene applicato <b>al salvataggio</b>,
 * quindi un parametro sbagliato diventa un 400 in rosso sotto il campo invece di una mail
 * dead-lettered qualche ora dopo.
 */
@Service
@Slf4j
public class RuleConfigService {

    private final RuleDao dao;
    private final MailSubProcessorRegistry registry;
    private final Clock clock;

    public RuleConfigService(RuleDao dao, MailSubProcessorRegistry registry, Clock clock) {
        this.dao = dao;
        this.registry = registry;
        this.clock = clock;
    }

    public List<RuleConfig> findAll() {
        return dao.findAll();
    }

    public Optional<RuleConfig> find(long id) {
        return dao.find(id);
    }

    public RuleConfig require(long id) {
        return dao.find(id).orElseThrow(() -> new IllegalArgumentException("regola inesistente: #" + id));
    }

    public List<RuleConfig> findEnabled() {
        return dao.findEnabled();
    }

    public List<RuleConfig> findForAccount(long accountId) {
        return dao.findForEvaluation(accountId);
    }

    /** Hit degli ultimi 7 giorni, per la colonna "usi" della lista regole. */
    public Map<Long, Integer> hitCountsLast7Days() {
        return dao.hitCounts(TimestampUtil.format(clock.instant().minus(7, ChronoUnit.DAYS)));
    }

    public long create(RuleConfig rule) {
        RuleConfig normalized = validate(rule);
        try {
            long id = dao.insert(normalized);
            RegexUtil.clearCache();
            log.info("Regola creata: #{} {}", id, normalized.name());
            return id;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("esiste gia' una regola con il nome '" + rule.name() + "'");
        }
    }

    public void update(RuleConfig rule) {
        require(rule.id());
        RuleConfig normalized = validate(rule);
        try {
            dao.update(normalized);
            RegexUtil.clearCache();
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("esiste gia' una regola con il nome '" + rule.name() + "'");
        }
    }

    public void setEnabled(long id, boolean enabled) {
        require(id);
        dao.setEnabled(id, enabled);
        RegexUtil.clearCache();
    }

    public void setPriority(long id, int priority) {
        require(id);
        dao.setPriority(id, priority);
        RegexUtil.clearCache();
    }

    public void delete(long id) {
        require(id);
        dao.delete(id);
        RegexUtil.clearCache();
    }

    /**
     * Valida e normalizza. Ritorna la regola con l'id canonico del processore: se l'utente (o una
     * riga vecchia) usa un alias, il salvataggio lo riscrive, cosi' gli alias decadono da soli.
     */
    RuleConfig validate(RuleConfig rule) {
        if (rule.name() == null || rule.name().isBlank()) {
            throw new IllegalArgumentException("il nome della regola e' obbligatorio");
        }
        if (rule.sender().isWildcard() && rule.subject().isWildcard() && rule.attachment().isWildcard()) {
            throw new IllegalArgumentException(
                    "almeno uno tra mittente, oggetto e allegato deve essere valorizzato: "
                            + "una regola che matcha tutto prenderebbe ogni mail della casella");
        }
        if (rule.maxAttempts() < 1) {
            throw new IllegalArgumentException("il numero massimo di tentativi deve essere almeno 1");
        }
        checkRegex(rule.sender(), "mittente");
        checkRegex(rule.subject(), "oggetto");
        checkRegex(rule.attachment(), "allegato");

        MailSubProcessor processor = registry.find(rule.processorId()).orElseThrow(
                () -> new IllegalArgumentException("processore sconosciuto: '" + rule.processorId()
                        + "' (disponibili: " + registry.ids() + ")"));

        JsonUtil.requireValidJson(rule.paramsJson(), "parametri della regola");
        ProcessorParams params = ProcessorParams.ofJson(rule.paramsJson(), processor.paramSpecs());
        processor.validateRule(rule, params);

        return new RuleConfig(rule.id(), rule.name().trim(), rule.description(), rule.accountId(), rule.enabled(),
                rule.priority(), rule.stopOnMatch(), rule.sender(), rule.subject(), rule.attachment(),
                rule.requireAttachment(), processor.id(), JsonUtil.write(params.asMap()), rule.maxAttempts(),
                rule.createdAt(), rule.updatedAt());
    }

    private void checkRegex(FieldCheck check, String field) {
        if (check.isWildcard() || check.mode() != FieldCheck.MatchMode.REGEX) {
            return;
        }
        try {
            RegexUtil.compile(check.pattern(), check.caseSensitive());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": " + e.getMessage());
        }
    }
}
