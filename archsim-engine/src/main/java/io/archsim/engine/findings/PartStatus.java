package io.archsim.engine.findings;

import io.archsim.model.codec.JsonEnum;

/** How loaded a part was in a run, from the most to the least. */
public enum PartStatus implements JsonEnum {

	/**
	 * Utilization at least 95%, at least 1% of arrivals rejected, or a queue still
	 * growing.
	 */
	SATURATED("saturated", 3),

	/** Utilization at least 85%. */
	HOT("hot", 2),

	/** Utilization at least 70%. */
	WARM("warm", 1),

	/** Below that. */
	OK("ok", 0),

	/** No request arrived. */
	IDLE("idle", -1);

	private final String json;

	private final int rank;

	PartStatus(String json, int rank) {
		this.json = json;
		this.rank = rank;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * How loaded the status is, to order parts by it.
	 * @return higher for more loaded
	 */
	int rank() {
		return this.rank;
	}

	/**
	 * Whether a part of this status is a bottleneck candidate.
	 * @return whether it is saturated or hot
	 */
	boolean isLoaded() {
		return this == SATURATED || this == HOT;
	}

}
