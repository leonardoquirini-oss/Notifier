package it.gruppobernardini.switchmail.util;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifratura delle password IMAP: AES-256-GCM, chiave da env.
 *
 * <p>Formato del BLOB: {@code [0x01 versione][IV 12 byte][ciphertext || tag GCM 16 byte]}. Il byte
 * di versione serve a poter cambiare algoritmo senza indovinare il formato delle righe vecchie.
 *
 * <p>Contratto write-only, come FlowCenter: il GET di un account ritorna {@code passwordSet} e mai
 * il valore; la password si scrive solo alla creazione o con l'endpoint dedicato.
 */
@Component
@Slf4j
public class CredentialCipher {

    public static final String GENERATE_KEY_HINT =
            "generare la chiave con:  openssl rand -base64 32   e metterla in SWITCHMAIL_CREDS_KEY";

    private static final byte VERSION = 0x01;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final String configuredKey;
    private SecretKey key;

    public CredentialCipher(SwitchMailProperties props) {
        this.configuredKey = props.getSecurity().getCredsKey();
    }

    @PostConstruct
    public void init() {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.error("SWITCHMAIL_CREDS_KEY non configurata: impossibile salvare o usare credenziali - {}",
                    GENERATE_KEY_HINT);
            return;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(configuredKey.trim());
            if (raw.length != 32) {
                log.error("SWITCHMAIL_CREDS_KEY di {} byte invece di 32: {}", raw.length, GENERATE_KEY_HINT);
                return;
            }
            this.key = new SecretKeySpec(raw, "AES");
            log.info("Cifratura credenziali attiva (AES-256-GCM)");
        } catch (IllegalArgumentException e) {
            log.error("SWITCHMAIL_CREDS_KEY non e' base64 valido: {}", GENERATE_KEY_HINT);
        }
    }

    /** La UI mostra un banner rosso quando e' false: meglio dirlo prima che al primo salvataggio. */
    public boolean isConfigured() {
        return key != null;
    }

    public byte[] encrypt(String plaintext) {
        requireConfigured();
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[1 + IV_BYTES + ct.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(ct, 0, out, 1 + IV_BYTES, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("cifratura della credenziale fallita", e);
        }
    }

    public String decrypt(byte[] blob) {
        requireConfigured();
        if (blob == null || blob.length == 0) {
            return null;
        }
        if (blob[0] != VERSION) {
            throw new IllegalStateException("formato credenziale sconosciuto (versione " + blob[0] + ")");
        }
        if (blob.length <= 1 + IV_BYTES) {
            throw new IllegalStateException("credenziale cifrata troncata");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 1, iv, 0, IV_BYTES);
            byte[] ct = new byte[blob.length - 1 - IV_BYTES];
            System.arraycopy(blob, 1 + IV_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            // Chiave sbagliata o blob manomesso: sono lo stesso errore, e vanno distinti da un bug.
            throw new IllegalStateException(
                    "credenziale non decifrabile con la chiave corrente (chiave cambiata o dato alterato)", e);
        } catch (Exception e) {
            throw new IllegalStateException("decifratura della credenziale fallita", e);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("SWITCHMAIL_CREDS_KEY non configurata o non valida: " + GENERATE_KEY_HINT);
        }
    }
}
