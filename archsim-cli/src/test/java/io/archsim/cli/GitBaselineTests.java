package io.archsim.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.TypeConversionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The baseline {@code check --baseline-ref} reads from git, where the recordings cannot
 * show it: git not installed, which Python refused in its {@code OSError}'s words; a git
 * that takes longer than the deadline, where Python waited; and a ref git could read as
 * an option, which argparse refused.
 */
class GitBaselineTests {

	private static final String NOT_INSTALLED = "archsim-no-such-git";

	/** A stand-in for git that never answers: a Java program that sleeps. */
	private static final String SLEEPING = """
			class Sleeping {
				public static void main(String[] args) throws InterruptedException {
					Thread.sleep(600_000);
				}
			}
			""";

	@Test
	void at_gitNotInstalled_isRefusedInTheWordsOfPythonsOsError(@TempDir Path folder) throws IOException {
		Path design = Files.writeString(folder.resolve("design.json"), "{}");
		String refusal = OS.WINDOWS.isCurrentOs() ? "[WinError 2] The system cannot find the file specified"
				: "[Errno 2] No such file or directory: '" + NOT_INSTALLED + "'";

		assertThatExceptionOfType(GitBaselineException.class)
			.isThrownBy(
					() -> new GitBaseline(List.of(NOT_INSTALLED), GitBaseline.DEADLINE).at("HEAD", design.toString()))
			.withMessage(refusal);
	}

	@ParameterizedTest
	@ValueSource(strings = { "CreateProcess error=2, The system cannot find the file specified",
			"error=2, No such file or directory", "Exec failed, error: 2 (No such file or directory) " })
	void notStarted_programNotFound_isRefusedInTheWordsOfPythonsOsError(String jdkWords) {
		String refusal = OS.WINDOWS.isCurrentOs() ? "[WinError 2] The system cannot find the file specified"
				: "[Errno 2] No such file or directory: 'git'";

		assertThat(GitBaseline.notStarted(startFailure("/tmp/x", jdkWords), "git")).isEqualTo(refusal);
	}

	@ParameterizedTest
	@ValueSource(strings = { "error=13, Permission denied", "Exec failed, error: 13 (Permission denied) " })
	void notStarted_anotherError_isRefusedInTheSystemsWords(String jdkWords) {
		IOException failure = startFailure("/tmp/x", jdkWords);

		assertThat(GitBaseline.notStarted(failure, "git")).isEqualTo(failure.getMessage());
	}

	// the folder, the design's, is the user's to name: only the JDK's words count
	@Test
	void notStarted_aFolderNamedLikeTheError_isNotTakenForIt() {
		IOException failure = startFailure("/tmp/error: 2", "Exec failed, error: 13 (Permission denied) ");

		assertThat(GitBaseline.notStarted(failure, "git")).isEqualTo(failure.getMessage());
	}

	// what ProcessBuilder.start throws when it cannot start git in a folder: its words
	// for the program and the folder, and the JDK's for the error, which it wraps
	private static IOException startFailure(String folder, String jdkWords) {
		return new IOException("Cannot run program \"git\" (in directory \"" + folder + "\"): " + jdkWords,
				new IOException(jdkWords));
	}

	@Test
	void at_gitTakingLongerThanTheDeadline_isStoppedAndRefused(@TempDir Path folder) throws IOException {
		Path design = Files.writeString(folder.resolve("design.json"), "{}");
		Path sleeping = Files.writeString(folder.resolve("Sleeping.java"), SLEEPING);
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		GitBaseline slow = new GitBaseline(List.of(java, sleeping.toString()), Duration.ofSeconds(1));
		long started = System.nanoTime();

		assertThatExceptionOfType(GitBaselineException.class).isThrownBy(() -> slow.at("HEAD", design.toString()))
			.withMessage("git rev-parse took more than 1 s");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
	}

	@ParameterizedTest
	@ValueSource(strings = { "-x", "--output=x", "-" })
	void refs_aRefStartingWithADash_isRefusedBeforeGitCouldReadItAsAnOption(String ref) {
		assertThatExceptionOfType(TypeConversionException.class).isThrownBy(() -> new CheckCommand.Refs().convert(ref))
			.withMessage("a git ref cannot start with '-'");
	}

	@ParameterizedTest
	@ValueSource(strings = { "HEAD", "origin/main", "HEAD~1", "v1.0-rc" })
	void refs_anyOtherRef_isTakenAsGiven(String ref) {
		assertThat(new CheckCommand.Refs().convert(ref)).isEqualTo(ref);
	}

}
