package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/** The cache product: Redis, Valkey, Memcached, a CDN, Varnish, or a generic cache. */
public enum CacheTechnology implements JsonEnum {

	REDIS("redis"), VALKEY("valkey"), MEMCACHED("memcached"), CDN("cdn"), VARNISH("varnish"), GENERIC("generic");

	private final String json;

	CacheTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
