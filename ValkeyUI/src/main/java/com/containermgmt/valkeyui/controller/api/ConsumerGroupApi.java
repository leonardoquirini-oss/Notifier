package com.containermgmt.valkeyui.controller.api;

import com.containermgmt.valkeyui.model.AckRequest;
import com.containermgmt.valkeyui.model.CreateGroupRequest;
import com.containermgmt.valkeyui.model.SetIdRequest;
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
@RequestMapping("/api/streams/{key}/groups")
@RequiredArgsConstructor
public class ConsumerGroupApi {

    private final StreamAdminService adminService;

    @PostMapping
    public Map<String, Object> create(@PathVariable String key,
                                      @RequestBody CreateGroupRequest request) {
        return adminService.createGroup(key, request.getName(),
                request.getId() == null ? "$" : request.getId(),
                request.isMkstream());
    }

    @DeleteMapping("/{group}")
    public Map<String, Object> destroy(@PathVariable String key,
                                       @PathVariable String group,
                                       @RequestParam("confirm") String confirm) {
        if (!group.equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "confirm parameter must equal group name");
        }
        return adminService.destroyGroup(key, group);
    }

    @DeleteMapping("/{group}/consumers/{consumer}")
    public Map<String, Object> deleteConsumer(@PathVariable String key,
                                              @PathVariable String group,
                                              @PathVariable String consumer) {
        return adminService.deleteConsumer(key, group, consumer);
    }

    @PostMapping("/{group}/setid")
    public Map<String, Object> setId(@PathVariable String key,
                                     @PathVariable String group,
                                     @RequestBody SetIdRequest request) {
        return adminService.setGroupId(key, group, request.getId());
    }

    @PostMapping("/{group}/ack")
    public Map<String, Object> ack(@PathVariable String key,
                                   @PathVariable String group,
                                   @RequestBody AckRequest request) {
        return adminService.ack(key, group, request.getIds());
    }

}
