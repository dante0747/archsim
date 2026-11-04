package io.archsim.engine.sizing;

import io.archsim.model.codec.JsonEnum;

/** Where a spec a part is simulated with came from. */
public enum SpecSource implements JsonEnum {

	/** Set on the part as drawn. */
	SET("set"),

	/** Its auto size. */
	AUTO("auto"),

	/** A scenario's override. */
	SCENARIO("scenario");

	private final String json;

	SpecSource(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
