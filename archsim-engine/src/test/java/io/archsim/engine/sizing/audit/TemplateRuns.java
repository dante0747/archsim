package io.archsim.engine.sizing.audit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * A template's designs as the audit runs them: compressed in time or scaled in rate, then
 * simulated or analyzed with the standard components and the shipped prices.
 */
final class TemplateRuns {

	private static final ComponentRegistry REGISTRY = StandardComponents.registry();

	private static final PriceSnapshots PRICES = PriceSnapshots.shipped();

	private static final int DIGITS = 3;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private TemplateRuns() {
	}

	/**
	 * The design's whole timeline squeezed into {@code seconds} (a burst or fault at 40%
	 * of the run stays at 40%), times rounded to 3 decimals as Python rounds them, its
	 * scenarios dropped.
	 * @param design the design
	 * @param seconds the run's length
	 * @return a compressed copy; a copy unchanged but for its scenarios when the run is
	 * no longer
	 */
	static ObjectNode compressed(JsonNode design, double seconds) {
		ObjectNode copy = (ObjectNode) design.deepCopy();
		copy.putArray("scenarios");
		ObjectNode workload = (ObjectNode) copy.path("workload");
		double factor = seconds / workload.path("durationS").doubleValue();
		if (factor >= 1) {
			return copy;
		}
		workload.put("durationS", seconds);
		workload.put("warmupS",
				Math.max(1.0, PythonRound.round(workload.path("warmupS").doubleValue() * factor, DIGITS)));
		if (workload.path("pattern") instanceof ObjectNode pattern) {
			shrink(pattern, factor);
			retrace(pattern, (time) -> NODES.numberNode(PythonRound.round(time.doubleValue() * factor, DIGITS)),
					Function.identity());
		}
		workload.path("faults").forEach((fault) -> shrink((ObjectNode) fault, factor));
		return copy;
	}

	/**
	 * The design at a multiple of its request rate: its rate, its pattern's rates and its
	 * trace's.
	 * @param design the design
	 * @param factor the multiple
	 * @return a scaled copy
	 */
	static JsonNode scaled(JsonNode design, double factor) {
		ObjectNode copy = (ObjectNode) design.deepCopy();
		ObjectNode workload = (ObjectNode) copy.path("workload");
		workload.put("rps", workload.path("rps").doubleValue() * factor);
		if (workload.path("pattern") instanceof ObjectNode pattern) {
			for (String key : List.of("burstRps", "startRps", "endRps")) {
				if (pattern.has(key)) {
					pattern.put(key, pattern.path(key).doubleValue() * factor);
				}
			}
			retrace(pattern, Function.identity(), (rate) -> NODES.numberNode(rate.doubleValue() * factor));
		}
		return copy;
	}

	/**
	 * A run of the design as drawn.
	 * @param design the design
	 * @return the run
	 */
	static AuditedRun run(JsonNode design) {
		return new AuditedRun(new Simulator(REGISTRY, PRICES).run(design, RunRequest.of(null)));
	}

	/**
	 * Each part's auto-sizing, as an analysis of the design gives it without a run.
	 * @param design the design
	 * @return the sizing entries, by part
	 */
	static JsonNode sizing(JsonNode design) {
		return new DesignAnalyzer(REGISTRY, PRICES).analyze(design, null).toJson().path("sizing").path("nodes");
	}

	/**
	 * The per-unit prices a design's pricing picks.
	 * @param pricing the design's pricing; {@code null} for the default
	 * @return the snapshot's rates, by name
	 */
	static Map<String, @Nullable Double> rates(@Nullable JsonNode pricing) {
		return PRICES.forPricing(pricing).rates();
	}

	// a trace's points, each [time, rate] rewritten; another pattern is left as it is
	private static void retrace(ObjectNode pattern, Function<JsonNode, JsonNode> time,
			Function<JsonNode, JsonNode> rate) {
		if (!AuditJson.is(pattern.path("kind"), "trace")) {
			return;
		}
		ArrayNode points = NODES.arrayNode();
		pattern.path("points")
			.forEach((point) -> points.addArray().add(time.apply(point.path(0))).add(rate.apply(point.path(1))));
		pattern.set("points", points);
	}

	private static void shrink(ObjectNode node, double factor) {
		for (String key : List.of("startS", "durationS")) {
			if (node.has(key)) {
				node.put(key, PythonRound.round(node.path(key).doubleValue() * factor, DIGITS));
			}
		}
	}

}
