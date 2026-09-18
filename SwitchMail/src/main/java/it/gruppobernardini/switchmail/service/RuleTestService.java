package it.gruppobernardini.switchmail.service;

import it.gruppobernardini.switchmail.dto.DryRunResult;
import it.gruppobernardini.switchmail.dto.RuleTestRequest;
import it.gruppobernardini.switchmail.dto.RuleTestResult;
import it.gruppobernardini.switchmail.model.MailAttachment;
import it.gruppobernardini.switchmail.model.MatchedRules;
import it.gruppobernardini.switchmail.model.ParsedMail;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.processor.MailContext;
import it.gruppobernardini.switchmail.processor.MailProcessingException;
import it.gruppobernardini.switchmail.processor.MailSubProcessor;
import it.gruppobernardini.switchmail.processor.MailSubProcessorRegistry;
import it.gruppobernardini.switchmail.processor.ProcessingOutcome;
import it.gruppobernardini.switchmail.processor.ProcessorParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Il tester non persistente: valuta le regole, mostra il "perche' no" e, su richiesta, esegue il
 * processore in prova.
 *
 * <p>Due garanzie: nulla viene scritto nel log, e il contesto riceve il client di registrazione,
 * quindi nessuna HTTP esce dal processo. Non e' una convenzione da rispettare: e' l'unico client che
 * il processore ha a disposizione in questo percorso.
 */
@Service
@Slf4j
public class RuleTestService {

    private final RuleConfigService ruleService;
    private final RuleMatcher matcher;
    private final MailSubProcessorRegistry registry;
    private final MailContentExtractor extractor;
    private final BerlinkApiClientFactory clientFactory;
    private final AccountService accountService;
    private final Clock clock;

    public RuleTestService(RuleConfigService ruleService, RuleMatcher matcher, MailSubProcessorRegistry registry,
                           MailContentExtractor extractor, BerlinkApiClientFactory clientFactory,
                           AccountService accountService, Clock clock) {
        this.ruleService = ruleService;
        this.matcher = matcher;
        this.registry = registry;
        this.extractor = extractor;
        this.clientFactory = clientFactory;
        this.accountService = accountService;
        this.clock = clock;
    }

    public RuleTestResult test(RuleTestRequest request) {
        ParsedMail mail = request.hasEml() ? fromEml(request) : synthesize(request);

        List<RuleConfig> rules = request.accountId() != null
                ? ruleService.findForAccount(request.accountId())
                : ruleService.findEnabled();
        MatchedRules matched = matcher.evaluate(mail, rules);

        RuleConfig winner = matched.first();
        if (winner == null) {
            return new RuleTestResult(mail, matched.trace(), null, Map.of(), null, null);
        }

        MailSubProcessor processor = registry.find(winner.processorId()).orElse(null);
        if (processor == null) {
            return new RuleTestResult(mail, matched.trace(), winner, Map.of(),
                    "processore sconosciuto: '" + winner.processorId() + "'", null);
        }

        ProcessorParams params;
        try {
            params = ProcessorParams.ofJson(winner.paramsJson(), processor.paramSpecs());
            processor.validateRule(winner, params);
        } catch (IllegalArgumentException e) {
            return new RuleTestResult(mail, matched.trace(), winner, Map.of(), e.getMessage(), null);
        }

        DryRunResult dryRun = request.dryRun() ? dryRun(processor, winner, params, mail) : null;
        return new RuleTestResult(mail, matched.trace(), winner, params.asMap(), null, dryRun);
    }

    private DryRunResult dryRun(MailSubProcessor processor, RuleConfig rule, ProcessorParams params, ParsedMail mail) {
        var recording = clientFactory.recording(mail, rule.id());
        MailContext ctx = new MailContext(mail, rule, params, 1, true, recording);
        try {
            ProcessingOutcome outcome = processor.process(ctx);
            return new DryRunResult(outcome.status().name(), outcome.message(), outcome.extractedJson(),
                    outcome.actionRef(), null, null, recording.calls());
        } catch (MailProcessingException e) {
            return new DryRunResult("ERROR", e.getMessage(), null, null, e.errorType(), e.retryable(),
                    recording.calls());
        } catch (RuntimeException e) {
            return new DryRunResult("ERROR", e.getMessage(), null, null, "UNEXPECTED", false, recording.calls());
        }
    }

    private ParsedMail fromEml(RuleTestRequest request) {
        long accountId = request.accountId() == null ? 0L : request.accountId();
        String accountName = request.accountId() == null ? "(nessuna casella)"
                : accountService.find(request.accountId()).map(a -> a.name())
                        .orElse("(casella #" + request.accountId() + ")");
        return extractor.extract(request.eml(), accountId, accountName, "INBOX", 0L, 0L);
    }

    /**
     * Mail sintetica dai campi del form. Gli allegati hanno nome ma contenuto vuoto: bastano a
     * verificare il matching, non a far girare un parser - per quello si carica un .eml vero.
     */
    private ParsedMail synthesize(RuleTestRequest request) {
        List<MailAttachment> attachments = new ArrayList<>();
        for (String name : request.attachmentNames()) {
            if (name != null && !name.isBlank()) {
                attachments.add(new MailAttachment(name.trim(), "application/octet-stream",
                        StandardCharsets.UTF_8, new byte[0], false));
            }
        }
        Instant now = clock.instant();
        String accountName = request.accountId() == null ? "(nessuna casella)"
                : accountService.find(request.accountId()).map(a -> a.name()).orElse("(casella)");
        return new ParsedMail(request.accountId() == null ? 0L : request.accountId(), accountName, "INBOX",
                0L, 0L, null, request.from(), request.fromName(), List.of(), request.subject(), now, now,
                request.body() == null ? "" : request.body(), null, attachments, 0,
                List.of("mail sintetica: allegati senza contenuto, caricare un .eml per un test completo"));
    }
}
