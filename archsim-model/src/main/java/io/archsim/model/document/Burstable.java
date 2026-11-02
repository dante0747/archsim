package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** Whether auto-sizing may pick burstable instance types (AWS t families). */
public enum Burstable implements JsonEnum {
    AUTO("auto"),
    NEVER("never");

    private final String json;

    Burstable(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
