package com.containermgmt.valkeyui.model;

import lombok.Data;

@Data
public class DeleteRangeRequest {
    private String fromId;
    private String toId;
    private String confirm;
}
