package it.gruppobernardini.switchmail.model;

import java.time.Instant;

/**
 * High-water mark del fetch incrementale per (account, folder).
 *
 * <p>Derivabile da MAX(uid) sul log, ma una riga a due interi rende il confronto di UIDVALIDITY una
 * point-read invece di un'aggregazione su una tabella che cresce, e da' all'handler di invalidazione
 * un posto ovvio dove scrivere.
 */
public record MailFolderState(long accountId, String folder, long uidValidity, long lastUid, Instant updatedAt) {
}
