package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/**
 * What a full cache does: evict the least recently used keys, or refuse writes; auto
 * follows the technology.
 */
public enum EvictionPolicy implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** A full cache evicts the least recently used key. */
	ALLKEYS_LRU("allkeys_lru"),
	/** A full cache refuses new keys with an out-of-memory error. */
	NOEVICTION("noeviction");

	private final String json;

	EvictionPolicy(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
