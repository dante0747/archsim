package io.archsim.tools.commits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The commits of a revision range, through {@code git log}, read as UTF-8 whatever the
 * console's code page: Windows' default cannot read every message.
 */
final class GitLog {

	private static final String SEPARATOR = "\u0000";

	private static final int SHORT_SHA = 10;

	private GitLog() {
	}

	/**
	 * Every commit in a range, oldest first.
	 * @param range a revision range, {@code origin/main..HEAD} say
	 * @param directory the repository, or the empty path for the working folder
	 * @return each commit
	 * @throws IOException when git cannot run or fails, its errors in the message
	 */
	static List<Commit> commits(String range, Path directory) throws IOException {
		ProcessBuilder git = new ProcessBuilder("git", "log", "--reverse", "--format=%H%x00%B%x00", range);
		if (!directory.toString().isEmpty()) {
			git.directory(directory.toFile());
		}
		// git's errors go to a file: two pipes read one after the other could both fill
		Path errors = Files.createTempFile("archsim-git-log", ".txt");
		try {
			Process process = git.redirectError(errors.toFile()).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor() != 0) {
				throw new IOException(
						"git log " + range + " failed: " + Files.readString(errors, StandardCharsets.UTF_8).strip());
			}
			return parse(output);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while git log ran", ex);
		}
		finally {
			Files.deleteIfExists(errors);
		}
	}

	/**
	 * The commits {@code git log --format=%H%x00%B%x00} printed: each one's id and
	 * message, each followed by a NUL.
	 * @param output what git printed
	 * @return each commit
	 */
	static List<Commit> parse(String output) {
		String[] parts = output.split(SEPARATOR, -1);
		List<Commit> commits = new ArrayList<>();
		for (int index = 0; index + 1 < parts.length; index += 2) {
			commits.add(new Commit(parts[index].strip(), parts[index + 1]));
		}
		return commits;
	}

	/**
	 * A commit.
	 *
	 * @param sha its id
	 * @param message its message
	 */
	record Commit(String sha, String message) {

		/**
		 * Its id as a problem names it.
		 * @return the id's first ten characters
		 */
		String shortSha() {
			return this.sha.substring(0, Math.min(SHORT_SHA, this.sha.length()));
		}

	}

}
