package com.containermgmt.valkeyui.controller.api;

import com.containermgmt.valkeyui.model.ServerInfo;
import com.containermgmt.valkeyui.service.ValkeyServerInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/server")
@RequiredArgsConstructor
public class ServerInfoApi {

    private final ValkeyServerInfoService serverInfoService;

    @GetMapping("/info")
    public ServerInfo info() {
        return serverInfoService.getServerInfo();
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Double latency = serverInfoService.ping();
        Map<String, Object> result = new HashMap<>();
        result.put("ok", latency != null);
        result.put("latencyMs", latency);
        return result;
    }

    @GetMapping("/clients")
    public List<String> clients() {
        return serverInfoService.clientList();
    }

}
