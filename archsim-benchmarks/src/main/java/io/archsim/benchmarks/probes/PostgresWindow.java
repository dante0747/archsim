package io.archsim.benchmarks.probes;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;

/**
 * What PostgreSQL did between two snapshots: the benchmark's statements as
 * {@code pg_stat_statements} timed them since it was cleared (the executor's time,
 * without planning, the commit or its WAL flush), and the WAL, the transactions and each
 * kind of process's CPU counted from the first. Counters that backends flush once a
 * second can lag by up to a second at either end, alike at both ends of a steady pass.
 */
public final class PostgresWindow {

	private static final int DECIMALS = 4;

	/**
	 * Linux's USER_HZ: {@code /proc/<pid>/stat} counts CPU time in hundredths of a
	 * second.
	 */
	private static final double CLOCK_TICKS = 100;

	private static final double MILLISECONDS = 1000;

	private PostgresWindow() {
	}

	/**
	 * What PostgreSQL did between two snapshots.
	 * @param before the first
	 * @param after the second
	 * @return {@code statements}, {@code wal}, {@code database}, and
	 * {@code cpuMsByProcess} when both snapshots read the processes
	 */
	public static ObjectNode of(PostgresSnapshot before, PostgresSnapshot after) {
		ObjectNode window = JsonNodeFactory.instance.objectNode();
		ArrayNode statements = window.putArray("statements");
		after.stats().get("statements").forEach((statement) -> {
			Optional<String> kind = statementKind(statement.get("query").stringValue());
			if (statement.get("calls").asDouble() > 0 && kind.isPresent()) {
				ObjectNode kept = statements.addObject().put("kind", kind.get());
				kept.setAll((ObjectNode) statement);
			}
		});
		window.set("wal", difference(before.stats().get("wal"), after.stats().get("wal")));
		window.set("database", difference(before.stats().get("database"), after.stats().get("database")));
		if (before.processes() != null && after.processes() != null) {
			window.set("cpuMsByProcess", cpu(before.processes(), after.processes()));
		}
		return window;
	}

	/**
	 * What a benchmark statement does, whatever the service's driver made of its text.
	 * @param query the statement's text
	 * @return {@code read} by key, {@code write} by key, or the consumer's
	 * {@code insert}; empty for anything else ({@code BEGIN}, a pool's checks)
	 */
	public static Optional<String> statementKind(String query) {
		String text = String.join(" ", query.toLowerCase(Locale.ROOT).strip().split("\\s+", -1));
		if (text.startsWith("select") && text.contains(" from items")) {
			return Optional.of("read");
		}
		if (text.startsWith("update items")) {
			return Optional.of("write");
		}
		return text.startsWith("insert into events") ? Optional.of("insert") : Optional.empty();
	}

	// each counter of the second less the first's, to 4 decimals
	private static ObjectNode difference(JsonNode before, JsonNode after) {
		ObjectNode counted = JsonNodeFactory.instance.objectNode();
		after.properties()
			.forEach((counter) -> counted.set(counter.getKey(), PythonValues
				.round(PythonValues.subtract(counter.getValue(), before.get(counter.getKey())), DECIMALS)));
		return counted;
	}

	// each kind's CPU between the snapshots, in milliseconds, by kind in order
	private static ObjectNode cpu(Map<String, PostgresSnapshot.ProcessTime> before,
			Map<String, PostgresSnapshot.ProcessTime> after) {
		Map<String, Double> byKind = new TreeMap<>();
		after.forEach((pid, process) -> {
			PostgresSnapshot.ProcessTime earlier = before.get(pid);
			long ticks = process.ticks() - ((earlier != null) ? earlier.ticks() : 0);
			byKind.merge(process.kind(), ticks * MILLISECONDS / CLOCK_TICKS, Double::sum);
		});
		ObjectNode cpu = JsonNodeFactory.instance.objectNode();
		byKind.forEach((kind, milliseconds) -> cpu.put(kind, PythonRound.round(milliseconds, 1)));
		return cpu;
	}

}
