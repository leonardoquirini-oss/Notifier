package com.containermgmt.tfpeventingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "damage-labels")
public class DamageLabelsProperties {

    private Map<String, String> vehicle = new LinkedHashMap<>();
    private Map<String, String> unit = new LinkedHashMap<>();

    public Map<String, String> getVehicle() {
        return vehicle;
    }

    public void setVehicle(Map<String, String> vehicle) {
        this.vehicle = vehicle;
    }

    public Map<String, String> getUnit() {
        return unit;
    }

    public void setUnit(Map<String, String> unit) {
        this.unit = unit;
    }

    /** Builds the unified alias→displayName map used by EventBrowserService */
    public Map<String, String> buildDisplayNames() {
        Map<String, String> map = new LinkedHashMap<>();
        vehicle.forEach((col, name) -> map.put("vl_" + col.replace("dmg_", ""), name));
        unit.forEach((col, name) -> map.put("ul_" + col.replace("dmg_", ""), name));
        return map;
    }
}
