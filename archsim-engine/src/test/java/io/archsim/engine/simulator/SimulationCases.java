package io.archsim.engine.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The designs the frozen engine simulated, each with the scenario, seed and mode it
 * simulated and its report: {@code simulator/cases.json.gz}, from
 * {@code scripts/export_simulation_cases.py}, since deleted; and the Java simulator that
 * replays them and the golden files' runs.
 */
public final class SimulationCases {

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private SimulationCases() {
	}

	/**
	 * Each recorded case.
	 * @return each case's {@code name}, {@code design}, {@code scenarioId}, {@code seed}
	 * ({@code null} for the workload's own), {@code mode} ({@code full} or
	 * {@code sampled}) and {@code report}, in the file's order
	 */
	public static List<JsonNode> recorded() {
		try (InputStream compressed = Objects.requireNonNull(SimulationCases.class.getResourceAsStream("cases.json.gz"),
				"simulator/cases.json.gz"); InputStream json = new GZIPInputStream(compressed)) {
			List<JsonNode> cases = new ArrayList<>();
			JsonMapper.builder().build().readTree(json).get("cases").forEach(cases::add);
			return cases;
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read simulator/cases.json.gz", ex);
		}
	}

	/**
	 * A recorded case simulated in Java.
	 * @param recorded the case
	 * @return the Java simulator's report of its design, with the scenario, the seed and
	 * the mode Python simulated
	 */
	public static ObjectNode simulate(JsonNode recorded) {
		return SIMULATOR.run(recorded.get("design"), request(recorded));
	}

	/**
	 * A design the frozen engine simulated, simulated in Java.
	 * @param design the design
	 * @param scenarioId the scenario Python simulated; JSON {@code null} for the design
	 * as drawn
	 * @return the Java simulator's report
	 */
	static ObjectNode simulate(JsonNode design, JsonNode scenarioId) {
		return SIMULATOR.run(design, RunRequest.of(scenarioId.isNull() ? null : scenarioId.stringValue()));
	}

	/**
	 * What a recorded case asks a run for.
	 * @param recorded the case
	 * @return the run's request: the scenario, the seed and the mode Python simulated
	 */
	static RunRequest request(JsonNode recorded) {
		JsonNode scenario = recorded.get("scenarioId");
		RunRequest request = RunRequest.of(scenario.isNull() ? null : scenario.stringValue());
		JsonNode seed = recorded.path("seed");
		if (seed.isIntegralNumber()) {
			request = request.withSeed(seed.longValue());
		}
		if ("sampled".equals(recorded.path("mode").asString())) {
			request = request.withMode(SimulationMode.SAMPLED);
		}
		return request;
	}

}
