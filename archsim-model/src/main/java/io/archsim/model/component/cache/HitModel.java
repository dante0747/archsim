package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/** How a cache decides hits: a fixed share of reads, or keys that expire after a TTL. */
public enum HitModel implements JsonEnum {

	/** Each read hits with the configured probability. */
	FIXED_RATIO("fixed_ratio"),
	/** A read hits when its key was filled less than a TTL ago. */
	TTL_KEYSPACE("ttl_keyspace");

	private final String json;

	HitModel(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
