package io.archsim.benchmarks.campaign;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
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
 * is uncommitted, and the designs are on the host machine the freeze names by its fit's
 * digest ({@code hostFit}, ADR-0095), or on none when it names none. The frozen Python
 * engine was known by its tree; nothing runs it any more.
 */
public final class Seals {

	private static final int SHORT_ID = 12;

	private static final String TAG = "tag";

	private final List<JsonNode> frozen;

	private final Function<String, Optional<String>> sourcesAt;

	private final @Nullable String hostFit;

	private Seals(List<JsonNode> frozen, Function<String, Optional<String>> sourcesAt, @Nullable String hostFit) {
		this.frozen = List.copyOf(frozen);
		this.sourcesAt = sourcesAt;
		this.hostFit = hostFit;
	}

	/**
	 * The seals the engines' file sets in a repository.
	 * @param engines {@code benchmarks/engines.json}
	 * @param git the repository, whose tags hold the frozen engines' sources
	 * @param hostFit the digest of the host machine the command's designs would run on
	 * ({@link io.archsim.benchmarks.machine.MachineFit#digest()}); {@code null} for none
	 * @return the seals
	 */
	public static Seals of(Path engines, GitRepository git, @Nullable String hostFit) {
		return of(engines, (tag) -> Provenance.engineSources(git, "refs/tags/" + tag), hostFit);
	}

	/**
	 * The seals the engines' file sets, with the sources each tag holds given.
	 * @param engines {@code benchmarks/engines.json}
	 * @param sourcesAt the hash over the engine's source trees at a tag; empty for a tag
	 * the clone lacks
	 * @param hostFit the digest of the host machine the designs would run on;
	 * {@code null} for none
	 * @return the seals
	 */
	static Seals of(Path engines, Function<String, Optional<String>> sourcesAt, @Nullable String hostFit) {
		List<JsonNode> java = Engines.read(engines)
			.frozen()
			.stream()
			.filter((engine) -> engine.path(Engines.SOURCES_KEY).isString())
			.toList();
		return new Seals(java, sourcesAt, hostFit);
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
		return notItsHost(freeze.get());
	}

	// why the designs would not run on the host machine the freeze names, empty when
	// they would; a hostFit that is no digest cannot be checked, so it opens nothing
	private Optional<String> notItsHost(JsonNode freeze) {
		JsonNode named = PythonValues.get(freeze, Engines.HOST_FIT_KEY);
		if (!named.isString() && !named.isNull()) {
			return Optional.of("its freeze's hostFit is not a fit's digest, so it cannot be checked.");
		}
		String frozenHost = named.isString() ? named.stringValue() : null;
		if (Objects.equals(frozenHost, this.hostFit)) {
			return Optional.empty();
		}
		String differ = " and the designs would run on " + host(this.hostFit) + ": ";
		if (frozenHost == null) {
			return Optional.of("its freeze names no host machine," + differ + "`BENCH_HOST=off` puts them on none "
					+ "(ADR-0095).");
		}
		return Optional.of("its freeze names " + host(frozenHost) + " (`hostFit`)," + differ + "they run on its fit "
				+ "with BENCH_HOST and BENCH_HOST_CLOCK unset, from the contention checks as they were at its tag or "
				+ "from a file of the fit's keys that BENCH_HOST_FIT names (ADR-0095).");
	}

	private static String host(@Nullable String fit) {
		return (fit != null) ? "host machine " + fit : "no host machine";
	}

	// a hash or a tag as text; what git could not name reads unknown
	private static String text(JsonNode value) {
		return value.isString() ? value.stringValue() : "unknown";
	}

	private static String prefix(String id) {
		return id.substring(0, Math.min(SHORT_ID, id.length()));
	}

}
