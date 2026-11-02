package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/** How messages are spread over partitions; auto follows the technology. */
public enum Partitioning implements JsonEnum {
    AUTO("auto"),
    KEY("key"),
    ROUND_ROBIN("round_robin"),
    NONE("none");

    private final String json;

    Partitioning(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
