package io.archsim.benchmarks.baselines;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Estimates;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;

/**
 * The knee by the engine's own analytic estimate: the highest load at which its instant
 * estimate of the design, queueing formulas and no simulation, meets the objective. When
 * the estimate gives no p95 at any load, the knee is the load it says the design
 * saturates at, and the file says so; when its p95 is over the objective at every load
 * tried, down to 1 req/s, there is no knee to give, and the file says that rather than
 * the search's zero.
 */
final class AnalyticEstimate {

	/** How the knee is found, in the file's words. */
	static final String HOW = "The highest load at which the engine's instant estimate of the design (queueing "
			+ "formulas at one load, not a simulation) gives a p95 within the objective, searched as the simulated "
			+ "knee is: by doubling from 100 req/s, then bisection to 1%. The estimate fails no request, so a load "
			+ "meets the objective when the estimate has a p95 there and it is within the objective's; it has none "
			+ "once a part a request waits for is overloaded. No measured load step is used.";

	/** What the file adds when the estimate gave no p95. */
	static final String NO_P95 = " The estimate gave no p95 at any load tried, so the knee is the load it says the "
			+ "design saturates at, read at the first load tried.";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String P95 = "p95";

	private static final String LOAD = "offeredRps";

	private static final String BASIS = "basis";

	private static final String SATURATION = "saturationRps";

	private static final List<String> AT_KNEE = List.of(P95, "overloaded", SATURATION, "summary");

	private AnalyticEstimate() {
	}

	/**
	 * The knee of a design by its estimates.
	 * @param estimate the design's estimate at an offered load, as {@link Estimates}
	 * reads it
	 * @return how it was found, {@code kneeRps} (None with {@code why} when the estimate
	 * is over the objective at every load tried), its {@code basis} ({@code p95}, or
	 * {@code saturation} when the estimate gave no p95), what the estimate says
	 * {@code atKnee}, and each load {@code tried} with its p95
	 */
	static ObjectNode of(Function<JsonNode, ObjectNode> estimate) {
		List<ObjectNode> estimates = new ArrayList<>();
		ObjectNode searched = Estimates.knee((rps) -> {
			ObjectNode at = estimate.apply(rps);
			estimates.add(at);
			return at;
		});
		boolean hasP95 = estimates.stream().anyMatch((at) -> !PythonValues.isNone(at.get(P95)));
		ObjectNode knee = hasP95 ? byP95(searched.get(Baselines.KNEE), estimates) : bySaturation(estimates.getFirst());
		ArrayNode tried = knee.putArray("tried");
		estimates.forEach((at) -> tried.addObject().set(LOAD, at.get(LOAD)).set(P95, at.get(P95)));
		return knee;
	}

	// the search's knee is a load it tried, or zero when no load met the objective
	private static ObjectNode byP95(JsonNode kneeRps, List<ObjectNode> estimates) {
		Optional<ObjectNode> atKnee = estimates.stream()
			.filter((at) -> PythonValues.equal(at.get(LOAD), kneeRps))
			.findFirst();
		ObjectNode knee = NODES.objectNode().put("how", HOW);
		knee.set(Baselines.KNEE, atKnee.isPresent() ? kneeRps : PythonValues.none());
		knee.put(BASIS, P95);
		if (atKnee.isEmpty()) {
			double lowest = estimates.stream().mapToDouble((at) -> at.get(LOAD).doubleValue()).min().orElseThrow();
			return knee.put("why", "the estimate's p95 is over the objective at every load tried, down to "
					+ PythonFormat.format(lowest, "g") + " req/s: it gives no knee");
		}
		ObjectNode said = knee.putObject("atKnee");
		AT_KNEE.forEach((key) -> said.set(key, PythonValues.get(atKnee.get(), key)));
		return knee;
	}

	private static ObjectNode bySaturation(ObjectNode first) {
		ObjectNode knee = NODES.objectNode().put("how", HOW + NO_P95);
		knee.set(Baselines.KNEE, PythonValues.get(first, SATURATION));
		return knee.put(BASIS, "saturation");
	}

}
