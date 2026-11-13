package io.archsim.benchmarks.experiments;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The engine versions in {@code benchmarks/engines.json}: each one's source tree, when it
 * was frozen (its tag) and the experiments it was tuned on.
 */
public final class Engines {

	private final List<JsonNode> entries;

	private Engines(List<JsonNode> entries) {
		this.entries = entries;
	}

	/**
	 * The engines a file lists; none when there is no file.
	 * @param path {@code engines.json}
	 * @return the engines
	 */
	public static Engines read(Path path) {
		return new Engines(ResultFiles.read(path).map((list) -> list.valueStream().toList()).orElse(List.of()));
	}

	/**
	 * The engines, as listed.
	 * @return each engine's entry
	 */
	public List<JsonNode> entries() {
		return this.entries;
	}

	/**
	 * Whether an engine's predictions of an experiment are out of sample:
	 * {@code held-out} for a held-out experiment predicted by an engine frozen here
	 * ({@code frozenAt} and {@code tag} set) that does not list it in {@code tunedOn},
	 * else {@code tuned-on}: the four original experiments were seen while the model was
	 * built, and an engine never frozen could have been tuned on anything measured before
	 * it.
	 * @param engineVersion the engine that predicted, or None
	 * @param experiment the experiment
	 * @return {@code held-out} or {@code tuned-on}
	 */
	public String sampleLabel(JsonNode engineVersion, Experiment experiment) {
		if (!experiment.isHeldOut()) {
			return "tuned-on";
		}
		Optional<JsonNode> engine = this.entries.stream()
			.filter((entry) -> PythonValues.get(entry, "version").equals(engineVersion))
			.findFirst();
		if (engine.isEmpty() || !frozen(engine.get())) {
			return "tuned-on";
		}
		boolean tunedOn = engine.get()
			.path("tunedOn")
			.valueStream()
			.anyMatch((name) -> name.stringValue("").equals(experiment.id()));
		return tunedOn ? "tuned-on" : "held-out";
	}

	private static boolean frozen(JsonNode engine) {
		return PythonText.truthy(PythonValues.get(engine, "frozenAt"))
				&& PythonText.truthy(PythonValues.get(engine, "tag"));
	}

}
