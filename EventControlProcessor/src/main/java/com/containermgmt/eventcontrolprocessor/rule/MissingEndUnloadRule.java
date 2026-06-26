package com.containermgmt.eventcontrolprocessor.rule;

import com.containermgmt.eventcontrolprocessor.repository.UnitEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Rule: every BEGIN_UNLOAD must be followed by an END_UNLOAD (same day, same unit, within GPS tolerance).
 */
@Component
public class MissingEndUnloadRule extends AbstractMissingPairRule {

    public static final String CODE = "MISSING_END_UNLOAD";

    private final MissingEndUnloadProperties props;

    public MissingEndUnloadRule(UnitEventRepository unitEventRepository,
                                MissingEndUnloadProperties props,
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
        return "BEGIN_UNLOAD";
    }

    @Override
    protected String endType() {
        return "END_UNLOAD";
    }

    @Override
    protected PairRuleSettings settings() {
        return props;
    }
}
