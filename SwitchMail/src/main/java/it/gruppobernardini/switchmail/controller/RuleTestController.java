package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.dto.RuleTestRequest;
import it.gruppobernardini.switchmail.dto.RuleTestResult;
import it.gruppobernardini.switchmail.service.RuleTestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static it.gruppobernardini.switchmail.controller.Payloads.bool;
import static it.gruppobernardini.switchmail.controller.Payloads.lng;
import static it.gruppobernardini.switchmail.controller.Payloads.str;

/** Il tester: valuta le regole e, su richiesta, esegue in prova. Non scrive nulla. */
@Controller
public class RuleTestController {

    private final RuleTestService ruleTestService;

    public RuleTestController(RuleTestService ruleTestService) {
        this.ruleTestService = ruleTestService;
    }

    @GetMapping("/ruletest")
    public String page() {
        return "ruletest";
    }

    @PostMapping("/ruletest/api/run")
    @ResponseBody
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        List<String> attachments = new ArrayList<>();
        if (body.get("attachmentNames") instanceof List<?> list) {
            list.forEach(v -> {
                String name = str(v);
                if (name != null) {
                    attachments.add(name);
                }
            });
        }
        RuleTestRequest request = new RuleTestRequest(lng(body.get("accountId")), str(body.get("from")),
                str(body.get("fromName")), str(body.get("subject")), str(body.get("body")), attachments,
                null, bool(body.get("dryRun"), false));
        return view(ruleTestService.test(request));
    }

    /** Percorso .eml: passa dal vero extractor, quindi valida anche charset e allegati. */
    @PostMapping("/ruletest/api/upload")
    @ResponseBody
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) Long accountId,
                                      @RequestParam(defaultValue = "false") boolean dryRun) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("nessun file caricato");
        }
        RuleTestRequest request = new RuleTestRequest(accountId, null, null, null, null, List.of(),
                file.getBytes(), dryRun);
        return view(ruleTestService.test(request));
    }

    private Map<String, Object> view(RuleTestResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mail", Payloads.mailView(result.mail()));
        out.put("trace", result.trace());
        out.put("winner", result.winner());
        out.put("effectiveParams", result.effectiveParams());
        out.put("paramsError", result.paramsError());
        out.put("dryRun", result.dryRun());
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
