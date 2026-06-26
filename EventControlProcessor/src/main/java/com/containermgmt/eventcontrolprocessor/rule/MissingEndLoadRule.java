package com.containermgmt.eventcontrolprocessor.rule;

import com.containermgmt.eventcontrolprocessor.repository.UnitEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Rule: every BEGIN_LOAD must be followed by an END_LOAD (same day, same unit, within GPS tolerance).
 */
@Component
public class MissingEndLoadRule extends AbstractMissingPairRule {

    public static final String CODE = "MISSING_END_LOAD";

    private final MissingEndLoadProperties props;

    public MissingEndLoadRule(UnitEventRepository unitEventRepository,
                              MissingEndLoadProperties props,
                              ObjectMapper objectMapper) {
        super(unitEventRepository, objectMapper);
        this.props = props;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected String beginType() {
        return "BEGIN_LOAD";
    }

    @Override
    protected String endType() {
        return "END_LOAD";
    }

    @Override
    protected PairRuleSettings settings() {
        return props;
    }
}
