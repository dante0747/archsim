package io.archsim.benchmarks.docker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commands run on this machine: a small Java program ({@code probe-program.txt}),
 * launched from its source by this JVM's own {@code java}, stands in for Docker.
 */
class SystemProcessesTests {

	private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

	private static ProcessResult result;

	private static Set<String> leftOver;

	@BeforeAll
	static void runTheProgram(@TempDir Path folder) throws IOException {
		Path source = folder.resolve("Program.java");
		try (InputStream program = SystemProcessesTests.class.getResourceAsStream("probe-program.txt")) {
			Files.write(source, Objects.requireNonNull(program).readAllBytes());
		}
		Set<String> before = scratchFolders();
		result = new SystemProcesses().run(List.of(JAVA, source.toString()), Map.of("ARCHSIM_PROBE", "set"), "");
		leftOver = new HashSet<>(scratchFolders());
		leftOver.removeAll(before);
	}

	@Test
	void run_aCommand_keepsItsExitCode() {
		assertThat(result.exitCode()).isEqualTo(3);
	}

	@Test
	void run_aCommand_keepsItsOutputWithTheVariablesItWasGiven() {
		assertThat(result.output()).isEqualTo("out set");
	}

	@Test
	void run_aCommand_keepsItsErrorsApart() {
		assertThat(result.errors()).isEqualTo("err");
	}

	@Test
	void run_aCommand_leavesNoFilesBehind() {
		assertThat(leftOver).isEmpty();
	}

	// the scripts the harness runs in a container's shell go in this way
	@Test
	void run_aCommandGivenInput_readsIt(@TempDir Path folder) throws IOException {
		Path source = folder.resolve("Program.java");
		try (InputStream program = SystemProcessesTests.class.getResourceAsStream("echo-program.txt")) {
			Files.write(source, Objects.requireNonNull(program).readAllBytes());
		}

		ProcessResult echoed = new SystemProcesses().run(List.of(JAVA, source.toString()), Map.of(),
				"for d in /proc/[0-9]*; do printf \"%s\" \"$d\"; done\n");

		assertThat(echoed.output()).isEqualTo("for d in /proc/[0-9]*; do printf \"%s\" \"$d\"; done\n");
	}

	// the folders the runner keeps a command's input and output in while it runs
	private static Set<String> scratchFolders() throws IOException {
		try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
			return files.map((path) -> path.getFileName().toString())
				.filter((name) -> name.startsWith("archsim-process"))
				.collect(Collectors.toSet());
		}
	}

}
