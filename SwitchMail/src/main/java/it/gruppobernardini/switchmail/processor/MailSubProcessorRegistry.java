package it.gruppobernardini.switchmail.processor;

import it.gruppobernardini.switchmail.dto.ProcessorDescriptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Indice dei sub-processori per id, con risoluzione degli alias. */
@Component
@Slf4j
public class MailSubProcessorRegistry {

    private final List<MailSubProcessor> processors;
    private final Map<String, MailSubProcessor> byId = new LinkedHashMap<>();
    private final Map<String, MailSubProcessor> byAlias = new HashMap<>();

    public MailSubProcessorRegistry(List<MailSubProcessor> processors) {
        this.processors = processors;
    }

    /**
     * Un id duplicato e' un bug di codice che renderebbe non deterministica la risoluzione delle
     * regole: qui si fallisce il boot, e si fallisce subito.
     */
    @PostConstruct
    public void init() {
        for (MailSubProcessor p : processors) {
            String id = p.id();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException(p.getClass().getName() + " non dichiara un id");
            }
            MailSubProcessor clash = byId.put(id, p);
            if (clash != null) {
                throw new IllegalStateException("due sub-processori con lo stesso id '" + id + "': "
                        + clash.getClass().getName() + " e " + p.getClass().getName());
            }
        }
        for (MailSubProcessor p : processors) {
            for (String alias : p.aliasIds()) {
                if (byId.containsKey(alias)) {
                    throw new IllegalStateException("l'alias '" + alias + "' di " + p.getClass().getName()
                            + " collide con l'id canonico di un altro processore");
                }
                MailSubProcessor clash = byAlias.put(alias, p);
                if (clash != null) {
                    throw new IllegalStateException("l'alias '" + alias + "' e' dichiarato da "
                            + clash.getClass().getName() + " e da " + p.getClass().getName());
                }
            }
        }
        log.info("Sub-processori registrati: {}", byId.keySet());
    }

    public Optional<MailSubProcessor> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        MailSubProcessor p = byId.get(id);
        if (p != null) {
            return Optional.of(p);
        }
        p = byAlias.get(id);
        if (p != null) {
            log.warn("Il processore '{}' e' un id storico di '{}': aggiornare la regola che lo usa", id, p.id());
            return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Per il runtime: un id ignoto e' un errore terminale, non uno skip silenzioso. */
    public MailSubProcessor require(String id) {
        return find(id).orElseThrow(() -> new TerminalMailProcessingException("UNKNOWN_PROCESSOR",
                "processore sconosciuto: '" + id + "' (disponibili: " + byId.keySet() + ")", null));
    }

    public boolean exists(String id) {
        return find(id).isPresent();
    }

    public List<ProcessorDescriptor> descriptors() {
        return byId.values().stream()
                .map(p -> new ProcessorDescriptor(p.id(), p.displayName(), p.description(), p.paramSpecs()))
                .sorted(Comparator.comparing(ProcessorDescriptor::displayName))
                .toList();
    }

    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }
}
