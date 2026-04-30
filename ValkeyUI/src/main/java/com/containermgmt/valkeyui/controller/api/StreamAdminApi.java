package com.containermgmt.valkeyui.controller.api;

import com.containermgmt.valkeyui.model.DeleteRangeRequest;
import com.containermgmt.valkeyui.model.DeleteTimeRangeRequest;
import com.containermgmt.valkeyui.model.TrimRequest;
import com.containermgmt.valkeyui.service.StreamAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/streams/{key}")
@RequiredArgsConstructor
public class StreamAdminApi {

    private final StreamAdminService adminService;

    @PostMapping("/trim")
    public Map<String, Object> trim(@PathVariable String key, @RequestBody TrimRequest request) {
        requireConfirm(key, request.getConfirm());
        return adminService.trim(key, request.getStrategy(), request.getValue(), request.isApproximate());
    }

    @PostMapping("/delete-range")
    public Map<String, Object> deleteRange(@PathVariable String key,
                                           @RequestBody DeleteRangeRequest request) {
        requireConfirm(key, request.getConfirm());
        return adminService.deleteRange(key, request.getFromId(), request.getToId());
    }

    @PostMapping("/delete-time-range")
    public Map<String, Object> deleteTimeRange(@PathVariable String key,
                                               @RequestBody DeleteTimeRangeRequest request) {
        requireConfirm(key, request.getConfirm());
        return adminService.deleteTimeRange(key, request.getFromTime(), request.getToTime());
    }

    @DeleteMapping
    public Map<String, Object> deleteAll(@PathVariable String key,
                                         @RequestParam("confirm") String confirm) {
        requireConfirm(key, confirm);
        return adminService.deleteAll(key);
    }

    private void requireConfirm(String key, String confirm) {
        if (confirm == null || !confirm.equals(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "confirm parameter must equal stream key");
        }
    }

}
