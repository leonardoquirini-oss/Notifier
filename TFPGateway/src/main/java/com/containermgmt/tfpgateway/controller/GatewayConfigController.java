package com.containermgmt.tfpgateway.controller;

import com.containermgmt.tfpgateway.config.GatewayProperties;
import com.containermgmt.tfpgateway.dto.GatewayRuntimeConfig;
import com.containermgmt.tfpgateway.dto.GatewayStatusInfo;
import com.containermgmt.tfpgateway.service.GatewayLifecycleManager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@Slf4j
public class GatewayConfigController {

    private final GatewayLifecycleManager lifecycleManager;
    private final GatewayProperties gatewayProperties;

    public GatewayConfigController(GatewayLifecycleManager lifecycleManager,
                                   GatewayProperties gatewayProperties) {
        this.lifecycleManager = lifecycleManager;
        this.gatewayProperties = gatewayProperties;
    }

    /** Configuration page. */
    @GetMapping("/gateway")
    public String gatewayPage(Model model) {
        model.addAttribute("config", GatewayRuntimeConfig.fromProperties(gatewayProperties));
        model.addAttribute("status", lifecycleManager.getStatus());
        return "gateway";
    }

    /** Stop all listeners (AJAX). */
    @PostMapping("/gateway/stop")
    @ResponseBody
    public ResponseEntity<GatewayStatusInfo> stop() {
        log.info("Stop requested via UI");
        lifecycleManager.stopAll();
        return ResponseEntity.ok(lifecycleManager.getStatus());
    }

    /** Start all listeners (AJAX). */
    @PostMapping("/gateway/start")
    @ResponseBody
    public ResponseEntity<GatewayStatusInfo> start() {
        log.info("Start requested via UI");
        lifecycleManager.startAll();
        return ResponseEntity.ok(lifecycleManager.getStatus());
    }

    /** Apply new configuration and restart (form POST). */
    @PostMapping("/gateway/apply")
    public String apply(@ModelAttribute GatewayRuntimeConfig config,
                        RedirectAttributes redirectAttributes) {
        try {
            log.info("Apply configuration requested via UI");
            lifecycleManager.reconfigure(config);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Configuration applied. Gateway restarted successfully.");
        } catch (Exception e) {
            log.error("Failed to apply configuration: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to apply configuration: " + e.getMessage());
        }
        return "redirect:/gateway";
    }

    /** Status polling (AJAX). */
    @GetMapping("/gateway/status")
    @ResponseBody
    public ResponseEntity<GatewayStatusInfo> status() {
        return ResponseEntity.ok(lifecycleManager.getStatus());
    }
}
