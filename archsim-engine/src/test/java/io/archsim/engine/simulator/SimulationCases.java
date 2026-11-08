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
 * The designs the frozen engine simulated, each with the scenario it simulated and its
 * report: {@code simulator/cases.json.gz}, from
 * {@code scripts/export_simulation_cases.py}; and the Java simulator that replays them
 * and the golden files' runs.
 */
public final class SimulationCases {

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private SimulationCases() {
	}

	/**
	 * Each recorded case.
	 * @return each case's {@code name}, {@code design}, {@code scenarioId} and
	 * {@code report}, in the file's order
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
	 * @return the Java simulator's report of its design, with the scenario Python
	 * simulated
	 */
	public static ObjectNode simulate(JsonNode recorded) {
		return simulate(recorded.get("design"), recorded.get("scenarioId"));
	}

	/**
	 * A design the frozen engine simulated, simulated in Java.
	 * @param design the design
	 * @param scenarioId the scenario Python simulated; JSON {@code null} for the design
	 * as drawn
	 * @return the Java simulator's report
	 */
	static ObjectNode simulate(JsonNode design, JsonNode scenarioId) {
		return SIMULATOR.run(design, scenarioId.isNull() ? null : scenarioId.stringValue());
	}

}
