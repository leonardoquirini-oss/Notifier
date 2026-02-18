package com.containermgmt.tfpgateway.controller;

import com.containermgmt.tfpgateway.service.EventBrowserService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
public class EventBrowserController {

    private static final int PAGE_SIZE = 50;

    private final EventBrowserService eventBrowserService;

    public EventBrowserController(EventBrowserService eventBrowserService) {
        this.eventBrowserService = eventBrowserService;
    }

    @GetMapping("/events")
    public String browseEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        List<Map<String, Object>> events = eventBrowserService.searchEvents(eventType, dateFrom, dateTo, page);
        long totalCount = eventBrowserService.countEvents(eventType, dateFrom, dateTo);
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

    @PostMapping("/events/resend-all")
    public String resendAllEvents(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false, defaultValue = "false") boolean forceMessageId,
            RedirectAttributes redirectAttributes) {

        int count = eventBrowserService.resendAllByFilter(eventType, dateFrom, dateTo, forceMessageId);

        if (count == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No events matched the filter criteria.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage",
                    count + " event(s) resent successfully to Valkey streams.");
        }

        return "redirect:/events";
    }
}
