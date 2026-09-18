package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.config.SwitchMailProperties;
import it.gruppobernardini.switchmail.util.CredentialCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static CredentialCipher cipher(String key) {
        SwitchMailProperties props = new SwitchMailProperties();
        props.getSecurity().setCredsKey(key);
        CredentialCipher c = new CredentialCipher(props);
        c.init();
        return c;
    }

    @Test
    void roundTrip() {
        CredentialCipher c = cipher(randomKey());
        assertThat(c.isConfigured()).isTrue();

        byte[] blob = c.encrypt("p4ssw0rd con spazi e àccenti");
        assertThat(blob[0]).isEqualTo((byte) 0x01);
        assertThat(blob.length).isGreaterThan(1 + 12 + 16);
        assertThat(c.decrypt(blob)).isEqualTo("p4ssw0rd con spazi e àccenti");
    }

    @Test
    @DisplayName("due cifrature della stessa password danno blob diversi (IV casuale)")
    void ivCasuale() {
        CredentialCipher c = cipher(randomKey());
        assertThat(c.encrypt("stessa")).isNotEqualTo(c.encrypt("stessa"));
    }

    @Test
    @DisplayName("chiave diversa: non decifra, e lo dice invece di restituire spazzatura")
    void chiaveSbagliata() {
        byte[] blob = cipher(randomKey()).encrypt("segreto");
        CredentialCipher altra = cipher(randomKey());

        assertThatThrownBy(() -> altra.decrypt(blob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non decifrabile");
    }

    @Test
    @DisplayName("ciphertext alterato: il tag GCM se ne accorge")
    void bloboAlterato() {
        CredentialCipher c = cipher(randomKey());
        byte[] blob = c.encrypt("segreto");
        blob[blob.length - 1] ^= 0x01;

        assertThatThrownBy(() -> c.decrypt(blob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non decifrabile");
    }

    @Test
    void bloboTroncatoOVersioneIgnota() {
        CredentialCipher c = cipher(randomKey());
        assertThatThrownBy(() -> c.decrypt(new byte[]{0x01, 0x00, 0x00}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("troncata");
        assertThatThrownBy(() -> c.decrypt(new byte[]{0x09, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("formato credenziale sconosciuto");
    }

    @Test
    @DisplayName("chiave assente o non valida: nessun crash al boot, ma ogni uso fallisce con le istruzioni")
    void chiaveNonConfigurata() {
        for (String key : new String[]{null, "", "non-base64!!", Base64.getEncoder().encodeToString(new byte[16])}) {
            CredentialCipher c = cipher(key);
            assertThat(c.isConfigured()).as("chiave '%s'", key).isFalse();
            assertThatThrownBy(() -> c.encrypt("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("openssl rand -base64 32");
        }
    }

    @Test
    void nullEVuotoPassanoSenzaCifratura() {
        CredentialCipher c = cipher(randomKey());
        assertThat(c.encrypt(null)).isNull();
        assertThat(c.decrypt(null)).isNull();
        assertThat(c.decrypt(new byte[0])).isNull();
    }
}
