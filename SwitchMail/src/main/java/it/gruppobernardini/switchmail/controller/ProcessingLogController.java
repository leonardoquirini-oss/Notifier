package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.dto.LogFilter;
import it.gruppobernardini.switchmail.dto.LogPage;
import it.gruppobernardini.switchmail.model.ProcessingLogEntry;
import it.gruppobernardini.switchmail.model.ProcessingStatus;
import it.gruppobernardini.switchmail.service.ProcessingLogService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static it.gruppobernardini.switchmail.controller.Payloads.lng;
import static it.gruppobernardini.switchmail.controller.Payloads.str;

/** La home: il registro delle elaborazioni. */
@Controller
public class ProcessingLogController {

    private final ProcessingLogService logService;

    public ProcessingLogController(ProcessingLogService logService) {
        this.logService = logService;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/logs";
    }

    @GetMapping("/logs")
    public String page() {
        return "logs";
    }

    @GetMapping("/logs/api")
    @ResponseBody
    public LogPage list(@RequestParam(required = false) Long accountId,
                        @RequestParam(required = false) List<String> status,
                        @RequestParam(required = false) Long ruleId,
                        @RequestParam(required = false) String text,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(required = false) String cursorCreatedAt,
                        @RequestParam(required = false) Long cursorId,
                        @RequestParam(defaultValue = "50") int limit) {
        List<ProcessingStatus> statuses = new ArrayList<>();
        if (status != null) {
            for (String s : status) {
                try {
                    statuses.add(ProcessingStatus.valueOf(s.trim().toUpperCase()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("stato sconosciuto: " + s);
                }
            }
        }
        return logService.page(new LogFilter(accountId, statuses, ruleId, text, from, to,
                cursorCreatedAt, cursorId, limit));
    }

    @GetMapping("/logs/api/counts")
    @ResponseBody
    public Map<String, Integer> counts() {
        return logService.counts();
    }

    @GetMapping("/logs/api/{id}")
    @ResponseBody
    public Map<String, Object> detail(@PathVariable long id) {
        ProcessingLogEntry entry = logService.require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entry", entry);
        out.put("attempts", logService.attempts(id));
        return out;
    }

    /**
     * Riprova. Vale anche per SKIPPED e NO_RULE: e' cosi' che una regola appena scritta si applica a
     * una mail vecchia, ed e' il ciclo che rende iterativa la scrittura delle regole.
     */
    @PostMapping("/logs/api/{id}/retry")
    @ResponseBody
    public Map<String, Object> retry(@PathVariable long id) {
        logService.retry(id);
        return Map.of("entry", logService.require(id));
    }

    @PostMapping("/logs/api/retry")
    @ResponseBody
    public Map<String, Object> retryMany(@RequestBody Map<String, Object> body) {
        Object raw = body.get("ids");
        List<Long> ids = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(v -> ids.add(lng(v)));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("nessuna riga selezionata");
        }
        return Map.of("retried", logService.retryAll(ids), "requested", ids.size());
    }

    /** Segna risolta: svuota la dead-letter senza fingere che la mail sia riuscita. */
    @PostMapping("/logs/api/{id}/resolve")
    @ResponseBody
    public Map<String, Object> resolve(@PathVariable long id, @RequestBody Map<String, Object> body) {
        logService.resolve(id, str(body.get("note")));
        return Map.of("entry", logService.require(id));
    }

    /** Il .eml da mettere in src/test/resources/mail/ come fixture del parser. */
    @GetMapping("/logs/api/{id}/eml")
    @ResponseBody
    public ResponseEntity<byte[]> eml(@PathVariable long id) {
        byte[] raw = logService.eml(id).orElseThrow(
                () -> new IllegalArgumentException("MIME non archiviato per la riga #" + id));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mail-" + id + ".eml\"")
                .contentType(MediaType.parseMediaType("message/rfc822"))
                .body(raw);
    }

    /** Cosa ha visto l'extractor: risponde a "il parser ha visto l'allegato?" senza debugger. */
    @GetMapping("/logs/api/{id}/parsed")
    @ResponseBody
    public Map<String, Object> parsed(@PathVariable long id) {
        return Payloads.mailView(logService.parsedView(id).orElseThrow(
                () -> new IllegalArgumentException("MIME non archiviato per la riga #" + id)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
