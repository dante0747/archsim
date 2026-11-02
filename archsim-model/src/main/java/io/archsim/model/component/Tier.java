package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;

/** How well ArchSim knows a technology: core ones are validated first, the rest are experimental. */
public enum Tier implements JsonEnum {
    CORE("core"),
    EXPERIMENTAL("experimental");

    private final String json;

    Tier(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
