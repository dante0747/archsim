package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** How requests arrive: at random (exponential gaps) or evenly spaced. */
public enum ArrivalProcess implements JsonEnum {
    POISSON("poisson"),
    DETERMINISTIC("deterministic");

    private final String json;

    ArrivalProcess(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
