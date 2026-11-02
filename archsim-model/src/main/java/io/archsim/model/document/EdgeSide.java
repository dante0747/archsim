package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** The side of a component a link attaches to on the canvas. */
public enum EdgeSide implements JsonEnum {
    TOP("top"),
    RIGHT("right"),
    BOTTOM("bottom"),
    LEFT("left");

    private final String json;

    EdgeSide(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
