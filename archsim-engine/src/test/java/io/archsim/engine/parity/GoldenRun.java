package io.archsim.engine.parity;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.GoldenFile;

/**
 * A template's run the golden file keeps the frozen engine's whole report of (a shorter
 * run than the baselines'), simulated in Java: for the tests of how a run is read as the
 * frozen engine's from what the baselines keep of it.
 *
 * @param java the Java simulator's report of the run
 * @param python what the baselines would have kept of the frozen engine's report of it
 * @param frozen the frozen engine's report of the template's run as the parity run reads
 * it ({@link Baselines#frozenReport})
 */
record GoldenRun(JsonNode java, JsonNode python, JsonNode frozen) {

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	/**
	 * A golden run simulated in Java.
	 * @param template the template's id
	 * @param run the run's name in its golden file: {@code base}, or a scenario's id
	 * @return the run
	 */
	static GoldenRun of(String template, String run) {
		JsonNode recorded = GoldenFile.read("templates/" + template).at("/runs/" + run);
		JsonNode scenario = recorded.path("scenarioId");
		RunRequest request = RunRequest.of(scenario.isString() ? scenario.stringValue() : null);
		JsonNode java = SIMULATOR.run(recorded.get("document").deepCopy(), request);
		return new GoldenRun(java, RunFigures.kept(recorded.get("report")), Baselines.frozenReport(template));
	}

}
