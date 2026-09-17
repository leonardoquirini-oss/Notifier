package it.gruppobernardini.switchmail.dto;

import it.gruppobernardini.switchmail.processor.ParamSpec;

import java.util.List;

/** Payload di GET /rules/api/processors: la tendina e il form parametri della UI nascono da qui. */
public record ProcessorDescriptor(
        String id,
        String displayName,
        String description,
        List<ParamSpec> paramSpecs) {
}
