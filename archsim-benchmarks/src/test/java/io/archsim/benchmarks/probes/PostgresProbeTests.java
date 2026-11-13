package io.archsim.benchmarks.probes;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL's statistics and processes read through compose: the processes' listing as a
 * script the container's shell reads, its quotes intact.
 */
class PostgresProbeTests {

	private static final Path FILE = Path.of("benchmarks", "compose.yaml");

	@Test
	void begin_aStackWithStatistics_listsTheProcessesThroughTheShellsInput() {
		FakeDocker docker = new FakeDocker(
				(command) -> FakeDocker.printing(command.contains(" psql ") ? "{\"statements\": []}" : ""));

		new PostgresProbe(Compose.of(docker, FILE, Map.of(), new Logs().log()), new Logs().log()).begin();

		assertThat(docker.commands()).last().asString().endsWith(" exec -T postgres sh -s");
		assertThat(docker.inputs()).last().isEqualTo(PostgresProcesses.SCRIPT);
	}

	@Test
	void begin_aStackWithoutStatistics_saysSo() {
		Logs logs = new Logs();

		new PostgresProbe(Compose.of(FakeDocker.silent(), FILE, Map.of(), logs.log()), logs.log()).begin();

		assertThat(logs.text().lines()).containsExactly("[12:34:56] no PostgreSQL statistics: ");
	}

}
