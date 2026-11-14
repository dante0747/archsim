package io.archsim.tools.commits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check run as the {@code commit-msg} hook runs it: from its source by Java's source
 * launcher, with nothing on the class path but the JDK.
 */
class SourceLaunchTests {

	private static final Path SOURCE = Path.of("src/main/java/io/archsim/tools/commits/CheckCommits.java");

	@Test
	void launch_aConventionalMessage_exitsWith0(@TempDir Path folder) throws IOException, InterruptedException {
		Path message = Files.writeString(folder.resolve("COMMIT_EDITMSG"), "feat: add a thing\n");

		assertThat(launch(message)).isZero();
	}

	@Test
	void launch_anotherMessage_exitsWith1(@TempDir Path folder) throws IOException, InterruptedException {
		Path message = Files.writeString(folder.resolve("COMMIT_EDITMSG"), "Add a thing\n");

		assertThat(launch(message)).isOne();
	}

	private static int launch(Path message) throws IOException, InterruptedException {
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		Process process = new ProcessBuilder(java.toString(), SOURCE.toString(), "--file", message.toString())
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.start();
		return process.waitFor();
	}

}
