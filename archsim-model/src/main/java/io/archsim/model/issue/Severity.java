package io.archsim.model.issue;

import io.archsim.model.codec.JsonEnum;

/** How bad an issue is: an error stops a simulation, a warning does not. */
public enum Severity implements JsonEnum {
    ERROR("error"),
    WARNING("warning");

    private final String json;

    Severity(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
