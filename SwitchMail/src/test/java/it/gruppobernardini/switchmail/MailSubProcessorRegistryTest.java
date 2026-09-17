package it.gruppobernardini.switchmail;

import it.gruppobernardini.switchmail.processor.AbstractMailSubProcessor;
import it.gruppobernardini.switchmail.processor.MailContext;
import it.gruppobernardini.switchmail.processor.MailSubProcessor;
import it.gruppobernardini.switchmail.processor.MailSubProcessorRegistry;
import it.gruppobernardini.switchmail.processor.ProcessingOutcome;
import it.gruppobernardini.switchmail.processor.TerminalMailProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailSubProcessorRegistryTest {

    private static class Fake extends AbstractMailSubProcessor {
        private final String id;
        private final Set<String> aliases;

        Fake(String id, String... aliases) {
            this.id = id;
            this.aliases = Set.of(aliases);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Set<String> aliasIds() {
            return aliases;
        }

        @Override
        public String displayName() {
            return "Fake " + id;
        }

        @Override
        protected ProcessingOutcome handle(MailContext ctx) {
            return ProcessingOutcome.success("ok", Map.of("id", id), null);
        }
    }

    private static MailSubProcessorRegistry registry(MailSubProcessor... processors) {
        MailSubProcessorRegistry r = new MailSubProcessorRegistry(List.of(processors));
        r.init();
        return r;
    }

    @Test
    @DisplayName("id duplicato: il boot fallisce, la risoluzione delle regole non puo' essere ambigua")
    void idDuplicato() {
        MailSubProcessorRegistry r = new MailSubProcessorRegistry(List.of(new Fake("dup"), new Fake("dup")));
        assertThatThrownBy(r::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stesso id 'dup'");
    }

    @Test
    @DisplayName("un alias risolve al processore canonico")
    void aliasRisolve() {
        MailSubProcessorRegistry r = registry(new Fake("train-departure", "treno", "train_departure"));
        assertThat(r.find("treno")).get().extracting(MailSubProcessor::id).isEqualTo("train-departure");
        assertThat(r.find("train_departure")).isPresent();
        assertThat(r.find("train-departure")).isPresent();
    }

    @Test
    @DisplayName("un alias che collide con un id canonico e' un bug: boot fallito")
    void collisioneAliasCanonico() {
        MailSubProcessorRegistry r = new MailSubProcessorRegistry(
                List.of(new Fake("alfa"), new Fake("beta", "alfa")));
        assertThatThrownBy(r::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collide con l'id canonico");
    }

    @Test
    void aliasDichiaratoDaDueProcessori() {
        MailSubProcessorRegistry r = new MailSubProcessorRegistry(
                List.of(new Fake("alfa", "vecchio"), new Fake("beta", "vecchio")));
        assertThatThrownBy(r::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("require su id ignoto: terminale con UNKNOWN_PROCESSOR, mai uno skip silenzioso")
    void requireIdIgnoto() {
        MailSubProcessorRegistry r = registry(new Fake("alfa"));
        assertThatThrownBy(() -> r.require("inesistente"))
                .isInstanceOf(TerminalMailProcessingException.class)
                .hasMessageContaining("processore sconosciuto")
                .extracting(e -> ((TerminalMailProcessingException) e).errorType())
                .isEqualTo("UNKNOWN_PROCESSOR");
    }

    @Test
    void descriptorsOrdinatiPerDisplayName() {
        MailSubProcessorRegistry r = registry(new Fake("zeta"), new Fake("alfa"));
        assertThat(r.descriptors()).extracting("displayName").containsExactly("Fake alfa", "Fake zeta");
        assertThat(r.exists("alfa")).isTrue();
        assertThat(r.exists("mai-visto")).isFalse();
    }
}
