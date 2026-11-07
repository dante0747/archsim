package io.archsim.engine.calibration.mapping;

import io.archsim.model.codec.JsonEnum;

/**
 * The unit a latency histogram's bucket bounds are in.
 */
public enum LatencyUnit implements JsonEnum {

	/** Seconds, as Prometheus' conventions have them. */
	SECONDS("s"),

	/** Milliseconds. */
	MILLISECONDS("ms");

	private static final double MILLISECONDS_PER_SECOND = 1000.0;

	private final String json;

	LatencyUnit(String json) {
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
			case SECONDS -> MILLISECONDS_PER_SECOND;
			case MILLISECONDS -> 1.0;
		};
	}

}
