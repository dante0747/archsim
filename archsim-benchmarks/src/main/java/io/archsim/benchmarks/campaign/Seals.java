package io.archsim.benchmarks.campaign;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.HeldOut;
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.provenance.Provenance;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The held-out experiments' seal: one may be calibrated and measured only by an engine
 * frozen in {@code engines.json}, so that the prediction saved before the first
 * measurement is the frozen engine's (ADR-0088). The other experiments are never sealed.
 * What would run is the frozen engine when a frozen entry names the hash over its three
 * source trees ({@code engineSources}, as a run's provenance records it), that entry's
 * tag is in the clone and holds those very sources, nothing in the engine or the harness
 * is uncommitted, and the designs are on no host machine of this machine's own fitting
 * ({@code BENCH_HOST}), which no freeze names. The frozen Python engine was known by its
 * tree; nothing runs it any more.
 */
public final class Seals {

	private static final int SHORT_ID = 12;

	private static final String TAG = "tag";

	private final List<JsonNode> frozen;

	private final Function<String, Optional<String>> sourcesAt;

	private final boolean onHostMachine;

	private Seals(List<JsonNode> frozen, Function<String, Optional<String>> sourcesAt, boolean onHostMachine) {
		this.frozen = List.copyOf(frozen);
		this.sourcesAt = sourcesAt;
		this.onHostMachine = onHostMachine;
	}

	/**
	 * The seals the engines' file sets in a repository.
	 * @param engines {@code benchmarks/engines.json}
	 * @param git the repository, whose tags hold the frozen engines' sources
	 * @param onHostMachine whether the command's designs would run on a host machine
	 * fitted from this machine's checks ({@code BENCH_HOST=on})
	 * @return the seals
	 */
	public static Seals of(Path engines, GitRepository git, boolean onHostMachine) {
		return of(engines, (tag) -> Provenance.engineSources(git, "refs/tags/" + tag), onHostMachine);
	}

	/**
	 * The seals the engines' file sets, with the sources each tag holds given.
	 * @param engines {@code benchmarks/engines.json}
	 * @param sourcesAt the hash over the engine's source trees at a tag; empty for a tag
	 * the clone lacks
	 * @param onHostMachine whether the designs would run on a host machine
	 * @return the seals
	 */
	static Seals of(Path engines, Function<String, Optional<String>> sourcesAt, boolean onHostMachine) {
		List<JsonNode> java = Engines.read(engines)
			.frozen()
			.stream()
			.filter((engine) -> engine.path(Engines.SOURCES_KEY).isString())
			.toList();
		return new Seals(java, sourcesAt, onHostMachine);
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
		String sealed = experiment.id() + " is a held-out experiment (registered " + heldOut.registered() + ", commit "
				+ prefix(heldOut.commit()) + ") and stays sealed: ";
		if (this.onHostMachine) {
			return Optional.of(sealed + "BENCH_HOST=on puts the designs on a host machine fitted from this machine's "
					+ "own checks, which no freeze names. Unset it.");
		}
		return notTheFrozenEngine(provenance).map((why) -> sealed + why + " `./gradlew :archsim-benchmarks:run "
				+ "--args=\"smoke " + experiment.id() + "\"` checks its stack without measuring it.");
	}

	// why what would run is not a frozen engine as frozen; empty when it is
	private Optional<String> notTheFrozenEngine(JsonNode provenance) {
		JsonNode sources = PythonValues.get(provenance, Engines.SOURCES_KEY);
		Optional<JsonNode> freeze = this.frozen.stream()
			.filter((engine) -> sources.isString() && sources.equals(engine.get(Engines.SOURCES_KEY)))
			.findFirst();
		if (freeze.isEmpty()) {
			return Optional.of("the engine as it is (sources " + prefix(text(sources))
					+ ") is not frozen in benchmarks/engines.json, so it may not be calibrated or measured "
					+ "(`./gradlew :archsim-benchmarks:run --args=\"engine-id\"` prints what a freeze records, "
					+ "ADR-0088).");
		}
		String tag = text(PythonValues.get(freeze.get(), TAG));
		if (this.sourcesAt.apply(tag).filter(sources.stringValue()::equals).isEmpty()) {
			return Optional.of("its freeze names tag " + tag + ", which is not in this clone or holds other sources "
					+ "than the entry says, so the freeze cannot be checked (`git fetch --tags`).");
		}
		JsonNode dirty = PythonValues.get(provenance, "dirty");
		if (!dirty.isBoolean() || dirty.booleanValue()) {
			return Optional.of("the engine's sources or the harness have uncommitted changes (or git cannot "
					+ "tell), so the frozen engine's sources do not describe what would run.");
		}
		return Optional.empty();
	}

	// a hash or a tag as text; what git could not name reads unknown
	private static String text(JsonNode value) {
		return value.isString() ? value.stringValue() : "unknown";
	}

	private static String prefix(String id) {
		return id.substring(0, Math.min(SHORT_ID, id.length()));
	}

}
