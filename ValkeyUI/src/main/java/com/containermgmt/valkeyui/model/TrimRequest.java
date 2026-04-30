package com.containermgmt.valkeyui.model;

import lombok.Data;

@Data
public class TrimRequest {
    private String strategy;
    private String value;
    private boolean approximate = true;
    private String confirm;
}
