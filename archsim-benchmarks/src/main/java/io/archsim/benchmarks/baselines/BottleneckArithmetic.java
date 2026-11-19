package io.archsim.benchmarks.baselines;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * The knee by bottleneck arithmetic: each hard limit of a design's parts divided by what
 * one request takes of it, and the smallest of those loads. It reads the calibrated
 * inputs' means and, behind a cache, the share of lookups the top calibration pass
 * counted as misses: no queueing, no simulation, no measured load step.
 */
final class BottleneckArithmetic {

	/** How the knee is found, in the file's words. */
	static final String HOW = "Each hard limit of the design's parts (a service's or a cache's cores, a pool of "
			+ "workers that wait for their calls, a database's cores and its connections, the lock of a row every "
			+ "write updates, a queue's consumers) times 1,000 over the milliseconds of one unit a client request "
			+ "takes, from the calibrated inputs' means: a service's CPU per request at the top calibration rate, "
			+ "a query's time, and for a worker, a connection or a lock the time it is held. A cache sends its "
			+ "database the share of its lookups that missed in the top calibration pass. The knee is the "
			+ "smallest such load among the limits a request waits for; work behind a queue is listed but does "
			+ "not bound it. No queueing and no simulation: waiting, the times' spread and garbage collection "
			+ "pauses are left out, and no measured load step is used.";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private BottleneckArithmetic() {
	}

	/**
	 * The knee of a design by its smallest limit.
	 * @param design the design of the run's calibrated inputs
	 * @param cacheMissShare the share of a cache's lookups that miss; any share for a
	 * design without a cache
	 * @return how it was found, {@code kneeRps}, the part and limit it is
	 * {@code limitedBy} (None when no request waits for a limit) and every limit
	 */
	static ObjectNode of(JsonNode design, double cacheMissShare) {
		List<Limit> limits = PartLimits.of(design, cacheMissShare);
		Optional<Limit> smallest = limits.stream()
			.filter(Limit::onRequestPath)
			.min(Comparator.comparingDouble(Limit::capacityRps));
		ObjectNode arithmetic = NODES.objectNode().put("how", HOW);
		arithmetic.set(Baselines.KNEE, smallest.map((limit) -> PythonValues.number(Units.rate(limit.capacityRps())))
			.orElse(PythonValues.none()));
		arithmetic.set("limitedBy",
				smallest
					.<JsonNode>map((limit) -> NODES.objectNode().put("part", limit.part()).put("limit", limit.name()))
					.orElse(PythonValues.none()));
		ArrayNode list = arithmetic.putArray("limits");
		limits.forEach((limit) -> list.add(limit.json()));
		return arithmetic;
	}

}
