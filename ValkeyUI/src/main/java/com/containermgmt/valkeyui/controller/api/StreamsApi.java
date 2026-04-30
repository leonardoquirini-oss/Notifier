package com.containermgmt.valkeyui.controller.api;

import com.containermgmt.valkeyui.model.ConsumerGroupInfo;
import com.containermgmt.valkeyui.model.StreamMessage;
import com.containermgmt.valkeyui.model.StreamSummary;
import com.containermgmt.valkeyui.service.StreamDiscoveryService;
import com.containermgmt.valkeyui.service.StreamInspectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/streams")
@RequiredArgsConstructor
public class StreamsApi {

    private final StreamDiscoveryService discoveryService;
    private final StreamInspectionService inspectionService;

    @GetMapping
    public List<StreamSummary> list(@RequestParam(value = "pattern", required = false) String pattern) {
        List<String> keys = discoveryService.listStreams(pattern);
        List<StreamSummary> result = new ArrayList<>(keys.size());
        for (String key : keys) {
            try {
                result.add(inspectionService.summary(key));
            } catch (Exception ignored) {
                // skip stream that disappeared between discovery and inspection
            }
        }
        return result;
    }

    @GetMapping("/{key}")
    public Map<String, Object> detail(@PathVariable String key) {
        Map<String, Object> info = inspectionService.xinfoStream(key);
        StreamSummary summary = inspectionService.summary(key);
        info.put("summary", summary);
        return info;
    }

    @GetMapping("/{key}/messages")
    public List<StreamMessage> messages(
            @PathVariable String key,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", defaultValue = "100") long limit,
            @RequestParam(value = "direction", defaultValue = "desc") String direction) {
        boolean reverse = !"asc".equalsIgnoreCase(direction);
        return inspectionService.readRange(key, from, to, limit, reverse);
    }

    @GetMapping("/{key}/groups")
    public List<ConsumerGroupInfo> groups(@PathVariable String key) {
        return inspectionService.consumerGroups(key);
    }

}
