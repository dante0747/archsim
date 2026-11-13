package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * Garbage collection as the service's runtime counted it in a pass: the pause the model
 * samples (lognormal, p95 2.5 times its median, so the median gives the measured mean)
 * and the requests between collections. Their CPU is in the CPU per request already; the
 * pause adds the stall every request on the replica waits through.
 */
final class GcInputs {

	/** V8's scavenges: short and frequent, left in the CPU per request. */
	private static final String SCAVENGE = "minor";

	/** A pause's p95 over its median. */
	private static final double PAUSE_SPREAD = 2.5;

	private static final int DECIMALS = 4;

	/** Why a Python service's collections are no pause in the model. */
	private static final String PYTHON_WORKERS = "not modelled as a pause: each gunicorn worker collects on its own "
			+ "and stalls only its own request; the collections' CPU is in the CPU per request";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private GcInputs() {
	}

	/**
	 * Node.js's mark-compact cycles from the service's {@code /stats}: the pause per
	 * cycle (its final pause and the incremental steps of the pass), and the pause per
	 * request the model adds, to take off the CPU per request. Scavenges stay in the CPU
	 * per request: a model pause has one size, and averaging them in would shrink it.
	 * @param stats the service's {@code /stats} in the top pass
	 * @return the inputs and the pause per request
	 */
	static Node node(JsonNode stats) {
		JsonNode gc = PythonValues.get(stats, "gc");
		if (!PythonText.truthy(gc) || !PythonText.truthy(PythonValues.get(gc, "byKind"))) {
			return Node.none();
		}
		String source = PythonText.truthy(PythonValues.get(gc, "source")) ? " (" + gc.get("source").stringValue() + ")"
				: "";
		MarkCompacts cycles = MarkCompacts.of(gc.get("byKind"));
		double requests = PythonValues.numberOrZero(stats, "requests");
		if (cycles.count() <= 0 || cycles.pauseMs() <= 0 || requests <= 0) {
			return new Node(noPause("no mark-compact in the pass" + source + "; " + cycles.scavenges()), 0.0);
		}
		double mean = cycles.pauseMs() / cycles.count();
		String how = "V8's mark-compact cycles in the pass" + source + ": "
				+ PythonFormat.format(cycles.pauseMs(), ".1f") + " ms (" + String.join(", ", cycles.steps()) + ") over "
				+ PythonFormat.format(cycles.count(), "g") + " cycles, a mean of " + PythonFormat.format(mean, ".3f")
				+ " ms (the model's median: mean / 1.17); " + cycles.scavenges();
		String spacing = PythonText.str(stats.get("requests")) + " requests / "
				+ PythonFormat.format(cycles.count(), "g") + " mark-compact cycles in the pass" + source;
		return new Node(pauses(new Pauses(mean, how, requests / cycles.count(), spacing)), cycles.pauseMs() / requests);
	}

	/**
	 * The stop-the-world pauses of a Java or Go service; a Python service's are left out,
	 * since each gunicorn worker collects on its own and stalls only its own request.
	 * @param experiment the experiment
	 * @param stats the service's {@code /stats} in the top pass
	 * @return the inputs
	 */
	static ObjectNode runtime(Experiment experiment, JsonNode stats) {
		if ("python".equals(experiment.runtime())) {
			return noPause(PYTHON_WORKERS);
		}
		JsonNode gc = PythonValues.get(stats, "gc");
		double collections = number(gc, "collections");
		double pause = number(gc, "pauseMs");
		double requests = PythonValues.numberOrZero(stats, "requests");
		if (collections <= 0 || pause <= 0 || requests <= 0) {
			String count = (gc.isObject() && PythonText.truthy(gc.path("collections")))
					? PythonText.str(gc.get("collections")) : "0";
			return noPause("no stop-the-world pause in the pass (" + count + " collections, "
					+ PythonFormat.format(pause, "g") + " ms)");
		}
		double mean = pause / collections;
		String counted = PythonText.str(gc.get("collections"));
		String how = "the runtime's stop-the-world pauses in the pass: " + PythonFormat.format(pause, ".2f")
				+ " ms over " + counted + " collections, a mean of " + PythonFormat.format(mean, ".4f")
				+ " ms (the model's median: mean / 1.17)";
		return pauses(new Pauses(mean, how, requests / collections,
				PythonText.str(stats.get("requests")) + " requests / " + counted + " collections in the pass"));
	}

