package it.berlink.monitoring.controller;

import it.berlink.monitoring.model.MonitorOverview;
import it.berlink.monitoring.model.ProcessorStatus;
import it.berlink.monitoring.model.QueryMetric;
import it.berlink.monitoring.service.QueryLogFileProcessor;
import it.berlink.monitoring.service.QueryMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Controller for the performance dashboard web interface.
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final QueryMonitorService monitorService;
    private final QueryLogFileProcessor fileProcessor;

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        MonitorOverview overview = monitorService.getOverview();
        ProcessorStatus processorStatus = fileProcessor.getStatus();
        List<QueryMetric> slowestQueries = monitorService.getSlowestQueries(10);
        List<QueryMetric> mostFrequentQueries = monitorService.getMostFrequentQueries(10);

        model.addAttribute("overview", overview);
        model.addAttribute("processorStatus", processorStatus);
        model.addAttribute("slowestQueries", slowestQueries);
        model.addAttribute("mostFrequentQueries", mostFrequentQueries);

        return "dashboard";
    }
}
