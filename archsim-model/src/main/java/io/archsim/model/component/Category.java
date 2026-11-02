package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;

/**
 * What kind of part a component type is; load balancers and queues send each call to one
 * target.
 */
public enum Category implements JsonEnum {

	TRAFFIC("traffic"), NETWORK("network"), COMPUTE("compute"), STORAGE("storage"), MESSAGING("messaging"),
	EXTERNAL("external");

	private final String json;

	Category(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
