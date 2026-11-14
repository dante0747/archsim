package io.archsim.tools.changes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The files a change touched, through {@code git diff}.
 */
final class GitDiff {

	private GitDiff() {
	}

	/**
	 * The files changed from where the head and the base meet to the head, with both the
	 * old and the new path of a rename.
	 * @param base the base: a pull request's base, or a push's {@code before}
	 * @param head the head
	 * @param directory the repository, or the empty path for the working folder
	 * @return the files; empty when the base is missing, all zeros (a new branch) or not
	 * in the clone (rewritten history)
	 */
	static Optional<List<String>> changedFiles(String base, String head, Path directory) {
		if (base.replace("0", "").isEmpty()) {
			return Optional.empty();
		}
		ProcessBuilder git = new ProcessBuilder("git", "diff", "--name-only", "--no-renames", "-z",
				base + "..." + head);
		if (!directory.toString().isEmpty()) {
			git.directory(directory.toFile());
		}
		try {
			Process process = git.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor() != 0) {
				return Optional.empty();
			}
			return Optional.of(Arrays.stream(output.split("\u0000")).filter((path) -> !path.isEmpty()).toList());
		}
		catch (IOException ex) {
			// git would not start: every job runs, as without a base, where ci_changes.py
			// failed the changes job and so skipped every job that waits on it
			return Optional.empty();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

}
