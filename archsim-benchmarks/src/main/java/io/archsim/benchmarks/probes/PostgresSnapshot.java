package io.archsim.benchmarks.probes;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * PostgreSQL's statistics at one moment, and its processes' CPU so far.
 *
 * @param stats its statement, WAL and database statistics ({@code postgres-stats.sql})
 * @param processes each process's kind and CPU ticks, by pid; null when they could not be
 * read
 */
public record PostgresSnapshot(JsonNode stats, @Nullable Map<String, ProcessTime> processes) {

	/**
	 * Copies the processes, keeping their order.
	 */
	public PostgresSnapshot {
		processes = (processes != null) ? new LinkedHashMap<>(processes) : null;
	}

	/**
	 * A process's kind and its CPU so far.
	 *
	 * @param kind {@code backends}, {@code walWriter} or {@code background}
	 * @param ticks its user and system time, in hundredths of a second
	 */
	public record ProcessTime(String kind, long ticks) {

	}

}
