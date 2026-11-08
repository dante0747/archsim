package io.archsim.engine.check;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * What a check simulates and how it judges.
 *
 * @param scenarioId the scenario of the design and its baseline to simulate; {@code null}
 * for the designs as drawn
 * @param settings the regression thresholds and what fails the check
 * @param sweepsBreakingPoints whether the design's and the baseline's breaking points are
 * swept too, on one load grid
 * @param limits the limits each run is held to
 */
public record CheckRequest(@Nullable String scenarioId, CheckSettings settings, boolean sweepsBreakingPoints,
		SimulationLimits limits) {

}
