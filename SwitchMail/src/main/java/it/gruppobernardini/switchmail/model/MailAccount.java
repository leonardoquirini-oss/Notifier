package it.gruppobernardini.switchmail.model;

import java.time.Instant;

/**
 * Una riga di mail_account.
 *
 * <p>La password viaggia solo cifrata: {@code passwordEncrypted} e' il BLOB AES-GCM e non viene mai
 * serializzato verso la UI, che riceve soltanto {@link #passwordSet()}.
 */
public record MailAccount(
        Long id,
        String name,
        String host,
        int port,
        boolean useSsl,
        boolean startTls,
        boolean trustAllCerts,
        String username,
        byte[] passwordEncrypted,
        String folder,
        AccessMode accessMode,
        PostAction postAction,
        String postActionFolder,
        String pollCron,
        int maxMessagesPerPoll,
        int initialLookbackDays,
        int connectTimeoutMs,
        int readTimeoutMs,
        boolean enabled,
        Instant lastPollAt,
        String lastPollStatus,
        String lastPollError,
        Integer lastPollFetched,
        int consecutiveFailures,
        Instant createdAt,
        Instant updatedAt) {

    public boolean passwordSet() {
        return passwordEncrypted != null && passwordEncrypted.length > 0;
    }

    public boolean isReadOnly() {
        return accessMode == AccessMode.READ_ONLY;
    }

    public String describe() {
        return name + " (" + username + "@" + host + ":" + port + "/" + folder + ")";
    }
}
