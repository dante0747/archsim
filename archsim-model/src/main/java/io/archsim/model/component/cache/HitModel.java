package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/** How a cache decides hits: a fixed share of reads, or keys that expire after a TTL. */
public enum HitModel implements JsonEnum {

	FIXED_RATIO("fixed_ratio"), TTL_KEYSPACE("ttl_keyspace");

	private final String json;

	HitModel(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
