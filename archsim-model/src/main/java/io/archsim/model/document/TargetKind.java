package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** What a scenario's override changes: a node's config, an edge's config or the workload. */
public enum TargetKind implements JsonEnum {
    NODE("node"),
    EDGE("edge"),
    WORKLOAD("workload");

    private final String json;

    TargetKind(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
