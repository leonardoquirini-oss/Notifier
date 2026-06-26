package com.containermgmt.eventcontrolprocessor.rule;

import com.containermgmt.eventcontrolprocessor.engine.Channel;
import com.containermgmt.eventcontrolprocessor.engine.ControlRule;
import com.containermgmt.eventcontrolprocessor.engine.Situation;
import com.containermgmt.eventcontrolprocessor.model.UnitEvent;
import com.containermgmt.eventcontrolprocessor.repository.UnitEventRepository;
import com.containermgmt.eventcontrolprocessor.util.GeoDistance;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic "opening event without matching closing event" rule.
 *
 * <p>For every {@code beginType} event whose grace period has elapsed, a {@code endType} event must
 * exist for the SAME unit, on the SAME calendar day, at roughly the SAME GPS position (within a meter
 * tolerance). If none exists, a situation is raised.</p>
 *
 * <p>Concrete rules only declare the event type pair, a code, and their settings.</p>
 */
@Slf4j
public abstract class AbstractMissingPairRule implements ControlRule {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    protected final UnitEventRepository unitEventRepository;
    protected final ObjectMapper objectMapper;

    protected AbstractMissingPairRule(UnitEventRepository unitEventRepository, ObjectMapper objectMapper) {
        this.unitEventRepository = unitEventRepository;
        this.objectMapper = objectMapper;
    }

    /** Opening event type, e.g. BEGIN_LOAD. */
    protected abstract String beginType();

    /** Closing event type that must pair the opening one, e.g. END_LOAD. */
    protected abstract String endType();

    /** This rule's configuration. */
    protected abstract PairRuleSettings settings();

    @Override
    public boolean isEnabled() {
        return settings().isEnabled();
    }

    @Override
    public List<Situation> evaluate() {
        PairRuleSettings s = settings();
        List<UnitEvent> begins = unitEventRepository.findRipeEvents(
                beginType(), s.getLookbackHours(), s.getGraceHours());

        List<Situation> situations = new ArrayList<>();
        for (UnitEvent begin : begins) {
            if (hasMatchingEnd(begin, s)) {
                continue;
            }
            situations.add(buildSituation(begin, s));
        }
        return situations;
    }

    private boolean hasMatchingEnd(UnitEvent begin, PairRuleSettings s) {
        List<UnitEvent> candidates = unitEventRepository.findClosingCandidates(
                endType(), begin.unitNumber(), begin.eventTime());
        if (candidates.isEmpty()) {
            return false;
        }
        // No GPS on the opening event -> cannot apply the meter tolerance; any same-day close pairs it.
        if (begin.latitude() == null || begin.longitude() == null) {
            return true;
        }
        for (UnitEvent end : candidates) {
            if (end.latitude() == null || end.longitude() == null) {
                continue;
            }
            double distance = GeoDistance.haversineMeters(
                    begin.latitude(), begin.longitude(), end.latitude(), end.longitude());
            if (distance <= s.getToleranceMeters()) {
                return true;
            }
        }
        return false;
    }

    private Situation buildSituation(UnitEvent begin, PairRuleSettings s) {
        String day = begin.eventTime() != null ? begin.eventTime().format(DAY) : "?";
        String when = begin.eventTime() != null ? begin.eventTime().format(TS) : "?";

        String title = endType() + " mancante";
        String message = String.format(
                "Unita %s: %s del %s senza %s corrispondente nello stesso giorno entro %.0f m.",
                begin.unitNumber(), beginType(), when, endType(), s.getToleranceMeters());

        return Situation.builder()
                .ruleCode(code())
                .dedupKey(code() + ":" + begin.idUnitEvent())
                .unitNumber(begin.unitNumber())
                .title(title)
                .message(message)
                .notificationType(s.getNotificationType())
                .channels(Channel.parse(s.getChannels()))
                .groupCode(s.getGroupCode())
                .emailRecipients(s.getEmailRecipients())
                .detailsJson(buildDetails(begin, day, s))
                .build();
    }

    private String buildDetails(UnitEvent begin, String day, PairRuleSettings s) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rule", code());
        details.put("begin_type", beginType());
        details.put("end_type", endType());
        details.put("begin_id_unit_event", begin.idUnitEvent());
        details.put("unit_number", begin.unitNumber());
        details.put("begin_event_time", begin.eventTime() != null ? begin.eventTime().toString() : null);
        details.put("day", day);
        details.put("begin_latitude", begin.latitude());
        details.put("begin_longitude", begin.longitude());
        details.put("tolerance_meters", s.getToleranceMeters());
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            return null;
        }
    }
}
