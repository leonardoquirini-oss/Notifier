package com.containermgmt.valkeyui.controller;

import com.containermgmt.valkeyui.config.ValkeyUiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final ValkeyUiProperties properties;

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("readOnly", properties.isReadOnly());
        model.addAttribute("title", "Valkey UI - Dashboard");
        return "dashboard";
    }

    @GetMapping("/streams")
    public String streamsList(Model model) {
        model.addAttribute("readOnly", properties.isReadOnly());
        model.addAttribute("title", "Valkey UI - Streams");
        return "streams";
    }

    @GetMapping("/streams/{key}")
    public String streamDetail(@org.springframework.web.bind.annotation.PathVariable String key, Model model) {
        model.addAttribute("readOnly", properties.isReadOnly());
        model.addAttribute("streamKey", key);
        model.addAttribute("title", "Valkey UI - " + key);
        return "stream-detail";
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("readOnly", properties.isReadOnly());
        model.addAttribute("title", "Valkey UI - Audit");
        return "audit";
    }

}
