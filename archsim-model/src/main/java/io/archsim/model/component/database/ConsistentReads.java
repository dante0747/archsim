package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** Whether reads of a metered table are eventually or strongly consistent. */
public enum ConsistentReads implements JsonEnum {
    EVENTUAL("eventual"),
    STRONG("strong");

    private final String json;

    ConsistentReads(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
