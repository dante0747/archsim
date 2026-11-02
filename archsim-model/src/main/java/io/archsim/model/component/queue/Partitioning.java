package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/** How messages are spread over partitions; auto follows the technology. */
public enum Partitioning implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** Messages with the same key go to the same partition, consumed in order. */
	KEY("key"),
	/** Messages are spread evenly over the partitions. */
	ROUND_ROBIN("round_robin"),
	/** One shared queue with competing consumers. */
	NONE("none");

	private final String json;

	Partitioning(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
