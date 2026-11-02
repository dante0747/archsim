package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/** Whether a failed message is delivered again; auto follows the technology. */
public enum Delivery implements JsonEnum {
    AUTO("auto"),
    AT_LEAST_ONCE("at_least_once"),
    AT_MOST_ONCE("at_most_once");

    private final String json;

    Delivery(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
