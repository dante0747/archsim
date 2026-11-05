package io.archsim.engine.confidence;

import java.util.List;

/**
 * How far a result can be trusted, and why: how much of what decides it rests on numbers
 * someone checked. There is no numeric score.
 *
 * @param level high, medium or low
 * @param reason the reason that decided the level
 * @param reasons that reason first, then the other facts behind the level
 * @param drivers the inputs to replace first
 * @param weightedBy what each part's weight in the result is
 * @param howToRaise what would raise it
 */
public record Confidence(ConfidenceLevel level, String reason, List<String> reasons, List<Driver> drivers,
		WeightedBy weightedBy, String howToRaise) {

	/**
	 * Copies the lists, so the record does not change.
	 */
	public Confidence {
		reasons = List.copyOf(reasons);
		drivers = List.copyOf(drivers);
	}

}
