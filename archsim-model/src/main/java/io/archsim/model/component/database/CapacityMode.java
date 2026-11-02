package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** How capacity units are bought: per request, or provisioned. */
public enum CapacityMode implements JsonEnum {
    ON_DEMAND("on_demand"),
    PROVISIONED("provisioned");

    private final String json;

    CapacityMode(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
