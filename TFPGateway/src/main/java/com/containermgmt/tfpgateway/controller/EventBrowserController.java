package com.containermgmt.tfpgateway.controller;

import com.containermgmt.tfpgateway.service.EventBrowserService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
public class EventBrowserController {

    private static final int PAGE_SIZE = 50;
    private static final int MAX_RESEND_LIST = 1000;

    private final EventBrowserService eventBrowserService;

    public EventBrowserController(EventBrowserService eventBrowserService) {
        this.eventBrowserService = eventBrowserService;
    }

    @GetMapping("/events")
    public String browseEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String messageId,
            @RequestParam(required = false) String unitNumber,
            @RequestParam(required = false) String plate,
            @RequestParam(required = false) String payloadType,
            @RequestParam(required = false) String additionalData,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        List<Map<String, Object>> events = eventBrowserService.searchEvents(eventType, dateFrom, dateTo, messageId, unitNumber, plate, payloadType, additionalData, page);
        long totalCount = eventBrowserService.countEvents(eventType, dateFrom, dateTo, messageId, unitNumber, plate, payloadType, additionalData);
        List<String> eventTypes = eventBrowserService.getDistinctEventTypes();

        int totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);

        model.addAttribute("events", events);
        model.addAttribute("eventTypes", eventTypes);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("currentPage", page);

        // Repopulate filter values
        model.addAttribute("selectedEventType", eventType);
        model.addAttribute("dateFrom", dateFrom);
        model.addAttribute("dateTo", dateTo);
        model.addAttribute("messageId", messageId);
        model.addAttribute("unitNumber", unitNumber);
        model.addAttribute("plate", plate);
        model.addAttribute("payloadType", payloadType);
        model.addAttribute("additionalData", additionalData);

        return "events";
    }

    @PostMapping("/events/resend")
    public String resendEvents(
            @RequestParam(required = false) List<Integer> selectedIds,
            @RequestParam(required = false, defaultValue = "false") boolean forceMessageId,
            RedirectAttributes redirectAttributes) {

        if (selectedIds == null || selectedIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No events selected.");
            return "redirect:/events";
        }

        int count = eventBrowserService.resendEvents(selectedIds, forceMessageId);
        redirectAttributes.addFlashAttribute("successMessage",
                count + " event(s) resent successfully to Valkey streams.");

        return "redirect:/events";
    }

    @PostMapping("/events/resend-list")
    public String resendByMessageIds(
            @RequestParam(required = false) String messageIds,
            @RequestParam(required = false, defaultValue = "input") String order,
            @RequestParam(required = false, defaultValue = "false") boolean forceMessageId,
            RedirectAttributes redirectAttributes) {

        List<String> ids = parseMessageIds(messageIds);

        if (ids.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No message id provided.");
            return "redirect:/events";
        }
        if (ids.size() > MAX_RESEND_LIST) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Too many message id: " + ids.size() + " (max " + MAX_RESEND_LIST + ").");
            return "redirect:/events";
        }

        boolean temporalOrder = "time".equalsIgnoreCase(order);
        int count = eventBrowserService.resendByMessageIds(ids, forceMessageId, temporalOrder);

        if (count == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No matching events found for the provided message id.");
        } else if (count < ids.size()) {
            redirectAttributes.addFlashAttribute("successMessage",
                    count + "/" + ids.size() + " event(s) resent (" + (ids.size() - count) + " message id not found).");
        } else {
            redirectAttributes.addFlashAttribute("successMessage",
                    count + " event(s) resent successfully to Valkey streams.");
        }

        return "redirect:/events";
    }

    private List<String> parseMessageIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        // Una riga = un message_id. Trim, scarta righe vuote, dedup preservando l'ordine di input.
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String mid = line.trim();
            if (!mid.isEmpty()) {
                unique.add(mid);
            }
        }
        return new java.util.ArrayList<>(unique);
    }

    /**
     * Crea un nuovo evento a partire da uno esistente (clone-from-existing dalla detail modal).
     * Chiamata AJAX dalla UI: ritorna JSON. SAVE -> send=false; SAVE & SEND -> send=true.
     */
    @PostMapping("/events/create")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createEvent(
            @RequestParam String messageId,
            @RequestParam String eventType,
            @RequestParam String eventTime,
            @RequestParam String payload,
            @RequestParam(required = false, defaultValue = "false") boolean send) {

        try {
            eventBrowserService.createEvent(messageId, eventType, eventTime, payload, send);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        String msg = send
                ? "Event saved and sent to Valkey streams."
                : "Event saved.";
        return ResponseEntity.ok(Map.of("message", msg));
    }

    @PostMapping("/events/resend-all")
    public String resendAllEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String messageId,
            @RequestParam(required = false) String unitNumber,
            @RequestParam(required = false) String plate,
            @RequestParam(required = false) String payloadType,
            @RequestParam(required = false) String additionalData,
            @RequestParam(required = false, defaultValue = "false") boolean forceMessageId,
            RedirectAttributes redirectAttributes) {

        int count = eventBrowserService.resendAllByFilter(eventType, dateFrom, dateTo, messageId, unitNumber, plate, payloadType, additionalData, forceMessageId);

        if (count == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No events matched the filter criteria.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage",
                    count + " event(s) resent successfully to Valkey streams.");
        }

        return "redirect:/events";
    }
}
