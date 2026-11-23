package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.results.KneePairs;
import io.archsim.benchmarks.values.PythonValues;

/**
 * What the Java engine reproduces of the frozen engine's predictions: of the kept
 * calibrations either engine re-simulated, how many it gave exactly the frozen engine's
 * knee, with the one sentence the validation page and {@code /accuracy} show of it
 * (in-sample, never in the headline). It speaks only of the calibrations the two were
 * compared on: runs kept since were predicted by a later engine.
 */
final class JavaEngineCheck {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private JavaEngineCheck() {
	}

	/**
	 * The check, from the development checks under {@code results/dev/}.
	 * @param results the results folder
	 * @param engines the engines, which name the Java engine's version
	 * @return the check; empty while neither engine has re-simulated any calibration
	 */
	static Optional<ObjectNode> of(Path results, Engines engines) {
		List<ObjectNode> pairs = KneePairs.of(results.resolve("dev"));
		if (pairs.isEmpty()) {
			return Optional.empty();
		}
		int same = (int) pairs.stream().filter(KneePairs::sameKnee).count();
		String version = javaVersion(engines);
		String count = (same == pairs.size()) ? "all " + same : same + " of the " + pairs.size();
		String text = "Re-simulated, in-sample: on the inputs " + KneePairs.FROZEN_ENGINE
				+ " derived (whole cores, no cost law, no host machine), engine " + version + " (Java) gives exactly "
				+ KneePairs.FROZEN_ENGINE + "'s knees on " + count + " calibrations the two engines were compared on; "
				+ "the predictions shown for the latest runs use its own inputs instead.";
		ObjectNode check = NODES.objectNode().put("version", version).put("reproduces", KneePairs.FROZEN_ENGINE);
		return Optional.of(check.put("sameKnees", same).put("keptCalibrations", pairs.size()).put("text", text));
	}

	/**
	 * The validation page's sentence on the Java engine, linked to its parity report.
	 * @param results the results folder
	 * @param engines the engines
	 * @return the sentence
	 */
	static String block(Path results, Engines engines) {
		return of(results, engines).map((check) -> {
			String text = check.get("text").stringValue();
			return text.substring(0, text.length() - 1) + " ([java-parity.md](validation/java-parity.md)).";
		}).orElse("_Not re-simulated yet._");
	}

	// the latest Java engine engines.json lists
	private static String javaVersion(Engines engines) {
		List<JsonNode> entries = engines.entries();
		for (int i = entries.size() - 1; i >= 0; i--) {
			if ("java".equals(PythonValues.get(entries.get(i), "engine").stringValue(""))) {
				return entries.get(i).get("version").stringValue();
			}
		}
		throw new IllegalStateException("engines.json lists no Java engine");
	}

}
