package io.archsim.model.component.client;

import io.archsim.model.codec.JsonEnum;

/** What a web client is built with: a label and logo, behaviour comes from the settings. */
public enum WebTechnology implements JsonEnum {
    BROWSER("browser"),
    REACT("react"),
    NEXTJS("nextjs"),
    VUE("vue"),
    ANGULAR("angular"),
    SVELTE("svelte");

    private final String json;

    WebTechnology(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
