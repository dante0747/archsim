package io.archsim.model.pricing;

import io.archsim.model.codec.JsonEnum;

/**
 * The price lists of a snapshot: virtual machines, managed databases, caches and streams.
 */
public enum SkuCategory implements JsonEnum {

	/** Virtual machine types. */
	COMPUTE("compute"),
	/** Managed database types. */
	DATABASE("database"),
	/** Managed cache types. */
	CACHE("cache"),
	/** Managed stream types. */
	STREAM("stream");

	private final String json;

	SkuCategory(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
