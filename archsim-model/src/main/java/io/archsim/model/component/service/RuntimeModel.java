package io.archsim.model.component.service;

import io.archsim.model.codec.JsonEnum;

/** How a service's handlers share work: a thread per request, or event loops; auto follows the technology. */
public enum RuntimeModel implements JsonEnum {
    AUTO("auto"),
    THREAD_POOL("thread_pool"),
    EVENT_LOOP("event_loop");

    private final String json;

    RuntimeModel(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
