package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** Which instances serve reads; auto follows the technology. */
public enum ReadFrom implements JsonEnum {

	AUTO("auto"), ALL("all"), PRIMARY("primary"), REPLICAS("replicas");

	private final String json;

	ReadFrom(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
