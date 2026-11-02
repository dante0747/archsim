package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/** Whether a cache snapshots its data to disk; auto follows the technology. */
public enum Persistence implements JsonEnum {
    AUTO("auto"),
    NONE("none"),
    RDB("rdb");

    private final String json;

    Persistence(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
