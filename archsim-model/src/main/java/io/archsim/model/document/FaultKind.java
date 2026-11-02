package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** What a fault does: refuse every call, slow a part down, or add latency to a link. */
public enum FaultKind implements JsonEnum {

	/** The target node refuses every request arriving while the fault is on. */
	OUTAGE("outage"),
	/** The target node's service times are multiplied by the fault's factor. */
	SLOWDOWN("slowdown"),
	/** Each traversal of the target edge takes the fault's extra latency longer. */
	LATENCY("latency");

	private final String json;

	FaultKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
