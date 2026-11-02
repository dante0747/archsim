package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/** What a publish waits for before it is acknowledged; auto follows the technology. */
public enum Acks implements JsonEnum {
    AUTO("auto"),
    LEADER("leader"),
    ALL("all");

    private final String json;

    Acks(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
