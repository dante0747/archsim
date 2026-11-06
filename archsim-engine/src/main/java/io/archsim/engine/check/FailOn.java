package io.archsim.engine.check;

import java.util.Arrays;

import io.archsim.model.codec.JsonEnum;

/** What fails a check. */
public enum FailOn implements JsonEnum {

	/** An objective the design sets is missed. */
	OBJECTIVES("objectives"),

	/** A figure got worse than the baseline's by more than its threshold. */
	REGRESSION("regression");

	private final String json;

	FailOn(String json) {
		this.json = json;
	}

	/**
	 * What fails a check, by the name {@code --fail-on} gives it.
	 * @param json {@code objectives} or {@code regression}
	 * @return the choice
	 * @throws IllegalArgumentException for any other name
	 */
	public static FailOn of(String json) {
		return Arrays.stream(values())
			.filter((choice) -> choice.json.equals(json))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("A check fails on objectives or regression, not " + json));
	}

	@Override
	public String json() {
		return this.json;
	}

}