	// no pause to model, and why
	private static ObjectNode noPause(String how) {
		ObjectNode inputs = NODES.objectNode();
		inputs.putObject("gcPauseMs").put("value", 0).put("how", how);
		return inputs;
	}

	// the pause the model samples and the requests between two of them
	private static ObjectNode pauses(Pauses pauses) {
		ObjectNode inputs = NODES.objectNode();
		inputs.putObject("gcPauseMs").put("value", median(pauses.meanMs())).put("how", pauses.how());
		inputs.putObject("gcEveryRequests")
			.put("value", (long) Math.max(1, Math.rint(pauses.requestsApart())))
			.put("how", pauses.spacing());
		return inputs;
	}

	// the lognormal's median that gives this mean
	private static double median(double mean) {
		double sigma = StrictMath.log(PAUSE_SPREAD) / ModelDistributions.Z95;
		return PythonRound.round(mean / StrictMath.exp(sigma * sigma / 2), DECIMALS);
	}

	private static double number(JsonNode object, String key) {
		return object.isObject() ? PythonValues.numberOrZero(object, key) : 0.0;
	}

	/**
	 * A pass's mark-compact cycles: their count (the major collections), their pause
	 * (every kind's but the scavenges'), each kind's pause in words, and the scavenges in
	 * words.
	 *
	 * @param count the cycles
	 * @param pauseMs their pause: the final pauses and the incremental steps (ms)
	 * @param steps each kind's pause, in words
	 * @param scavenges the scavenges left in the CPU per request, in words
	 */
	private record MarkCompacts(double count, double pauseMs, List<String> steps, String scavenges) {

		// the pauses added as Python's sum adds them: compensated, in the kinds' order
		static MarkCompacts of(JsonNode kinds) {
			List<JsonNode> pauses = new ArrayList<>();
			List<String> steps = new ArrayList<>();
			for (var kind : kinds.properties()) {
				if (!kind.getKey().equals(SCAVENGE)) {
					double pauseMs = number(kind.getValue(), "pauseMs");
					pauses.add(PythonValues.number(pauseMs));
					steps.add(kind.getKey() + " " + PythonFormat.format(pauseMs, ".1f") + " ms");
				}
			}
			JsonNode scavenges = kinds.path(SCAVENGE);
			return new MarkCompacts(number(kinds.path("major"), "collections"), PythonValues.sum(pauses).doubleValue(),
					steps,
					PythonFormat.format(number(scavenges, "collections"), "g") + " scavenges, "
							+ PythonFormat.format(number(scavenges, "pauseMs"), ".1f")
							+ " ms, left in the CPU per request");
		}

	}

	/**
	 * The pauses the model samples.
	 *
	 * @param meanMs a pause's mean (ms)
	 * @param how how it was found
	 * @param requestsApart the requests between two pauses
	 * @param spacing how the spacing was found
	 */
	private record Pauses(double meanMs, String how, double requestsApart, String spacing) {

	}

	/**
	 * Node.js's GC inputs and the pause per request they add.
	 *
	 * @param inputs the inputs
	 * @param perRequestMs the pause per request the model adds (ms)
	 */
	record Node(ObjectNode inputs, double perRequestMs) {

		/**
		 * No GC reported: no inputs and no pause.
		 * @return the inputs, none
		 */
		static Node none() {
			return new Node(NODES.objectNode(), 0.0);
		}

	}

}
