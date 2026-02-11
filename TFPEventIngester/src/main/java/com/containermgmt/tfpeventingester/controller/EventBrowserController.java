package com.containermgmt.tfpeventingester.controller;

import com.containermgmt.tfpeventingester.service.EventBrowserService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
public class EventBrowserController {

    private final EventBrowserService eventBrowserService;

    public EventBrowserController(EventBrowserService eventBrowserService) {
        this.eventBrowserService = eventBrowserService;
    }

    @GetMapping("/events")
    public String browseEvents(
            @RequestParam(defaultValue = "events") String tab,
            // Unit Events filters
            @RequestParam(required = false) String evtUnitNumber,
            @RequestParam(required = false) String evtUnitTypeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate evtDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate evtDateTo,
            @RequestParam(defaultValue = "false") boolean evtUnlinkedOnly,
            @RequestParam(defaultValue = "0") int evtPage,
            // Unit Positions filters
            @RequestParam(required = false) String posUnitNumber,
            @RequestParam(required = false) String posUnitTypeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate posDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate posDateTo,
            @RequestParam(defaultValue = "false") boolean posUnlinkedOnly,
            @RequestParam(defaultValue = "0") int posPage,
            // Asset Damages filters
            @RequestParam(required = false) String dmgAssetIdentifier,
            @RequestParam(required = false) String dmgAssetType,
            @RequestParam(required = false) String dmgSeverity,
            @RequestParam(required = false) String dmgStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dmgDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dmgDateTo,
            @RequestParam(defaultValue = "false") boolean dmgUnlinkedOnly,
            @RequestParam(defaultValue = "0") int dmgPage,
            Model model) {

        int pageSize = eventBrowserService.getPageSize();

        // --- Unit Events ---
        List<Map<String, Object>> unitEvents = eventBrowserService.searchUnitEvents(
                evtUnitNumber, evtUnitTypeCode, evtDateFrom, evtDateTo, evtUnlinkedOnly, evtPage);
        long evtTotalCount = eventBrowserService.countUnitEvents(
                evtUnitNumber, evtUnitTypeCode, evtDateFrom, evtDateTo, evtUnlinkedOnly);
        int evtTotalPages = (int) Math.ceil((double) evtTotalCount / pageSize);
        List<String> evtUnitTypeCodes = eventBrowserService.getDistinctValues("unit_type_code", "evt_unit_events");

        model.addAttribute("unitEvents", unitEvents);
        model.addAttribute("evtTotalCount", evtTotalCount);
        model.addAttribute("evtTotalPages", evtTotalPages);
        model.addAttribute("evtCurrentPage", evtPage);
        model.addAttribute("evtUnitTypeCodes", evtUnitTypeCodes);

        // Repopulate event filters
        model.addAttribute("evtUnitNumber", evtUnitNumber);
        model.addAttribute("evtUnitTypeCode", evtUnitTypeCode);
        model.addAttribute("evtDateFrom", evtDateFrom);
        model.addAttribute("evtDateTo", evtDateTo);
        model.addAttribute("evtUnlinkedOnly", evtUnlinkedOnly);

        // --- Unit Positions ---
        List<Map<String, Object>> unitPositions = eventBrowserService.searchUnitPositions(
                posUnitNumber, posUnitTypeCode, posDateFrom, posDateTo, posUnlinkedOnly, posPage);
        long posTotalCount = eventBrowserService.countUnitPositions(
                posUnitNumber, posUnitTypeCode, posDateFrom, posDateTo, posUnlinkedOnly);
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
        model.addAttribute("posDateFrom", posDateFrom);
        model.addAttribute("posDateTo", posDateTo);
        model.addAttribute("posUnlinkedOnly", posUnlinkedOnly);

        // --- Asset Damages ---
        List<Map<String, Object>> assetDamages = eventBrowserService.searchAssetDamages(
                dmgAssetIdentifier, dmgAssetType, dmgSeverity, dmgStatus,
                dmgDateFrom, dmgDateTo, dmgUnlinkedOnly, dmgPage);
        long dmgTotalCount = eventBrowserService.countAssetDamages(
                dmgAssetIdentifier, dmgAssetType, dmgSeverity, dmgStatus,
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
        model.addAttribute("dmgSeverity", dmgSeverity);
        model.addAttribute("dmgStatus", dmgStatus);
        model.addAttribute("dmgDateFrom", dmgDateFrom);
        model.addAttribute("dmgDateTo", dmgDateTo);
        model.addAttribute("dmgUnlinkedOnly", dmgUnlinkedOnly);

        // Active tab
        model.addAttribute("activeTab", tab);

        return "events";
    }
}
