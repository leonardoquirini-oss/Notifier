package it.gruppobernardini.switchmail.model;

/**
 * Come il servizio tratta la casella.
 *
 * <p>READ_ONLY e' la modalita' B del design: la casella e' del dipendente, il servizio la guarda e
 * basta. OWNED e' la modalita' A: casella dedicata che riceve un forward, di cui il servizio e'
 * padrone. Un CHECK sullo schema impedisce qualsiasi post-action su un account READ_ONLY.
 */
public enum AccessMode {
    READ_ONLY, OWNED
}
