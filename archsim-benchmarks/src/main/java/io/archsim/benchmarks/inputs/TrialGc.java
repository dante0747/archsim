package io.archsim.benchmarks.inputs;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The garbage collection a calibration-only trial measured, for kept runs whose Node.js
 * service did not report its own: each kind's collections and pause per request over the
 * trial's top passes, given to a run's top passes scaled to their own requests.
 */
final class TrialGc {

	/**
	 * The trials (in {@code results/trials/}) whose service reported its garbage
	 * collection (W21).
	 */
	private static final Map<String, String> TRIALS = Map.of("pg-mixed", "2026-10-31-pg-mixed-w21", "pg-hot-row",
			"2026-10-31-pg-hot-row-w21", "cache-aside", "2026-10-31-cache-aside-w21", "kafka", "2026-10-31-kafka-w21");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String COLLECTIONS = "collections";

	private static final String PAUSE = "pauseMs";

	private TrialGc() {
	}

	/**
	 * The GC per request of an experiment's trial.
	 * @param results the results folder
	 * @param experiment the experiment
	 * @return the trial, its top rate, its requests and each kind's GC per request; empty
	 * without a trial or a reported GC
	 */
	static Optional<ObjectNode> of(Path results, Experiment experiment) {
		String trial = TRIALS.get(experiment.id());
		Optional<JsonNode> calibration = (trial != null)
				? ResultFiles.read(results.resolve("trials").resolve(trial).resolve("calibration.json"))
				: Optional.empty();
		if (calibration.isEmpty()) {
			return Optional.empty();
		}
		List<JsonNode> tops = PythonValues.get(calibration.get(), "replicates")
			.valueStream()
			.map((replicate) -> replicate.get("high").get("service"))
			.filter((service) -> !service.path("gc").path("byKind").isEmpty())
			.toList();
		long requests = tops.stream().mapToLong((service) -> service.get("requests").longValue()).sum();
		if (requests == 0) {
			return Optional.empty();
		}
		ObjectNode gc = NODES.objectNode().put("trial", trial);
		gc.set("rateRps", calibration.get().get("staircaseRps").get(calibration.get().get("staircaseRps").size() - 1));
		gc.put("requests", requests);
		ObjectNode perRequest = gc.putObject("perRequest");
		totals(tops).forEach((kind, total) -> perRequest.putObject(kind)
			.put(COLLECTIONS, total.collections() / requests)
			.put(PAUSE, total.pauseMs() / requests));
		return Optional.of(gc);
	}

	/**
	 * A calibration whose top passes' services carry the trial's GC per request scaled to
	 * their own requests, where they report none.
	 * @param calibration the run's {@code calibration.json}
	 * @param gc the trial's GC ({@link #of})
	 * @return a copy of the calibration
	 */
	static ObjectNode give(JsonNode calibration, JsonNode gc) {
		ObjectNode out = ((ObjectNode) calibration).deepCopy();
		ArrayNode replicates = NODES.arrayNode();
		PythonValues.get(calibration, "replicates").valueStream().forEach((replicate) -> {
			ObjectNode copy = ((ObjectNode) replicate).deepCopy();
			replicates.add(copy.set("high", topWithGc(replicate.get("high"), gc)));
		});
		out.set("replicates", replicates);
		ObjectNode passes = ((ObjectNode) calibration.get("passes")).deepCopy();
		out.set("passes", passes.set("high", topWithGc(calibration.get("passes").get("high"), gc)));
		return out;
	}

	// a top pass with the trial's GC, scaled to its requests, unless it reported its own
	private static JsonNode topWithGc(JsonNode entry, JsonNode gc) {
		JsonNode service = entry.get("service");
		if (PythonText.truthy(service.path("gc"))) {
			return entry;
		}
		double requests = service.get("requests").doubleValue();
		ObjectNode byKind = NODES.objectNode();
		gc.get("perRequest")
			.properties()
			.forEach((kind) -> byKind.putObject(kind.getKey())
				.put(COLLECTIONS, kind.getValue().get(COLLECTIONS).doubleValue() * requests)
				.put(PAUSE, kind.getValue().get(PAUSE).doubleValue() * requests));
		ObjectNode withGc = ((ObjectNode) service).deepCopy();
		withGc.putObject("gc")
			.set("byKind", byKind)
			.put("source", "per request as trial " + gc.get("trial").stringValue() + " measured at "
					+ PythonFormat.format(gc.get("rateRps").doubleValue(), "g") + " req/s");
		return ((ObjectNode) entry).deepCopy().set("service", withGc);
	}

	// each kind's collections and pause over the top passes,
	// in the order the kinds appear
	private static Map<String, Totals> totals(List<JsonNode> tops) {
		Map<String, Totals> totals = new LinkedHashMap<>();
		for (JsonNode service : tops) {
			service.get("gc")
				.get("byKind")
				.properties()
				.forEach((kind) -> totals.merge(kind.getKey(), Totals.of(kind.getValue()), Totals::plus));
		}
		return totals;
	}

	/**
	 * One kind's collections and pause, added up over passes.
	 *
	 * @param collections the collections
	 * @param pauseMs the pause (ms)
	 */
	private record Totals(double collections, double pauseMs) {

		static Totals of(JsonNode kind) {
			return new Totals(kind.get(COLLECTIONS).doubleValue(), kind.get(PAUSE).doubleValue());
		}

		Totals plus(Totals other) {
			return new Totals(this.collections + other.collections, this.pauseMs + other.pauseMs);
		}

	}

}
