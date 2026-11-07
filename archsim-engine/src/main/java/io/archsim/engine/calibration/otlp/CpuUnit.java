package io.archsim.engine.calibration.otlp;

import java.util.Arrays;
import java.util.Optional;

import io.archsim.model.codec.JsonEnum;

/**
 * The unit a span attribute writes CPU time in.
 */
public enum CpuUnit implements JsonEnum {

	/** Seconds. */
	SECONDS("s"),

	/** Milliseconds. */
	MILLISECONDS("ms"),

	/** Microseconds. */
	MICROSECONDS("us"),

	/** Nanoseconds. */
	NANOSECONDS("ns");

	private static final double MS_PER_S = 1000.0;

	private static final double MS_PER_US = 1e-3;

	private static final double MS_PER_NS = 1e-6;

	private final String json;

	CpuUnit(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * Milliseconds per unit.
	 * @return the milliseconds in one unit
	 */
	public double milliseconds() {
		return switch (this) {
			case SECONDS -> MS_PER_S;
			case MILLISECONDS -> 1.0;
			case MICROSECONDS -> MS_PER_US;
			case NANOSECONDS -> MS_PER_NS;
		};
	}

	/**
	 * The unit a name stands for.
	 * @param name {@code s}, {@code ms}, {@code us} or {@code ns}
	 * @return the unit; empty for any other name
	 */
	public static Optional<CpuUnit> named(String name) {
		return Arrays.stream(values()).filter((unit) -> unit.json.equals(name)).findFirst();
	}

}
