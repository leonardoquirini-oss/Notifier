package it.gruppobernardini.switchmail.dto;

import java.util.List;

/**
 * Esito di un fetch.
 *
 * @param uidValidityChanged true se la cartella ha una UIDVALIDITY diversa da quella attesa: in quel
 *                           caso non viene fatto nessun fetch e la decisione passa a chi ha chiamato
 */
public record ImapFetchResult(long uidValidity, boolean uidValidityChanged, List<FetchedMail> mails) {

    public ImapFetchResult {
        mails = mails == null ? List.of() : List.copyOf(mails);
    }

    public long maxUid() {
        return mails.stream().mapToLong(m -> m.parsed().uid()).max().orElse(0L);
    }
}
