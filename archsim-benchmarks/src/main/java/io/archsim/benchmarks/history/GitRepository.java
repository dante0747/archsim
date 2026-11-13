package io.archsim.benchmarks.history;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A git repository the harness reads with {@code git}: the commits its refs reach and the
 * tree of a folder in each.
 */
public final class GitRepository {

	/**
	 * {@code git cat-file}'s answer for a tree: its ID and its type; anything else is not
	 * one.
	 */
	private static final Pattern TREE = Pattern.compile("(\\S+) tree");

	private final Path directory;

	/**
	 * A repository.
	 * @param directory its working tree or its git directory
	 */
	public GitRepository(Path directory) {
		this.directory = directory;
	}

	/**
	 * Every commit the repository's refs reach.
	 * @return their IDs
	 * @throws IllegalStateException when git fails, with what it said
	 */
	List<String> commits() {
		return run(List.of("rev-list", "--all"), "").lines().toList();
	}

	/**
	 * The tree of a folder in each of some revisions, read in one {@code git} run.
	 * @param revisions commits or tags
	 * @param folder the folder, from the repository's root
	 * @return each revision's tree of the folder, for the revisions that have it
	 * @throws IllegalStateException when git fails, with what it said
	 */
	Map<String, String> folderTrees(List<String> revisions, String folder) {
		StringBuilder input = new StringBuilder();
		revisions.forEach((revision) -> input.append(revision).append(':').append(folder).append('\n'));
		List<String> answers = run(List.of("cat-file", "--batch-check=%(objectname) %(objecttype)"), input.toString())
			.lines()
			.toList();
		Map<String, String> trees = new LinkedHashMap<>();
		for (int i = 0; i < revisions.size(); i++) {
			Matcher tree = TREE.matcher(answers.get(i));
			if (tree.matches()) {
				trees.put(revisions.get(i), tree.group(1));
			}
		}
		return trees;
	}

	// git reads its input from a file and writes its errors to one, never to
	// pipes: a whole history's input to cat-file (about 47 KB for 820 commits)
	// outgrows a Windows pipe of a few KB while git's answers fill the other,
	// and writing one before reading the other deadlocks
	private String run(List<String> arguments, String input) {
		List<String> command = new ArrayList<>(List.of("git", "-C", this.directory.toString()));
		command.addAll(arguments);
		try (Exchange exchange = Exchange.create()) {
			Files.writeString(exchange.input(), input, StandardCharsets.UTF_8);
			return execute(command, exchange);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String execute(List<String> command, Exchange exchange) throws IOException {
		Process git = new ProcessBuilder(command).redirectInput(exchange.input().toFile())
			.redirectError(exchange.errors().toFile())
			.start();
		String output;
		try (InputStream answers = git.getInputStream()) {
			output = new String(answers.readAllBytes(), StandardCharsets.UTF_8);
		}
		int exitCode = waitFor(git, command);
		if (exitCode != 0) {
			// bytes that are not UTF-8 are replaced, so
			// git's reason is never lost to decoding
			String said = new String(Files.readAllBytes(exchange.errors()), StandardCharsets.UTF_8).strip();
			throw new IllegalStateException(
					String.join(" ", command) + " failed with exit code " + exitCode + ": " + said);
		}
		return output;
	}

	// an interruption stops git and waits for it to end,
	// so the files it held can be deleted
	private static int waitFor(Process git, List<String> command) {
		try {
			return git.waitFor();
		}
		catch (InterruptedException ex) {
			git.destroyForcibly().onExit().join();
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while running " + String.join(" ", command), ex);
		}
	}

	/**
	 * A folder of git's input and errors, deleted when closed; a failure to delete it is
	 * kept with the exception that ended the run.
	 */
	private static final class Exchange implements AutoCloseable {

		private final Path folder;

		private Exchange(Path folder) {
			this.folder = folder;
		}

		static Exchange create() throws IOException {
			return new Exchange(Files.createTempDirectory("archsim-git"));
		}

		Path input() {
			return this.folder.resolve("stdin.txt");
		}

		Path errors() {
			return this.folder.resolve("stderr.txt");
		}

		@Override
		public void close() throws IOException {
			Files.deleteIfExists(input());
			Files.deleteIfExists(errors());
			Files.deleteIfExists(this.folder);
		}

	}

}
