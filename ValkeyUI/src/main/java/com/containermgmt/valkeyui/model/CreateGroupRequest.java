package com.containermgmt.valkeyui.model;

import lombok.Data;

@Data
public class CreateGroupRequest {
    private String name;
    private String id = "$";
    private boolean mkstream = false;
}
