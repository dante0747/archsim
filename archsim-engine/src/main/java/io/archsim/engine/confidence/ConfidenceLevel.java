package io.archsim.engine.confidence;

import io.archsim.model.codec.JsonEnum;

/** How far a result can be trusted, from the least to the most. */
public enum ConfidenceLevel implements JsonEnum {

	/** The result may be far off. */
	LOW("low"),

	/** The result is roughly right. */
	MEDIUM("medium"),

	/** The result rests on measured or well-grounded inputs. */
	HIGH("high");

	private final String json;

	ConfidenceLevel(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * This level lowered to a cap when it is above it: a borderline verdict across seeds
	 * is trusted at most at {@link #MEDIUM}.
	 * @param cap the highest level allowed
	 * @return the lower of the two
	 */
	public ConfidenceLevel cappedAt(ConfidenceLevel cap) {
		return (compareTo(cap) <= 0) ? this : cap;
	}

}
