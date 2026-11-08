package io.archsim.engine.check;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A design's breaking point, read on a load grid it shares with the design it is compared
 * with.
 *
 * @param knee the highest load within the objectives, in requests per second;
 * {@code null} when the lightest load missed them
 * @param kneeVerdict the sweep's verdict
 * @param kneeRange the range of the first grid, which spans every design's suggested
 * range
 * @param kneeResolution the step of the grid the knee is read from
 */
public record KneeFinding(@Nullable Double knee, String kneeVerdict, List<Double> kneeRange, double kneeResolution) {

	/**
	 * Copies the range, so the record does not change.
	 */
	public KneeFinding {
		kneeRange = List.copyOf(kneeRange);
	}

}
