package com.containermgmt.valkeyui.model;

import lombok.Data;

import java.util.List;

@Data
public class AckRequest {
    private List<String> ids;
}
