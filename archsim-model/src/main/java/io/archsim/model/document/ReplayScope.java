package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** What a calibration replay check compares: the whole request path, or one component. */
public enum ReplayScope implements JsonEnum {
    END_TO_END("end_to_end"),
    COMPONENT("component");

    private final String json;

    ReplayScope(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
