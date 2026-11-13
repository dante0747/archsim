package io.archsim.benchmarks.designs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Objective;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The highest load whose simulation meets the objective, by doubling then bisection to
 * 1%. With a knee already found for nearly the same inputs the bracket is found by 25%
 * steps from it instead of doubling from 100 req/s: fewer simulations, the same answer to
 * within 1%. Loads keep Python's types, an integer where {@code round()} made one, so the
 * tried loads are written as the Python harness wrote them.
 */
final class KneeSearch {

	private static final double FIRST_RPS = 100.0;

	private static final double MOST_RPS = 200_000;

	private static final double LEAST_RPS = 10;

	private static final double STEP_UP = 1.25;

	private static final double STEP_DOWN = 0.8;

	private static final double RESOLUTION = 0.01;

	private static final List<String> TRIED_KEYS = List.of("offeredRps", "p95", "successRate");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Function<JsonNode, ObjectNode> simulation;

	/** The loads this search tried, in order: an instance runs one search. */
	private final List<ObjectNode> tried = new ArrayList<>();

	private KneeSearch(Function<JsonNode, ObjectNode> simulation) {
		this.simulation = simulation;
	}

	/**
	 * The knee of a design and the loads tried on the way.
	 * @param simulation the design's simulation at an offered load: its summary
	 * @param near a knee already found for nearly the same inputs, or None (or 0) to
	 * double from 100 req/s
	 * @return {@code kneeRps} and {@code tried}
	 */
	static ObjectNode knee(Function<JsonNode, ObjectNode> simulation, JsonNode near) {
		return new KneeSearch(simulation).search(near);
	}

	private ObjectNode search(JsonNode near) {
		Bracket bracket = (PythonValues.isNone(near) || near.doubleValue() == 0) ? doubling() : around(near);
		JsonNode low = bracket.low();
		JsonNode high = bracket.high();
		while (high.doubleValue() - low.doubleValue() > Math.max(1.0, RESOLUTION * high.doubleValue())) {
			JsonNode middle = roundToInteger((low.doubleValue() + high.doubleValue()) / 2);
			if (Objective.isMetBy(simulate(middle))) {
				low = middle;
			}
			else {
				high = middle;
			}
		}
		ObjectNode knee = NODES.objectNode().set("kneeRps", low);
		ArrayNode loads = knee.putArray("tried");
		for (ObjectNode summary : this.tried) {
			ObjectNode load = loads.addObject();
			TRIED_KEYS.forEach((key) -> load.set(key, PythonValues.get(summary, key)));
		}
		return knee;
	}

	// from 100 req/s, doubling until a load misses (or 200,000 req/s)
	private Bracket doubling() {
		JsonNode low = PythonValues.number(0.0);
		JsonNode high = PythonValues.number(FIRST_RPS);
		while (Objective.isMetBy(simulate(high))) {
			low = high;
			high = PythonValues.multiply(high, PythonValues.integer(2));
			if (high.doubleValue() > MOST_RPS) {
				break;
			}
		}
		return new Bracket(low, high);
	}

	// by 25% steps from a knee found before, until a load's verdict changes
	private Bracket around(JsonNode near) {
		boolean up = Objective.isMetBy(simulate(near));
		JsonNode low = up ? near : PythonValues.multiply(near, PythonValues.number(STEP_DOWN));
		JsonNode high = up ? PythonValues.multiply(near, PythonValues.number(STEP_UP)) : near;
		while (low.doubleValue() >= LEAST_RPS && high.doubleValue() <= MOST_RPS) {
			if (Objective.isMetBy(simulate(roundToInteger((up ? high : low).doubleValue()))) != up) {
				break;
			}
			JsonNode nextLow = up ? high : PythonValues.multiply(low, PythonValues.number(STEP_DOWN));
			JsonNode nextHigh = up ? PythonValues.multiply(high, PythonValues.number(STEP_UP)) : low;
			low = nextLow;
			high = nextHigh;
		}
		return new Bracket(
				(low.doubleValue() < LEAST_RPS) ? PythonValues.number(0.0) : roundToInteger(low.doubleValue()),
				roundToInteger(high.doubleValue()));
	}

	private ObjectNode simulate(JsonNode rps) {
		ObjectNode summary = this.simulation.apply(rps);
		this.tried.add(summary);
		return summary;
	}

	// Python's round(x): half to even, an int
	private static JsonNode roundToInteger(double value) {
		return PythonValues.integer((long) Math.rint(value));
	}

	/**
	 * The loads between which the knee lies.
	 *
	 * @param low a load that met the objective, or 0
	 * @param high a load that missed it
	 */
	private record Bracket(JsonNode low, JsonNode high) {

	}

}
