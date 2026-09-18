package it.gruppobernardini.switchmail.dto;

import it.gruppobernardini.switchmail.model.ParsedMail;

/** Una mail materializzata + il suo MIME grezzo, gia' fuori dalla connessione IMAP. */
public record FetchedMail(ParsedMail parsed, byte[] raw) {
}
