package io.archsim.benchmarks.history;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A throwaway git repository for the history's tests: commits of the files they give, by
 * a test identity, without the project's hooks or signing.
 */
public final class GitFixture {

	private final Path directory;

	private GitFixture(Path directory) {
		this.directory = directory;
	}

	/**
	 * A new repository.
	 * @param directory a folder not there yet, or empty
	 * @return the repository
	 * @throws IOException when the folder cannot be made or git cannot run
	 */
	public static GitFixture init(Path directory) throws IOException {
		Files.createDirectories(directory);
		GitFixture repository = new GitFixture(directory);
		repository.git("init", "-q");
		return repository;
	}

	/**
	 * Writes files and commits them.
	 * @param files each file's path from the root, and its text
	 * @return the commit's ID
	 * @throws IOException when a file cannot be written or git cannot run
	 */
	public String commit(Map<String, String> files) throws IOException {
		for (Map.Entry<String, String> file : files.entrySet()) {
			Path path = this.directory.resolve(file.getKey());
			Files.createDirectories(Objects.requireNonNull(path.getParent()));
			Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
		}
		git("-c", "core.autocrlf=false", "add", "-A");
		git("-c", "user.name=test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "-c",
				"core.autocrlf=false", "commit", "-q", "--no-verify", "-m", "commit");
		return git("rev-parse", "HEAD");
	}

	/**
	 * A folder's tree in a commit.
	 * @param commit the commit
	 * @param folder the folder
	 * @return the tree's ID
	 * @throws IOException when git cannot run
	 */
	public String tree(String commit, String folder) throws IOException {
		return git("rev-parse", commit + ":" + folder);
	}

	/**
	 * The repository's folder.
	 * @return the folder
	 */
	public Path directory() {
		return this.directory;
	}

	private String git(String... arguments) throws IOException {
		List<String> command = new ArrayList<>(List.of("git", "-C", this.directory.toString()));
		command.addAll(List.of(arguments));
		Process git = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
		String output;
		try (InputStream answers = git.getInputStream()) {
			output = new String(answers.readAllBytes(), StandardCharsets.UTF_8).strip();
		}
		try {
			if (git.waitFor() != 0) {
				throw new IOException(String.join(" ", command) + " failed (git's reason is in the test's output)");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", ex);
		}
		return output;
	}

}
