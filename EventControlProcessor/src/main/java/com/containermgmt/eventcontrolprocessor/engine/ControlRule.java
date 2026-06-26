package com.containermgmt.eventcontrolprocessor.engine;

import java.util.List;

/**
 * A control rule detects anomalous situations on events and returns them.
 *
 * <p>This is the extension point of the engine: to add a new check, create a Spring {@code @Component}
 * implementing this interface. Rules are NOT restricted to event-pairing logic — a rule may detect
 * any condition it can evaluate (missing pair, stale state, threshold breach, etc.).</p>
 *
 * <p>Implementations must be side-effect free with respect to notifications: they only <b>detect</b>.
 * Persistence ("notify once") and dispatch are handled centrally by the {@link ControlEngine}.</p>
 */
public interface ControlRule {

    /** Stable unique code for this rule, stored in evt_event_checks.rule_code. */
    String code();

    /** Whether this rule is currently enabled (driven by its own config). */
    boolean isEnabled();

    /** Evaluate the rule and return every currently-open situation it finds. */
    List<Situation> evaluate();
}
