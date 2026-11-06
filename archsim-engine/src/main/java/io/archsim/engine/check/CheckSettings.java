package io.archsim.engine.check;

import java.util.List;

/**
 * How a check judges a design: its regression thresholds and what fails it.
 *
 * @param thresholds how much worse each figure may get than the baseline's
 * @param failOn what fails the check, in the order given
 */
public record CheckSettings(Thresholds thresholds, List<FailOn> failOn) {

	/**
	 * Copies what fails the check, so the record does not change.
	 */
	public CheckSettings {
		failOn = List.copyOf(failOn);
	}

}
