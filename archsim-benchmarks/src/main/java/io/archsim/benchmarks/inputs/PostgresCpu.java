package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * What PostgreSQL's CPU and its own statistics say about a pass: its CPU per query, the
 * CPU share of a query in the model, a statement's executor time and the WAL flushes.
 */
final class PostgresCpu {

	private static final String CONTAINER = "postgres";

	private static final double LEAST_SHARE = 0.02;

	private static final int DECIMALS = 4;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private PostgresCpu() {
	}

	/**
	 * PostgreSQL's CPU per query in a pass, net of its CPU at rest (protocol v5).
	 * @param passes the calibration's passes
	 * @param entry the pass
	 * @param queries the pass's queries
	 * @return the CPU (ms); {@code null} without an idle pass, a reading or queries
	 */
	static @Nullable Double perQueryNetOfIdle(JsonNode passes, JsonNode entry, double queries) {
		JsonNode idle = PythonValues.get(passes, "idle");
		if (PythonValues.isNone(idle)) {
			return null;
		}
		Double net = PassReadings.netCpuMs(entry, idle, CONTAINER);
		return (net != null && queries > 0 && net > 0) ? net / queries : null;
	}

	/**
	 * PostgreSQL's CPU per query in a pass: net of its CPU at rest (protocol v5), else
	 * per extra query over a lower pass (protocols v2 to v4). A pass's own CPU over its
	 * queries would count PostgreSQL's CPU at rest too.
	 * @param passes the calibration's passes
	 * @param high the pass and its queries
	 * @param low the lower pass and its queries
	 * @return the CPU (ms); {@code null} without either figure
	 */
	static @Nullable Double perQuery(JsonNode passes, Queries high, Queries low) {
		if (!PythonValues.isNone(PythonValues.get(passes, "idle"))) {
			return perQueryNetOfIdle(passes, high.entry(), high.count());
		}
		Double per = PassReadings.slope(cpu(low.entry()), cpu(high.entry()), low.count(), high.count());
		return (per != null && per > 0) ? per : null;
	}

	/**
	 * PostgreSQL's CPU per query over the mean time the model gives a query: at the top
	 * pass net of idle (protocol v5), else per extra query between the two passes. The
	 * model spends the share times each query's sampled time on a core, so the share is
	 * taken against the mean of the times it samples (writes without their WAL flush,
	 * which uses no CPU in the model), not against the mean the service measured: engine
	 * 0.14.0 did, which counted the flush and the measured tail and left PostgreSQL's
	 * busy cores 16% to 69% low.
	 * @param passes the calibration's passes
	 * @param queries the queries of the low and the top pass
	 * @param model the model's mean time per query (ms) and how it was found
	 * @return the share and how it was found; empty without a usable figure
	 */
	static Optional<ObjectNode> share(JsonNode passes, QueryCounts queries, ModelTime model) {
		JsonNode idle = PythonValues.get(passes, "idle");
		Double cpu;
		String what;
		if (!PythonValues.isNone(idle)) {
			Double net = PassReadings.netCpuMs(passes.get("high"), idle, CONTAINER);
			cpu = (net != null && queries.high() > 0) ? net / queries.high() : null;
			what = "PostgreSQL CPU per query at the top pass net of idle (" + fourDecimals(cpu) + " ms)";
		}
		else {
			cpu = PassReadings.slope(cpu(passes.get("low")), cpu(passes.get("high")), queries.low(), queries.high());
			what = "PostgreSQL CPU per extra query between the passes (" + fourDecimals(cpu) + " ms)";
		}
		if (cpu == null || cpu <= 0 || model.ms() <= 0) {
			return Optional.empty();
		}
		double share = Math.min(1.0, Math.max(LEAST_SHARE, cpu / model.ms()));
		return Optional.of(NODES.objectNode()
			.put("value", PythonRound.round(share, DECIMALS))
			.put("how", what + " / the model's mean time per query (" + model.how() + ")"));
	}

	/**
	 * A pass's statement of one kind as pg_stat_statements timed it, all its forms
	 * together.
	 * @param entry the pass
	 * @param kind the statement's kind: {@code read}, {@code write} or {@code insert}
	 * @return its calls-weighted mean executor time (ms); {@code null} when the pass
	 * recorded none
	 */
	static @Nullable Double statementMs(JsonNode entry, String kind) {
		List<JsonNode> calls = new ArrayList<>();
		List<JsonNode> times = new ArrayList<>();
		for (JsonNode statement : statements(entry)) {
			if (kind.equals(statement.path("kind").stringValue(""))) {
				calls.add(statement.get("calls"));
				times.add(PythonValues.multiply(statement.get("meanExecMs"), statement.get("calls")));
			}
		}
		double count = PythonValues.sum(calls).doubleValue();
		return (count == 0) ? null : PythonValues.sum(times).doubleValue() / count;
	}

	/**
	 * A pass's WAL flushes: their number, each one's time (writing the WAL and syncing
	 * it, which run one after another under the WAL write lock) and the commits that
	 * shared one.
	 * @param entry the pass
	 * @return the flushes; empty without a flush or the statistics (track_wal_io_timing
	 * off)
	 */
	static Optional<WalFlushes> flushes(JsonNode entry) {
		JsonNode postgres = entry.path(CONTAINER);
		JsonNode wal = postgres.path("wal");
		if (!PythonText.truthy(wal.path("syncs")) || !PythonText.truthy(wal.path("syncMs"))) {
			return Optional.empty();
		}
		double commits = 0;
		for (JsonNode statement : statements(entry)) {
			String kind = statement.path("kind").stringValue("");
			if ("write".equals(kind) || "insert".equals(kind)) {
				commits += statement.get("calls").doubleValue();
			}
		}
		JsonNode syncs = wal.get("syncs");
		double count = syncs.doubleValue();
		return Optional
			.of(new WalFlushes(syncs, (wal.get("writeMs").doubleValue() + wal.get("syncMs").doubleValue()) / count,
					wal.get("syncMs").doubleValue() / count, commits / count));
	}

	private static List<JsonNode> statements(JsonNode entry) {
		return entry.path(CONTAINER).path("statements").valueStream().toList();
	}

	private static JsonNode cpu(JsonNode entry) {
		return entry.path("containerCpuMs").path(CONTAINER);
	}

	// Python's f"{pg or 0:.4f}"
	private static String fourDecimals(@Nullable Double cpu) {
		return PythonFormat.format((cpu != null) ? cpu : 0.0, ".4f");
	}

	/**
	 * A pass and its queries.
	 *
	 * @param entry the pass
	 * @param count its queries
	 */
	record Queries(JsonNode entry, double count) {

	}

	/**
	 * The queries of the low and the top calibration pass.
	 *
	 * @param low the low pass's
	 * @param high the top pass's
	 */
	record QueryCounts(double low, double high) {

	}

	/**
	 * The model's mean time per query and how it was found.
	 *
	 * @param ms the mean time (ms)
	 * @param how how it was found
	 */
	record ModelTime(double ms, String how) {

	}

	/**
	 * A pass's WAL flushes.
	 *
	 * @param syncs the flushes, as the pass recorded them
	 * @param flushMs each one's time, writing and syncing (ms)
	 * @param syncMs each one's sync (ms)
	 * @param commitsPerFlush the commits that shared one
	 */
	record WalFlushes(JsonNode syncs, double flushMs, double syncMs, double commitsPerFlush) {

	}

}
