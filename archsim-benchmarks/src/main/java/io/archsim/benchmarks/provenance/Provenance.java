package io.archsim.benchmarks.provenance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.history.GitRepository;

/**
 * What produced a prediction or a check: the commit, a hash over the Java engine's three
 * source trees and the build that ran, a hash over the harness's trees (git object IDs,
 * so the same on every machine and line-ending setting), whether any of them had changes
 * that are not committed (then the IDs do not describe what ran), and the machine.
 */
public final class Provenance {

	/**
	 * The Java engine's source trees: the command line's too, though the harness runs the
	 * engine in its own process, so that {@code engineSources} stays comparable with the
	 * checks and predictions recorded through the command line.
	 */
	static final List<String> ENGINE_SOURCES = List.of("archsim-engine", "archsim-model", "archsim-cli");

	/**
	 * What turns calibration passes into predictions and measurements, besides the
	 * engine.
	 */
	static final List<String> HARNESS = List.of("archsim-benchmarks", "benchmarks/compose.yaml", "benchmarks/service",
			"benchmarks/service-java", "benchmarks/service-go", "benchmarks/service-python", "benchmarks/k6",
			"benchmarks/db", "benchmarks/knee-range.json");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final GitRepository git;

	private final String machineId;

	private final @Nullable String javaBuild;

	/**
	 * The provenance of what runs in a repository.
	 * @param git the repository
	 * @param machineId the machine's name ({@link MachineName})
	 * @param javaBuild the engine's build ({@link EngineBuild}), or {@code null} when it
	 * runs from folders of classes
	 */
	public Provenance(GitRepository git, String machineId, @Nullable String javaBuild) {
		this.git = git;
		this.machineId = machineId;
		this.javaBuild = javaBuild;
	}

	/**
	 * The provenance as a run's files record it.
	 * @return {@code commit}, {@code engineSources}, {@code javaBuild},
	 * {@code harnessHash}, {@code dirty} and {@code machineId}; what git cannot name is
	 * None
	 */
	public ObjectNode of() {
		List<String> paths = new ArrayList<>(ENGINE_SOURCES);
		paths.addAll(HARNESS);
		ObjectNode provenance = NODES.objectNode();
		provenance.put("commit", this.git.revision("HEAD").orElse(null));
		provenance.put(Engines.SOURCES_KEY, treesHash(this.git, "HEAD", ENGINE_SOURCES));
		provenance.put("javaBuild", this.javaBuild);
		provenance.put("harnessHash", treesHash(this.git, "HEAD", HARNESS));
		provenance.put("dirty", this.git.hasChanges(paths).orElse(null));
		provenance.put("machineId", this.machineId);
		return provenance;
	}

	/**
	 * What identifies the Java engine at a commit: the hash over its three source trees
	 * there. A frozen engine records its tag's ({@code engines.json}), and a run's
	 * provenance the commit's it ran from, so the two agree only for the frozen sources.
	 * @param git the repository
	 * @param revision the commit: {@code HEAD}, or a freeze's tag
	 * @return the hash; empty when git cannot name the revision, a tag this clone lacks
	 * for one
	 */
	public static Optional<String> engineSources(GitRepository git, String revision) {
		return git.revision(revision).map((commit) -> treesHash(git, revision, ENGINE_SOURCES));
	}

	// a SHA-256 over "<path> <tree>" lines, a path the commit lacks named None as the
	// Python harness's f-string named it
	private static String treesHash(GitRepository git, String revision, List<String> paths) {
		String trees = paths.stream()
			.map((path) -> path + " " + git.revision(revision + ":" + path).orElse("None"))
			.collect(Collectors.joining("\n"));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(trees.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// every Java platform has SHA-256 (MessageDigest's contract)
			throw new IllegalStateException(ex);
		}
	}

}
