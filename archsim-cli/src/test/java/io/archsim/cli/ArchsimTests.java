package io.archsim.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code archsim} command itself: what it says it is. The benchmark harness reads the
 * engine's version from {@code --version} and names its checks on the Java engine by it.
 */
class ArchsimTests {

	@Test
	void execute_versionOption_printsTheEngineItSimulatesWith() {
		StringWriter out = new StringWriter();

		int exitCode = Archsim.commandLine().setOut(new PrintWriter(out)).execute("--version");

		assertThat(List.of(exitCode, out.toString().strip())).containsExactly(0, "archsim, engine 1.0.0");
	}

}
