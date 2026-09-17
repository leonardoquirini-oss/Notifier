package it.gruppobernardini.switchmail.model;

import java.time.Instant;
import java.util.List;

/**
 * Una riga di mail_processing_log: lo snapshot completo di cosa e' arrivato, quale regola l'ha
 * presa, com'e' finita. Sopravvive alla cancellazione della mail dalla casella.
 */
public record ProcessingLogEntry(
        long id,
        long accountId,
        String accountName,
        String folder,
        long uidValidity,
        long uid,
        String internetMessageId,
        String mailFrom,
        String mailFromName,
        String mailSubject,
        Instant mailSentAt,
        Instant mailReceivedAt,
        List<String> attachmentNames,
        Integer mailSizeBytes,
        Long ruleId,
        String ruleName,
        List<Long> matchedRuleIds,
        String processorId,
        ProcessingStatus status,
        int attempt,
        int maxAttempts,
        String message,
        String extractedJson,
        String actionRef,
        List<String> warnings,
        String errorType,
        String errorMessage,
        String errorStack,
        Long durationMs,
        Instant claimedAt,
        Instant processedAt,
        Instant nextRetryAt,
        Instant resolvedAt,
        String resolvedNote,
        Instant createdAt,
        boolean rawAvailable) {

    public String dedupKey() {
        return accountId + ":" + folder + ":" + uidValidity + ":" + uid;
    }
}
