package it.gruppobernardini.switchmail.dto;

/**
 * Una cartella visibile all'account, con il nome <b>completo</b> da incollare nel campo Cartella.
 *
 * @param namespace  "personale", "altri utenti" o "condivise": con Cyrus/Dovecot una casella
 *                   condivisa non si raggiunge con una login speciale, ma come cartella in un
 *                   namespace diverso dal proprio
 * @param selectable false per i nodi che sono solo contenitori (\NoSelect): non si possono pollare
 */
public record FolderInfo(String fullName, String namespace, boolean selectable, Integer messageCount) {
}
