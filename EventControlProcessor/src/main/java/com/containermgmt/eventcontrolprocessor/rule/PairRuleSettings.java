package com.containermgmt.eventcontrolprocessor.rule;

import java.util.List;

/**
 * Settings shared by every "BEGIN_x without END_x" pairing rule.
 * Implemented by each rule's @ConfigurationProperties class (Lombok getters satisfy this contract).
 */
public interface PairRuleSettings {

    boolean isEnabled();

    int getGraceHours();

    int getLookbackHours();

    double getToleranceMeters();

    List<String> getChannels();

    String getGroupCode();

    String getNotificationType();

    List<String> getEmailRecipients();
}
