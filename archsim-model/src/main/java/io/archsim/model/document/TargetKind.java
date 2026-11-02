package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/**
 * What a scenario's override changes: a node's config, an edge's config or the workload.
 */
public enum TargetKind implements JsonEnum {

	/** A node's config, named by the target's id. */
	NODE("node"),
	/** An edge's config, named by the target's id. */
	EDGE("edge"),
	/** The workload, which needs no id. */
	WORKLOAD("workload");

	private final String json;

	TargetKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
