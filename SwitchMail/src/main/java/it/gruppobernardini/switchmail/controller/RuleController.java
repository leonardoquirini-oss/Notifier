package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.dto.ProcessorDescriptor;
import it.gruppobernardini.switchmail.model.RuleConfig;
import it.gruppobernardini.switchmail.processor.MailSubProcessorRegistry;
import it.gruppobernardini.switchmail.service.ProcessorValidationRunner;
import it.gruppobernardini.switchmail.service.RuleConfigService;
import it.gruppobernardini.switchmail.util.JsonUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static it.gruppobernardini.switchmail.controller.Payloads.bool;
import static it.gruppobernardini.switchmail.controller.Payloads.fieldCheck;
import static it.gruppobernardini.switchmail.controller.Payloads.intg;
import static it.gruppobernardini.switchmail.controller.Payloads.lng;
import static it.gruppobernardini.switchmail.controller.Payloads.require;
import static it.gruppobernardini.switchmail.controller.Payloads.str;

/**
 * Pagina e API delle regole.
 *
 * <p>Ogni riga porta {@code processorAvailable}: se una regola punta a un processore che non esiste
 * piu', la UI lo mostra in rosso e blocca il salvataggio invece di riscrivere in silenzio un binding
 * rotto.
 */
@Controller
public class RuleController {

    private final RuleConfigService ruleService;
    private final MailSubProcessorRegistry registry;
    private final ProcessorValidationRunner validationRunner;

    public RuleController(RuleConfigService ruleService, MailSubProcessorRegistry registry,
                          ProcessorValidationRunner validationRunner) {
        this.ruleService = ruleService;
        this.registry = registry;
        this.validationRunner = validationRunner;
    }

    @GetMapping("/rules")
    public String page() {
        return "rules";
    }

    @GetMapping("/rules/api")
    @ResponseBody
    public List<Map<String, Object>> list() {
        Map<Long, Integer> hits = ruleService.hitCountsLast7Days();
        return ruleService.findAll().stream().map(r -> view(r, hits)).toList();
    }

    /** La tendina della UI e il form dei parametri nascono da qui. */
    @GetMapping("/rules/api/processors")
    @ResponseBody
    public List<ProcessorDescriptor> processors() {
        return registry.descriptors();
    }

    @GetMapping("/rules/api/{id}")
    @ResponseBody
    public Map<String, Object> get(@PathVariable long id) {
        return view(ruleService.require(id), ruleService.hitCountsLast7Days());
    }

    @PostMapping("/rules/api")
    @ResponseBody
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        long id = ruleService.create(fromBody(null, body));
        validationRunner.revalidate();
        return view(ruleService.require(id), Map.of());
    }

    @PutMapping("/rules/api/{id}")
    @ResponseBody
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ruleService.update(fromBody(id, body));
        validationRunner.revalidate();
        return view(ruleService.require(id), Map.of());
    }

    @PatchMapping("/rules/api/{id}/enabled")
    @ResponseBody
    public Map<String, Object> setEnabled(@PathVariable long id, @RequestBody Map<String, Object> body) {
        ruleService.setEnabled(id, bool(body.get("enabled"), true));
        return Map.of("id", id, "enabled", bool(body.get("enabled"), true));
    }

    /** Salvataggio inline della priorita' dalla lista. */
    @PatchMapping("/rules/api/{id}/priority")
    @ResponseBody
    public Map<String, Object> setPriority(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int priority = intg(body.get("priority"), 100);
        ruleService.setPriority(id, priority);
        return Map.of("id", id, "priority", priority);
    }

    @DeleteMapping("/rules/api/{id}")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable long id) {
        ruleService.delete(id);
        validationRunner.revalidate();
        return Map.of("deleted", id);
    }

    private RuleConfig fromBody(Long id, Map<String, Object> body) {
        Object params = body.get("params");
        String paramsJson;
        if (params == null) {
            paramsJson = "{}";
        } else if (params instanceof String s) {
            paramsJson = s.isBlank() ? "{}" : s;
        } else {
            paramsJson = JsonUtil.write(params);
        }

        return new RuleConfig(id,
                require(body.get("name"), "name"),
                str(body.get("description")),
                lng(body.get("accountId")),
                bool(body.get("enabled"), true),
                intg(body.get("priority"), 100),
                bool(body.get("stopOnMatch"), true),
                fieldCheck(body, "sender"),
                fieldCheck(body, "subject"),
                fieldCheck(body, "attachment"),
                bool(body.get("requireAttachment"), false),
                require(body.get("processorId"), "processorId"),
                paramsJson,
                intg(body.get("maxAttempts"), 3),
                null, null);
    }

    private Map<String, Object> view(RuleConfig r, Map<Long, Integer> hits) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", r.id());
        view.put("name", r.name());
        view.put("description", r.description());
        view.put("accountId", r.accountId());
        view.put("enabled", r.enabled());
        view.put("priority", r.priority());
        view.put("stopOnMatch", r.stopOnMatch());
        view.put("senderPattern", r.sender().pattern());
        view.put("senderMatch", r.sender().mode().name());
        view.put("senderCaseSensitive", r.sender().caseSensitive());
        view.put("subjectPattern", r.subject().pattern());
        view.put("subjectMatch", r.subject().mode().name());
        view.put("subjectCaseSensitive", r.subject().caseSensitive());
        view.put("attachmentPattern", r.attachment().pattern());
        view.put("attachmentMatch", r.attachment().mode().name());
        view.put("attachmentCaseSensitive", r.attachment().caseSensitive());
        view.put("requireAttachment", r.requireAttachment());
        view.put("processorId", r.processorId());
        view.put("processorAvailable", registry.exists(r.processorId()));
        view.put("params", JsonUtil.readMap(r.paramsJson()));
        view.put("maxAttempts", r.maxAttempts());
        view.put("hits7d", hits.getOrDefault(r.id(), 0));
        view.put("problem", validationRunner.brokenRules().get(r.id()));
        return view;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
