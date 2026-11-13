package io.archsim.benchmarks.probes;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What PostgreSQL did during a calibration pass, from its statistics and its processes'
 * CPU before and after it.
 */
class PostgresWindowTests {

	private static final PostgresSnapshot BEFORE = snapshot(0, 0.0, 100, 500);

	private static final PostgresSnapshot AFTER = snapshot(9000, 0.05, 2100, 800);

	// pg_stat_statements was cleared before the pass: its rows are the pass's own
	@Test
	void of_aPass_keepsItsBenchmarkStatementsWithTheirKind() {
		ObjectNode window = PostgresWindow.of(BEFORE, AFTER);

		assertThat(window.get("statements")).isEqualTo(JsonText.read("""
				[{"kind": "write", "query": "UPDATE items SET value = value + $1 WHERE id = $2", "calls": 9000,
				  "rows": 9000, "meanExecMs": 0.05, "stddevExecMs": 0.01, "minExecMs": 0.01, "maxExecMs": 1.0}]"""));
	}

	@Test
	void of_aPass_countsItsWalFromBefore() {
		ObjectNode window = PostgresWindow.of(BEFORE, AFTER);

		assertThat(window.get("wal")).isEqualTo(JsonText.read("""
				{"records": 20000, "bytes": 2000000, "buffersFull": 0, "writes": 2000, "syncs": 2000, "writeMs": 200.0,
				 "syncMs": 1000.0}"""));
	}

	@Test
	void of_aPass_countsItsCommitsFromBefore() {
		ObjectNode window = PostgresWindow.of(BEFORE, AFTER);

		assertThat(window.at("/database/commits").intValue()).isEqualTo(9000);
	}

	@Test
	void of_aPass_takesEachKindOfProcesssCpu() {
		ObjectNode window = PostgresWindow.of(BEFORE, AFTER);

		assertThat(window.get("cpuMsByProcess"))
			.isEqualTo(JsonText.read("{\"backends\": 3000.0, \"walWriter\": 300.0}"));
	}

	@Test
	void of_noProcessesRead_hasNoCpu() {
		PostgresSnapshot unread = new PostgresSnapshot(AFTER.stats(), null);

		ObjectNode window = PostgresWindow.of(BEFORE, unread);

		assertThat(window.has("cpuMsByProcess")).isFalse();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			SELECT value FROM items WHERE id = $1                      | read
			select value from items where id=$1                        | read
			UPDATE items SET value = value + $1 WHERE id = $2          | write
			INSERT INTO events (item_id, produced_at) VALUES ($1, $2)  | insert
			""")
	void statementKind_aBenchmarkStatement_isWhatItDoes(String query, String kind) {
		assertThat(PostgresWindow.statementKind(query)).contains(kind);
	}

	@Test
	void statementKind_anotherStatement_isNone() {
		assertThat(PostgresWindow.statementKind("BEGIN")).isEmpty();
	}

	// what pg_snapshot reads: the statistics and the processes' CPU ticks
	private static PostgresSnapshot snapshot(int calls, double execMs, int syncs, int ticks) {
		ObjectNode stats = JsonText.object("""
				{"statements": [
				  {"query": "UPDATE items SET value = value + $1 WHERE id = $2", "calls": %d, "rows": %d,
				   "meanExecMs": %s, "stddevExecMs": 0.01, "minExecMs": 0.01, "maxExecMs": 1.0},
				  {"query": "BEGIN", "calls": %d, "rows": 0, "meanExecMs": 0.001, "stddevExecMs": 0.0,
				   "minExecMs": 0.0, "maxExecMs": 0.1}],
				 "wal": {"records": %d, "bytes": %d, "buffersFull": 0, "writes": %d, "syncs": %d, "writeMs": %s,
				   "syncMs": %s},
				 "database": {"commits": %d, "rollbacks": 0, "activeMs": %s, "sessionMs": 1000.0}}""".formatted(calls,
				calls, execMs, calls, 10 * syncs, 1000 * syncs, syncs, syncs, 0.1 * syncs, 0.5 * syncs, calls,
				0.3 * calls));
		Map<String, PostgresSnapshot.ProcessTime> processes = new LinkedHashMap<>();
		processes.put("40", new PostgresSnapshot.ProcessTime("backends", ticks));
		processes.put("41", new PostgresSnapshot.ProcessTime("walWriter", ticks / 10));
		return new PostgresSnapshot(stats, processes);
	}

}
