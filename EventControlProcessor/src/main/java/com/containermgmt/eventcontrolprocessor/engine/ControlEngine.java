package com.containermgmt.eventcontrolprocessor.engine;

import com.containermgmt.eventcontrolprocessor.config.ControlProperties;
import com.containermgmt.eventcontrolprocessor.notify.NotificationDispatcher;
import com.containermgmt.eventcontrolprocessor.repository.SituationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled orchestrator: runs every enabled {@link ControlRule}, persists each detected
 * {@link Situation} idempotently, and dispatches a notification only for situations seen for the
 * first time.
 *
 * <p>"Notify once" is enforced by {@link SituationRepository#insertIfAbsent}: an insert that hits the
 * (rule_code, dedup_key) unique constraint is a no-op and returns false, so no duplicate notification
 * is sent — robust across restarts and overlapping ticks.</p>
 */
@Component
@Slf4j
public class ControlEngine {

    private final List<ControlRule> rules;
    private final SituationRepository situationRepository;
    private final NotificationDispatcher dispatcher;
    private final ControlProperties properties;

    public ControlEngine(List<ControlRule> rules,
                         SituationRepository situationRepository,
                         NotificationDispatcher dispatcher,
                         ControlProperties properties) {
        this.rules = rules;
        this.situationRepository = situationRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(cron = "${control.poll-cron}")
    public void run() {
        if (!properties.isEnabled()) {
            log.debug("Control engine disabled; skipping tick");
            return;
        }
        if (rules.isEmpty()) {
            log.debug("No control rules registered; nothing to evaluate");
            return;
        }
        log.info("Control engine tick: evaluating {} rule(s)", rules.size());
        for (ControlRule rule : rules) {
            evaluateRule(rule);
        }
    }

    private void evaluateRule(ControlRule rule) {
        if (!rule.isEnabled()) {
            log.debug("Rule {} disabled; skipping", rule.code());
            return;
        }
        List<Situation> situations;
        try {
            situations = rule.evaluate();
        } catch (Exception e) {
            log.error("Rule {} evaluation failed: {}", rule.code(), e.getMessage(), e);
            return;
        }
        if (situations == null || situations.isEmpty()) {
            log.debug("Rule {}: no situations", rule.code());
            return;
        }

        int newCount = 0;
        for (Situation s : situations) {
            try {
                boolean isNew = situationRepository.insertIfAbsent(s);
                if (!isNew) {
                    continue; // already detected and notified before -> notify once
                }
                newCount++;
                dispatcher.dispatch(s);
                situationRepository.markNotified(s.getRuleCode(), s.getDedupKey());
            } catch (Exception e) {
                log.error("Rule {}: failed to process situation dedupKey={}: {}",
                        rule.code(), s.getDedupKey(), e.getMessage(), e);
            }
        }
        log.info("Rule {}: {} situation(s) found, {} new (notified)", rule.code(), situations.size(), newCount);
    }
}
