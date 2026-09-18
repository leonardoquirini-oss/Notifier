package it.gruppobernardini.switchmail.support;

import it.gruppobernardini.switchmail.processor.AbstractMailSubProcessor;
import it.gruppobernardini.switchmail.processor.MailContext;
import it.gruppobernardini.switchmail.processor.ParamSpec;
import it.gruppobernardini.switchmail.processor.ProcessingOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Processore di test: registra le invocazioni e fa quello che gli si dice. */
public class RecordingProcessor extends AbstractMailSubProcessor {

    private final String id;
    private final List<MailContext> invocations = new ArrayList<>();
    private Function<MailContext, ProcessingOutcome> behaviour =
            ctx -> ProcessingOutcome.success("elaborata", Map.of("subject", String.valueOf(ctx.mail().subject())), null);

    public RecordingProcessor(String id) {
        this.id = id;
    }

    public RecordingProcessor behaviour(Function<MailContext, ProcessingOutcome> behaviour) {
        this.behaviour = behaviour;
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return "Processore di test " + id;
    }

    @Override
    public List<ParamSpec> paramSpecs() {
        return List.of(ParamSpec.string("etichetta", "Etichetta", false, null, null));
    }

    @Override
    protected ProcessingOutcome handle(MailContext ctx) {
        invocations.add(ctx);
        return behaviour.apply(ctx);
    }

    public List<MailContext> invocations() {
        return invocations;
    }

    public int count() {
        return invocations.size();
    }
}
