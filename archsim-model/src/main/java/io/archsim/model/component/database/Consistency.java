package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** How many of a key's replicas an operation waits for: one, a quorum, or all. */
public enum Consistency implements JsonEnum {
    ONE("one"),
    QUORUM("quorum"),
    ALL("all");

    private final String json;

    Consistency(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
