package io.archsim.tools.commits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code commit-msg} hook ({@code .githooks/commit-msg}) as git runs it: by
 * {@code sh}, from the repository's root, with {@code JAVA_HOME} as the developer set it.
 * On Windows that often ends in a backslash. Skipped where there is no {@code sh}.
 */
class CommitMsgHookTests {

	private static final Path ROOT = Path.of(System.getProperty("archsim.repoRoot", "..")).toAbsolutePath().normalize();

	private static final int TIMEOUT_SECONDS = 60;

	@TempDir
	private Path folder;

	@Test
	void run_javaHomeOfJava21EndingInABackslash_refusesNamingItsFolder() throws IOException, InterruptedException {
		Path javaHome = Files.createDirectory(this.folder.resolve("jdk-21"));
		Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"21.0.5\"\n");

		HookRun run = run(javaHome + "\\", "feat: add a thing\n");

		assertThat(run.exitCode()).isOne();
		assertThat(run.output().strip())
			.isEqualTo("commit-msg: the commit message check needs Java 22 or later; the Java in JAVA_HOME, %s, is 21"
				.formatted(javaHome));
	}

	@Test
	void run_javaHomeEndingInABackslash_acceptsAConventionalMessage() throws IOException, InterruptedException {
		HookRun run = run(System.getProperty("java.home") + "\\", "feat: add a thing\n");

		assertThat(run.exitCode()).as(run.output()).isZero();
	}

	private HookRun run(String javaHome, String message) throws IOException, InterruptedException {
		Path file = Files.writeString(this.folder.resolve("COMMIT_EDITMSG"), message);
		Path output = this.folder.resolve("output");
		ProcessBuilder hook = new ProcessBuilder("sh", ROOT.resolve(".githooks/commit-msg").toString(), file.toString())
			.directory(ROOT.toFile())
			.redirectErrorStream(true)
			.redirectOutput(output.toFile());
		hook.environment().put("JAVA_HOME", javaHome);
		Process process = start(hook);
		boolean ended = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		String printed = Files.readString(output, StandardCharsets.UTF_8);
		if (!ended) {
			// the java the hook starts too, which would keep the output open
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			throw new IllegalStateException("the hook ran past " + TIMEOUT_SECONDS + " s:\n" + printed);
		}
		return new HookRun(process.exitValue(), printed);
	}

	private static Process start(ProcessBuilder hook) throws IOException {
		try {
			return hook.start();
		}
		catch (IOException ex) {
			assumeTrue(false, "needs sh, which git runs the hook with: " + ex.getMessage());
			throw ex;
		}
	}

	private record HookRun(int exitCode, String output) {
	}

}
