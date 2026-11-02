package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;

/** A behaviour setting that is on, off, or what the technology does by default. */
public enum Toggle implements JsonEnum {

	AUTO("auto"), ON("on"), OFF("off");

	private final String json;

	Toggle(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
