package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;

/** A behaviour setting that is on, off, or what the technology does by default. */
public enum Toggle implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** On, whatever the technology does by default. */
	ON("on"),
	/** Off, whatever the technology does by default. */
	OFF("off");

	private final String json;

	Toggle(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
