package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * What PostgreSQL's own statistics say about a pass: a statement's executor time and the
 * WAL flushes the pass's commits shared.
 */
class PostgresCpuTests {

	/**
	 * 9,000 UPDATEs at 0.05 ms in the executor; 2,000 flushes, 200 ms writing and 1 s
	 * syncing.
	 */
	private static final JsonNode PASS = JsonText.read("""
			{"postgres": {
			  "statements": [{"query": "UPDATE items SET value = value + $1 WHERE id = $2", "kind": "write",
			                  "calls": 9000, "rows": 9000, "meanExecMs": 0.05}],
			  "wal": {"syncs": 2000, "writeMs": 200.0, "syncMs": 1000.0}}}""");

	@Test
	void statementMs_aKindThePassRan_isItsMeanExecutorTime() {
		assertThat(PostgresCpu.statementMs(PASS, "write")).isEqualTo(0.05);
	}

	@Test
	void statementMs_aKindThePassDidNotRun_isNone() {
		assertThat(PostgresCpu.statementMs(PASS, "read")).isNull();
	}

	// writing the WAL and syncing it run one after the other under the WAL write lock
	@Test
	void flushes_aPassWithWalStatistics_isEachFlushsTimeAndItsCommits() {
		PostgresCpu.WalFlushes flushes = PostgresCpu.flushes(PASS).orElseThrow();

		assertThat(flushes.flushMs()).isCloseTo(0.6, within(1e-12));
		assertThat(flushes.syncMs()).isCloseTo(0.5, within(1e-12));
		assertThat(flushes.commitsPerFlush()).isCloseTo(4.5, within(1e-12));
	}

	@Test
	void flushes_aPassWithoutStatistics_isEmpty() {
		assertThat(PostgresCpu.flushes(JsonText.read("{}"))).isEmpty();
	}

}
