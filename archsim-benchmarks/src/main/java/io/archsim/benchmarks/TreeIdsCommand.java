package io.archsim.benchmarks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.experiments.Engines;
import io.archsim.benchmarks.history.CommitMap;
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.history.TreeIds;

/**
 * {@code tree-ids}: writes {@code benchmarks/results/tree-ids.tsv}, today's tree of each
 * engine tree {@code benchmarks/engines.json} records, from the history before a rewrite
 * (a clone of its backup) and the rewrite's commit map.
 */
@Command(name = "tree-ids",
		description = "write benchmarks/results/tree-ids.tsv: today's tree of each engine tree benchmarks/engines.json "
				+ "records, through the history before a rewrite",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class TreeIdsCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--before", required = true,
			description = "a clone of the history before the rewrite: git clone --mirror its backup bundle")
	private @Nullable Path before;

	@Option(names = "--commit-map", required = true,
			description = "the rewrite's commit map: a header, then the old and the new ID of each commit")
	private @Nullable Path commitMap;

	private final RepositoryLayout layout;

	TreeIdsCommand(RepositoryLayout layout) {
		this.layout = layout;
	}

	/**
	 * Derives the map and writes it.
	 * @return 0 when written; 1, saying why, when a tree maps to none or to several, git
	 * fails or cannot run, or the commit map is malformed or not there
	 */
	@Override
	public Integer call() {
		TreeIds trees;
		try {
			trees = TreeIds.derive(Engines.read(this.layout.engines()).engineTrees().values(), rewrite());
		}
		catch (IllegalStateException | IllegalArgumentException | UncheckedIOException ex) {
			this.spec.commandLine().getErr().println(ex.getMessage());
			return 1;
		}
		Path file = this.layout.treeIds();
		try {
			Files.writeString(file, trees.text(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		this.spec.commandLine().getOut().println("wrote " + this.layout.name(file));
		return 0;
	}

	// the history before the rewrite, its commit map, and this repository
	private TreeIds.Rewrite rewrite() {
		return new TreeIds.Rewrite(new GitRepository(Objects.requireNonNull(this.before)),
				CommitMap.read(Objects.requireNonNull(this.commitMap)), new GitRepository(this.layout.root()));
	}

}
