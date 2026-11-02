package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/** The cache product: Redis, Valkey, Memcached, a CDN, Varnish, or a generic cache. */
public enum CacheTechnology implements JsonEnum {

	/** Redis, which executes commands on one thread. */
	REDIS("redis"),
	/** Valkey, Redis's open-source fork, which also executes commands on one thread. */
	VALKEY("valkey"),
	/** Memcached, which executes commands on worker threads. */
	MEMCACHED("memcached"),
	/** A content delivery network, caching responses at the edge. */
	CDN("cdn"),
	/** Varnish, an HTTP cache in front of web servers. */
	VARNISH("varnish"),
	/** A cache of no particular product. */
	GENERIC("generic");

	private final String json;

	CacheTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
