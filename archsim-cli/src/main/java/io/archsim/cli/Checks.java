package io.archsim.cli;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.check.CheckRequest;
import io.archsim.engine.check.CheckResult;
import io.archsim.engine.check.CheckRunner;
import io.archsim.engine.check.KneeSweeps;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulator.Simulator;
import io.archsim.engine.sweep.BreakingPoint;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * What runs {@code archsim check}'s check: the engine's {@link CheckRunner}, or a
 * stand-in where a test needs one.
 */
@FunctionalInterface
interface Checks {

	/**
	 * Checks a design against its objectives and, with one, its baseline.
	 * @param document the design
	 * @param baseline the baseline; {@code null} for none
	 * @param request what to check and how
	 * @return the check
	 */
	CheckResult check(JsonNode document, @Nullable JsonNode baseline, CheckRequest request);

	/**
	 * The engine's checks, on the standard components and the shipped prices, built when
	 * a check is run.
	 * @return the checks
	 */
	static Checks standard() {
		return (document, baseline, request) -> runner().check(document, baseline, request);
	}

	private static CheckRunner runner() {
		Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());
		ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(), PriceSnapshots.shipped());
		// as the Python engine's workers: one run per step of a sweep, one per processor
		int parallelism = Math.min(KneeSweeps.STEPS, Runtime.getRuntime().availableProcessors());
		return new CheckRunner(simulator,
				new KneeSweeps(new BreakingPoint(simulator, resolver, parallelism), resolver));
	}

}
