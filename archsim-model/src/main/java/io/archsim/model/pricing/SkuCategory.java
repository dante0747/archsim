package io.archsim.model.pricing;

import io.archsim.model.codec.JsonEnum;

/** The price lists of a snapshot: virtual machines, managed databases, caches and streams. */
public enum SkuCategory implements JsonEnum {
    COMPUTE("compute"),
    DATABASE("database"),
    CACHE("cache"),
    STREAM("stream");

    private final String json;

    SkuCategory(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
