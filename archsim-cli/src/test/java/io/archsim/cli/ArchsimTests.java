package io.archsim.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code archsim} command itself: what it says it is, and how it answers a command
 * that cannot go on. The benchmark harness reads the engine's version from
 * {@code --version} and names its checks on the Java engine by it.
 */
class ArchsimTests {

	@TempDir
	private Path folder;

	@Test
	void execute_versionOption_printsTheEngineItSimulatesWith() {
		StringWriter out = new StringWriter();

		int exitCode = Archsim.commandLine().setOut(new PrintWriter(out)).execute("--version");

		assertThat(List.of(exitCode, out.toString().strip())).containsExactly(0, "archsim, engine 1.0.0");
	}

	@Test
	void execute_aCommandRefused_printsWhyWithoutAStackTrace() {
		Path missing = this.folder.resolve("missing.json");
		StringWriter out = new StringWriter();
		StringWriter err = new StringWriter();

		int exitCode = Archsim.commandLine()
			.setOut(new PrintWriter(out))
			.setErr(new PrintWriter(err))
			.execute("validate", missing.toString());

		assertThat(List.of(exitCode, out.toString(), err.toString().strip())).containsExactly(1, "",
				"Cannot read %s: there is no such file or folder".formatted(missing));
	}

}
