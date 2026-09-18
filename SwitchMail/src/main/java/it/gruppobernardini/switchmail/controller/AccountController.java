package it.gruppobernardini.switchmail.controller;

import it.gruppobernardini.switchmail.dto.MailPreview;
import it.gruppobernardini.switchmail.dto.TestConnectionResult;
import it.gruppobernardini.switchmail.model.AccessMode;
import it.gruppobernardini.switchmail.model.MailAccount;
import it.gruppobernardini.switchmail.model.PostAction;
import it.gruppobernardini.switchmail.service.AccountService;
import it.gruppobernardini.switchmail.service.MailPollScheduler;
import it.gruppobernardini.switchmail.util.CredentialCipher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static it.gruppobernardini.switchmail.controller.Payloads.bool;
import static it.gruppobernardini.switchmail.controller.Payloads.intg;
import static it.gruppobernardini.switchmail.controller.Payloads.lng;
import static it.gruppobernardini.switchmail.controller.Payloads.require;
import static it.gruppobernardini.switchmail.controller.Payloads.str;

/** Pagina e API delle caselle. La password entra e non esce mai. */
@Controller
public class AccountController {

    private final AccountService accountService;
    private final MailPollScheduler scheduler;
    private final CredentialCipher cipher;

    public AccountController(AccountService accountService, MailPollScheduler scheduler, CredentialCipher cipher) {
        this.accountService = accountService;
        this.scheduler = scheduler;
        this.cipher = cipher;
    }

    @GetMapping("/accounts")
    public String page() {
        return "accounts";
    }

    @GetMapping("/accounts/api")
    @ResponseBody
    public List<Map<String, Object>> list() {
        return accountService.findAll().stream().map(this::view).toList();
    }

    @GetMapping("/accounts/api/{id}")
    @ResponseBody
    public Map<String, Object> get(@PathVariable long id) {
        return view(accountService.require(id));
    }

    @PostMapping("/accounts/api")
    @ResponseBody
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        long id = accountService.create(fromBody(null, body), str(body.get("password")));
        return view(accountService.require(id));
    }

    /** Non accetta la password: un round-trip della schermata non puo' svuotarla per distrazione. */
    @PutMapping("/accounts/api/{id}")
    @ResponseBody
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        accountService.update(fromBody(id, body));
        return view(accountService.require(id));
    }

    @PutMapping("/accounts/api/{id}/password")
    @ResponseBody
    public Map<String, Object> setPassword(@PathVariable long id, @RequestBody Map<String, Object> body) {
        accountService.setPassword(id, require(body.get("password"), "password"));
        return Map.of("passwordSet", true);
    }

    @DeleteMapping("/accounts/api/{id}")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable long id) {
        accountService.delete(id);
        return Map.of("deleted", id);
    }

    /** Test "prima di salvare", con credenziali ad hoc: non scrive niente. */
    @PostMapping("/accounts/api/test")
    @ResponseBody
    public TestConnectionResult test(@RequestBody Map<String, Object> body) {
        Long id = lng(body.get("id"));
        return accountService.test(fromBody(id, body), str(body.get("password")), intg(body.get("preview"), 5));
    }

    @PostMapping("/accounts/api/{id}/test")
    @ResponseBody
    public TestConnectionResult testStored(@PathVariable long id,
                                           @RequestParam(defaultValue = "5") int preview) {
        return accountService.test(id, preview);
    }

    @GetMapping("/accounts/api/{id}/preview")
    @ResponseBody
    public List<MailPreview> preview(@PathVariable long id, @RequestParam(defaultValue = "10") int n) {
        return accountService.preview(id, n);
    }

    /** Poll immediato: utile subito dopo aver creato una regola. */
    @PostMapping("/accounts/api/{id}/poll")
    @ResponseBody
    public Map<String, Object> poll(@PathVariable long id) {
        accountService.require(id);
        boolean started = scheduler.submit(id);
        return Map.of("started", started);
    }

    /** Alimenta il banner rosso: meglio dirlo subito che al primo salvataggio fallito. */
    @GetMapping("/accounts/api/crypto")
    @ResponseBody
    public Map<String, Object> crypto() {
        return Map.of("configured", cipher.isConfigured(), "hint", CredentialCipher.GENERATE_KEY_HINT);
    }

    private MailAccount fromBody(Long id, Map<String, Object> body) {
        AccessMode accessMode;
        PostAction postAction;
        try {
            accessMode = AccessMode.valueOf(str(body.getOrDefault("accessMode", "READ_ONLY")).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("modalita' di accesso non valida: " + body.get("accessMode"));
        }
        try {
            postAction = PostAction.valueOf(str(body.getOrDefault("postAction", "NONE")).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("azione post-elaborazione non valida: " + body.get("postAction"));
        }

        return new MailAccount(id,
                require(body.get("name"), "name"),
                require(body.get("host"), "host"),
                intg(body.get("port"), 993),
                bool(body.get("useSsl"), true),
                bool(body.get("startTls"), false),
                bool(body.get("trustAllCerts"), false),
                require(body.get("username"), "username"),
                null,
                str(body.getOrDefault("folder", "INBOX")),
                accessMode,
                postAction,
                str(body.get("postActionFolder")),
                str(body.getOrDefault("pollCron", "0 */2 * * * *")),
                intg(body.get("maxMessagesPerPoll"), 50),
                intg(body.get("initialLookbackDays"), 1),
                intg(body.get("connectTimeoutMs"), 10000),
                intg(body.get("readTimeoutMs"), 30000),
                bool(body.get("enabled"), true),
                null, null, null, null, 0, null, null);
    }

    private Map<String, Object> view(MailAccount a) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", a.id());
        view.put("name", a.name());
        view.put("host", a.host());
        view.put("port", a.port());
        view.put("useSsl", a.useSsl());
        view.put("startTls", a.startTls());
        view.put("trustAllCerts", a.trustAllCerts());
        view.put("username", a.username());
        // Mai il valore: solo se c'e' o no.
        view.put("passwordSet", a.passwordSet());
        view.put("folder", a.folder());
        view.put("accessMode", a.accessMode().name());
        view.put("postAction", a.postAction().name());
        view.put("postActionFolder", a.postActionFolder());
        view.put("pollCron", a.pollCron());
        view.put("maxMessagesPerPoll", a.maxMessagesPerPoll());
        view.put("initialLookbackDays", a.initialLookbackDays());
        view.put("connectTimeoutMs", a.connectTimeoutMs());
        view.put("readTimeoutMs", a.readTimeoutMs());
        view.put("enabled", a.enabled());
        view.put("lastPollAt", a.lastPollAt());
        view.put("lastPollStatus", a.lastPollStatus());
        view.put("lastPollError", a.lastPollError());
        view.put("lastPollFetched", a.lastPollFetched());
        view.put("consecutiveFailures", a.consecutiveFailures());
        view.put("ruleCount", a.id() == null ? 0 : accountService.countRules(a.id()));
        return view;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
