package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** What a fault does: refuse every call, slow a part down, or add latency to a link. */
public enum FaultKind implements JsonEnum {
    OUTAGE("outage"),
    SLOWDOWN("slowdown"),
    LATENCY("latency");

    private final String json;

    FaultKind(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
