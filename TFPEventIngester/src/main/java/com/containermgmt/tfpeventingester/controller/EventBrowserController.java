package com.containermgmt.tfpeventingester.controller;

import com.containermgmt.tfpeventingester.service.BerlinkAttachmentService;
import com.containermgmt.tfpeventingester.service.EventBrowserService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Controller
public class EventBrowserController {

    private static final String DT_PATTERN = "yyyy-MM-dd'T'HH:mm[:ss]";
    private static final DateTimeFormatter DT_INPUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private static String fmt(LocalDateTime dt) {
        return dt != null ? dt.format(DT_INPUT_FMT) : "";
    }

    private final EventBrowserService eventBrowserService;
    private final BerlinkAttachmentService berlinkAttachmentService;

    public EventBrowserController(EventBrowserService eventBrowserService,
                                  BerlinkAttachmentService berlinkAttachmentService) {
        this.eventBrowserService = eventBrowserService;
        this.berlinkAttachmentService = berlinkAttachmentService;
    }

    @GetMapping("/events")
    public String browseEvents(
            @RequestParam(defaultValue = "events") String tab,
            // Unit Events filters
            @RequestParam(required = false) String evtUnitNumber,
            @RequestParam(required = false) String evtUnitTypeCode,
            @RequestParam(required = false) String evtMessageId,
            @RequestParam(required = false) String evtTrailerPlate,
            @RequestParam(required = false) String evtContainerNumber,
            @RequestParam(required = false) String evtType,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime evtDateFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime evtDateTo,
            @RequestParam(defaultValue = "false") boolean evtUnlinkedOnly,
            @RequestParam(defaultValue = "0") int evtPage,
            // Unit Positions filters
            @RequestParam(required = false) String posUnitNumber,
            @RequestParam(required = false) String posUnitTypeCode,
            @RequestParam(required = false) String posVehiclePlate,
            @RequestParam(required = false) String posContainerNumber,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime posDateFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime posDateTo,
            @RequestParam(defaultValue = "false") boolean posUnlinkedOnly,
            @RequestParam(defaultValue = "0") int posPage,
            // Asset Damages filters
            @RequestParam(required = false) String dmgAssetIdentifier,
            @RequestParam(required = false) String dmgAssetType,
            @RequestParam(required = false) String dmgContainerNumber,
            @RequestParam(required = false) String dmgSeverity,
            @RequestParam(required = false) String dmgStatus,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime dmgDateFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime dmgDateTo,
            @RequestParam(defaultValue = "false") boolean dmgUnlinkedOnly,
            @RequestParam(defaultValue = "0") int dmgPage,
            // Ingestion Errors filters
            @RequestParam(required = false) String errMessageId,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime errDateFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = DT_PATTERN) LocalDateTime errDateTo,
            @RequestParam(defaultValue = "0") int errPage,
            Model model) {

        int pageSize = eventBrowserService.getPageSize();

        // --- Unit Events ---
        List<Map<String, Object>> unitEvents = eventBrowserService.searchUnitEvents(
                evtUnitNumber, evtUnitTypeCode, evtMessageId, evtTrailerPlate, evtContainerNumber,
                evtType, evtDateFrom, evtDateTo, evtUnlinkedOnly, evtPage);
        long evtTotalCount = eventBrowserService.countUnitEvents(
                evtUnitNumber, evtUnitTypeCode, evtMessageId, evtTrailerPlate, evtContainerNumber,
                evtType, evtDateFrom, evtDateTo, evtUnlinkedOnly);
        int evtTotalPages = (int) Math.ceil((double) evtTotalCount / pageSize);
        List<String> evtUnitTypeCodes = eventBrowserService.getDistinctValues("unit_type_code", "evt_unit_events");
        List<String> evtTypes = eventBrowserService.getDistinctValues("type", "evt_unit_events");

        model.addAttribute("unitEvents", unitEvents);
        model.addAttribute("evtTotalCount", evtTotalCount);
        model.addAttribute("evtTotalPages", evtTotalPages);
        model.addAttribute("evtCurrentPage", evtPage);
        model.addAttribute("evtUnitTypeCodes", evtUnitTypeCodes);
        model.addAttribute("evtTypes", evtTypes);

        // Repopulate event filters
        model.addAttribute("evtUnitNumber", evtUnitNumber);
        model.addAttribute("evtUnitTypeCode", evtUnitTypeCode);
        model.addAttribute("evtMessageId", evtMessageId);
        model.addAttribute("evtTrailerPlate", evtTrailerPlate);
        model.addAttribute("evtContainerNumber", evtContainerNumber);
        model.addAttribute("evtType", evtType);
        model.addAttribute("evtDateFromStr", fmt(evtDateFrom));
        model.addAttribute("evtDateToStr", fmt(evtDateTo));
        model.addAttribute("evtUnlinkedOnly", evtUnlinkedOnly);

        // --- Unit Positions ---
        List<Map<String, Object>> unitPositions = eventBrowserService.searchUnitPositions(
                posUnitNumber, posUnitTypeCode, posVehiclePlate, posContainerNumber,
                posDateFrom, posDateTo, posUnlinkedOnly, posPage);
        long posTotalCount = eventBrowserService.countUnitPositions(
                posUnitNumber, posUnitTypeCode, posVehiclePlate, posContainerNumber,
                posDateFrom, posDateTo, posUnlinkedOnly);
        int posTotalPages = (int) Math.ceil((double) posTotalCount / pageSize);
        List<String> posUnitTypeCodes = eventBrowserService.getDistinctValues("unit_type_code", "evt_unit_positions");

        model.addAttribute("unitPositions", unitPositions);
        model.addAttribute("posTotalCount", posTotalCount);
        model.addAttribute("posTotalPages", posTotalPages);
        model.addAttribute("posCurrentPage", posPage);
        model.addAttribute("posUnitTypeCodes", posUnitTypeCodes);

        // Repopulate position filters
        model.addAttribute("posUnitNumber", posUnitNumber);
        model.addAttribute("posUnitTypeCode", posUnitTypeCode);
        model.addAttribute("posVehiclePlate", posVehiclePlate);
        model.addAttribute("posContainerNumber", posContainerNumber);
        model.addAttribute("posDateFromStr", fmt(posDateFrom));
        model.addAttribute("posDateToStr", fmt(posDateTo));
        model.addAttribute("posUnlinkedOnly", posUnlinkedOnly);

        // --- Asset Damages ---
        List<Map<String, Object>> assetDamages = eventBrowserService.searchAssetDamages(
                dmgAssetIdentifier, dmgAssetType, dmgContainerNumber, dmgSeverity, dmgStatus,
                dmgDateFrom, dmgDateTo, dmgUnlinkedOnly, dmgPage);
        long dmgTotalCount = eventBrowserService.countAssetDamages(
                dmgAssetIdentifier, dmgAssetType, dmgContainerNumber, dmgSeverity, dmgStatus,
                dmgDateFrom, dmgDateTo, dmgUnlinkedOnly);
        int dmgTotalPages = (int) Math.ceil((double) dmgTotalCount / pageSize);
        List<String> dmgAssetTypes = eventBrowserService.getDistinctValues("asset_type", "evt_asset_damages");
        List<String> dmgSeverities = eventBrowserService.getDistinctValues("severity", "evt_asset_damages");
        List<String> dmgStatuses = eventBrowserService.getDistinctValues("status", "evt_asset_damages");

        model.addAttribute("assetDamages", assetDamages);
        model.addAttribute("dmgTotalCount", dmgTotalCount);
        model.addAttribute("dmgTotalPages", dmgTotalPages);
        model.addAttribute("dmgCurrentPage", dmgPage);
        model.addAttribute("dmgAssetTypes", dmgAssetTypes);
        model.addAttribute("dmgSeverities", dmgSeverities);
        model.addAttribute("dmgStatuses", dmgStatuses);

        // Repopulate damage filters
        model.addAttribute("dmgAssetIdentifier", dmgAssetIdentifier);
        model.addAttribute("dmgAssetType", dmgAssetType);
        model.addAttribute("dmgContainerNumber", dmgContainerNumber);
        model.addAttribute("dmgSeverity", dmgSeverity);
        model.addAttribute("dmgStatus", dmgStatus);
        model.addAttribute("dmgDateFromStr", fmt(dmgDateFrom));
        model.addAttribute("dmgDateToStr", fmt(dmgDateTo));
        model.addAttribute("dmgUnlinkedOnly", dmgUnlinkedOnly);

        // --- Ingestion Errors ---
        List<Map<String, Object>> ingestionErrors = eventBrowserService.searchErrors(
                errMessageId, errDateFrom, errDateTo, errPage);
        long errTotalCount = eventBrowserService.countErrors(errMessageId, errDateFrom, errDateTo);
        int errTotalPages = (int) Math.ceil((double) errTotalCount / pageSize);

        model.addAttribute("ingestionErrors", ingestionErrors);
        model.addAttribute("errTotalCount", errTotalCount);
        model.addAttribute("errTotalPages", errTotalPages);
        model.addAttribute("errCurrentPage", errPage);

        // Repopulate error filters
        model.addAttribute("errMessageId", errMessageId);
        model.addAttribute("errDateFromStr", fmt(errDateFrom));
        model.addAttribute("errDateToStr", fmt(errDateTo));

        // Active tab
        model.addAttribute("activeTab", tab);

        return "events";
    }

    @GetMapping("/events/error-payload")
    @ResponseBody
    public String getErrorPayload(@RequestParam String messageId) {
        String payload = eventBrowserService.getErrorPayload(messageId);
        return payload != null ? payload : "";
    }

    @GetMapping("/events/unit-event-detail")
    @ResponseBody
    public Map<String, Object> getUnitEventDetail(@RequestParam long id) {
        return eventBrowserService.getUnitEventDetail(id);
    }

    @GetMapping("/events/unit-position-detail")
    @ResponseBody
    public Map<String, Object> getUnitPositionDetail(@RequestParam long id) {
        return eventBrowserService.getUnitPositionDetail(id);
    }

    @GetMapping("/events/asset-damage-detail")
    @ResponseBody
    public Map<String, Object> getAssetDamageDetail(@RequestParam long id) {
        return eventBrowserService.getAssetDamageDetail(id);
    }

    @GetMapping("/events/attachments/{idDocument}/download")
    public ResponseEntity<byte[]> downloadAttachment(@PathVariable Long idDocument,
                                                      @RequestParam(required = false) String filename) {
        ResponseEntity<byte[]> upstream = berlinkAttachmentService.download(idDocument);
        if (upstream == null || !upstream.getStatusCode().is2xxSuccessful() || upstream.getBody() == null) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        MediaType contentType = upstream.getHeaders().getContentType();
        headers.setContentType(contentType != null ? contentType : MediaType.APPLICATION_OCTET_STREAM);
        String downloadName = (filename != null && !filename.isBlank())
                ? filename
                : "attachment-" + idDocument;
        String encoded = java.net.URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + downloadName.replace("\"", "") + "\"; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(upstream.getBody());
    }
}
