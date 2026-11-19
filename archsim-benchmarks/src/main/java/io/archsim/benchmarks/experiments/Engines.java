package io.archsim.benchmarks.experiments;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The engine versions in {@code benchmarks/engines.json}: what identifies each one's
 * sources (the Python engine's tree, the hash over the Java engine's source trees), when
 * it was frozen (its tag) and the experiments it was tuned on.
 */
public final class Engines {

	/**
	 * What a frozen Java engine's entry, and a run's provenance, keep the hash over the
	 * engine's source trees under.
	 */
	public static final String SOURCES_KEY = "engineSources";

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
	 * The engines frozen for prospective predictions: {@code frozenAt} and {@code tag}
	 * set, and what identifies their sources: {@code engineTree}, the frozen Python
	 * engine's tree, or {@code engineSources}, the hash over the Java engine's source
	 * trees that a run's provenance records.
	 * @return each one's entry, in the file's order
	 */
	public List<JsonNode> frozen() {
		return this.entries.stream()
			.filter((entry) -> isFrozen(entry) && (PythonText.truthy(PythonValues.get(entry, "engineTree"))
					|| PythonText.truthy(PythonValues.get(entry, SOURCES_KEY))))
			.toList();
	}

	/**
	 * The engine trees the engines record: the tree of the frozen Python engine's sources
	 * each one was known by.
	 * @return each engine's tree by its version, in the file's order, for the engines
	 * that record one
	 */
	public Map<String, String> engineTrees() {
		Map<String, String> trees = new LinkedHashMap<>();
		this.entries.stream()
			.filter((entry) -> entry.path("engineTree").isString())
			.forEach((entry) -> trees.put(entry.get("version").stringValue(), entry.get("engineTree").stringValue()));
		return trees;
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
		if (engine.isEmpty() || !isFrozen(engine.get())) {
			return "tuned-on";
		}
		boolean tunedOn = engine.get()
			.path("tunedOn")
			.valueStream()
			.anyMatch((name) -> name.stringValue("").equals(experiment.id()));
		return tunedOn ? "tuned-on" : "held-out";
	}

	private static boolean isFrozen(JsonNode engine) {
		return PythonText.truthy(PythonValues.get(engine, "frozenAt"))
				&& PythonText.truthy(PythonValues.get(engine, "tag"));
	}

}
