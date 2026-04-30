package com.containermgmt.valkeyui.model;

import lombok.Data;

@Data
public class DeleteTimeRangeRequest {
    private String fromTime;
    private String toTime;
    private String confirm;
}
