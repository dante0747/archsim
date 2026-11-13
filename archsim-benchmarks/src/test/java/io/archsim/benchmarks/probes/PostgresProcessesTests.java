package io.archsim.benchmarks.probes;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL's processes by their titles, and each one's CPU so far from {@code /proc}.
 */
class PostgresProcessesTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			postgres: bench bench 172.18.0.4(43122) idle | backends
			postgres: walwriter                          | walWriter
			postgres: checkpointer                       | background
			postgres -c shared_buffers=256MB             | background
			""")
	void kind_aPostgresProcess_isItsKind(String title, String kind) {
		assertThat(PostgresProcesses.kind(title)).contains(kind);
	}

	// the probe's own backend, its shell and its cat
	@ParameterizedTest
	@ValueSource(strings = { "postgres: bench bench [local] SELECT", "sh -c for d in /proc" })
	void kind_theProbesOwnProcess_isNone(String title) {
		assertThat(PostgresProcesses.kind(title)).isEmpty();
	}

	// utime and stime are /proc/<pid>/stat's fields 14 and 15, after the title in
	// brackets
	@Test
	void of_eachProcessTitleAndStat_isItsKindAndTicks() {
		String listed = """
				postgres: walwriter \t52 (postgres) S 1 1 1 0 -1 4194624 92 0 0 0 7 5 0 0 20 0 1 0 245
				sh -c for d in /proc \t97 (sh) R 0 97 97 0 -1 4194560 140 0 0 0 0 0 0 0 20 0 1 0 900
				""";

		assertThat(PostgresProcesses.of(listed))
			.containsExactly(Map.entry("52", new PostgresSnapshot.ProcessTime("walWriter", 12)));
	}

}
