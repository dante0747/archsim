package io.archsim.benchmarks.campaign;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.HeldOut;
import io.archsim.benchmarks.history.TreeIds;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The held-out experiments' seal: one may be calibrated and measured only by an engine
 * frozen in {@code engines.json}, with nothing uncommitted in the engine or the harness,
 * so that the prediction saved before the first measurement is the frozen engine's. The
 * other experiments are never sealed. A frozen engine is known by its tree, as the Python
 * engine's provenance named it, mapped to today's tree ({@code tree-ids.tsv}: the 13 Nov
 * 2026 rewrite changed them); the Java engine's provenance names its sources
 * ({@code engineSources}), so it stays sealed until its freeze says what identifies it
 * (ADR-0062).
 */
public final class Seals {

	private static final int SHORT_ID = 12;

	private final List<JsonNode> frozen;

	private final UnaryOperator<String> currentTree;

	private Seals(List<JsonNode> frozen, UnaryOperator<String> currentTree) {
		this.frozen = List.copyOf(frozen);
		this.currentTree = currentTree;
	}

	/**
	 * The seals the engines' file and the tree map set.
	 * @param engines {@code benchmarks/engines.json}
	 * @param treeIds {@code benchmarks/results/tree-ids.tsv}, which need not be there
	 * @return the seals
	 */
	public static Seals of(Path engines, Path treeIds) {
		UnaryOperator<String> current = Files.exists(treeIds) ? TreeIds.read(treeIds)::current : (tree) -> tree;
		return new Seals(Engines.read(engines).frozen(), current);
	}

	/**
	 * Why an experiment may not be calibrated or measured now.
	 * @param experiment the experiment
	 * @param provenance what would produce its predictions ({@code Provenance})
	 * @return the reason; empty when it may
	 */
	public Optional<String> of(Experiment experiment, JsonNode provenance) {
		HeldOut heldOut = experiment.heldOut();
		if (heldOut == null) {
			return Optional.empty();
		}
		JsonNode tree = PythonValues.get(provenance, "engineTree");
		String sealed = experiment.id() + " is a held-out experiment (registered " + heldOut.registered() + ", commit "
				+ prefix(heldOut.commit()) + ") and stays sealed";
		if (this.frozen.stream().noneMatch((engine) -> frozenAs(engine, tree))) {
			String engine = provenance.has("engineSources") ? "the Java engine is not frozen yet (ADR-0062)"
					: "this engine (tree " + prefix(PythonText.str(tree))
							+ ") is not frozen in benchmarks/engines.json";
			return Optional.of(sealed + ": " + engine + ", so it may not be calibrated or measured. "
					+ "`./gradlew :archsim-benchmarks:run --args=\"smoke " + experiment.id()
					+ "\"` checks its stack without measuring it.");
		}
		JsonNode dirty = PythonValues.get(provenance, "dirty");
		if (!dirty.isBoolean() || dirty.booleanValue()) {
			return Optional.of(sealed + ": the engine's sources or the harness have uncommitted changes (or git "
					+ "cannot tell), so the frozen engine's sources do not describe what would run.");
		}
		return Optional.empty();
	}

	// whether a frozen engine's recorded tree is, today, the tree that runs
	private boolean frozenAs(JsonNode engine, JsonNode tree) {
		JsonNode recorded = PythonValues.get(engine, "engineTree");
		return recorded.isString() && tree.isString()
				&& this.currentTree.apply(recorded.stringValue()).equals(tree.stringValue());
	}

	private static String prefix(String tree) {
		return tree.substring(0, Math.min(SHORT_ID, tree.length()));
	}

}
