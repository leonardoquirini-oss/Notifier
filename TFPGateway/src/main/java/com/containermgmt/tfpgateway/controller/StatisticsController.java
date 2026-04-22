package com.containermgmt.tfpgateway.controller;

import com.containermgmt.tfpgateway.service.StatisticsService;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

@Controller
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping("/statistics")
    public String page() {
        return "statistics";
    }

    @GetMapping("/statistics/api/events-over-time")
    @ResponseBody
    public Map<String, Object> eventsOverTime(
            @RequestParam(defaultValue = "hour") String granularity,
            @RequestParam(defaultValue = "24") int lookbackHours) {
        return statisticsService.eventsOverTime(granularity, clampLookback(lookbackHours));
    }

    @GetMapping("/statistics/api/summary")
    @ResponseBody
    public Map<String, Object> summary() {
        return statisticsService.summary();
    }

    @GetMapping("/statistics/api/counts-by-type")
    @ResponseBody
    public List<Map<String, Object>> countsByType(
            @RequestParam(defaultValue = "24") int lookbackHours) {
        return statisticsService.countsByEventType(clampLookback(lookbackHours));
    }

    @GetMapping("/statistics/api/processing-lag")
    @ResponseBody
    public List<Map<String, Object>> processingLag(
            @RequestParam(defaultValue = "24") int lookbackHours) {
        return statisticsService.processingLag(clampLookback(lookbackHours));
    }

    @GetMapping("/statistics/api/arrival-gaps")
    @ResponseBody
    public Map<String, Object> arrivalGaps(
            @RequestParam(defaultValue = "24") int lookbackHours) {
        return statisticsService.arrivalGapDistribution(clampLookback(lookbackHours));
    }

    @GetMapping("/statistics/api/silence")
    @ResponseBody
    public List<Map<String, Object>> silence() {
        return statisticsService.silenceByEventType();
    }

    private int clampLookback(int hours) {
        if (hours < 1) return 1;
        if (hours > 24 * 90) return 24 * 90;
        return hours;
    }
}
