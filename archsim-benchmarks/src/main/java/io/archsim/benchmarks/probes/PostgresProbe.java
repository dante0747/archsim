package io.archsim.benchmarks.probes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.ProcessResult;
import io.archsim.engine.text.PythonJsonReader;

/**
 * What PostgreSQL did during a calibration pass: its statistics and its processes' CPU
 * read before and after the pass, through {@code docker compose exec}.
 */
public final class PostgresProbe {

	private static final String STATS_SQL = sql();

	/** How much of a refusal the log keeps, from its end. */
	private static final int REASON = 300;

	private final Compose compose;

	private final HarnessLog log;

	/**
	 * The probe of a stack's PostgreSQL.
	 * @param compose the stack's compose
	 * @param log the log
	 */
	public PostgresProbe(Compose compose, HarnessLog log) {
		this.compose = compose;
		this.log = log;
	}

	/**
	 * Before a pass: clears {@code pg_stat_statements}, so that its means cover the pass
	 * alone, and reads the counters the pass is measured against.
	 * @return the snapshot; empty when the stack's PostgreSQL has no
	 * {@code pg_stat_statements} (stacks started before {@code compose.yaml} loaded it)
	 */
	public Optional<PostgresSnapshot> begin() {
		this.compose.result("exec", "-T", "postgres", "psql", "-U", "bench", "-d", "bench", "-qAt", "-c",
				"SELECT pg_stat_statements_reset()");
		return snapshot();
	}

	/**
	 * After a pass: what PostgreSQL did during it.
	 * @param before the snapshot {@link #begin} read
	 * @return what it did ({@link PostgresWindow}); empty when the snapshot after it
	 * cannot be read
	 */
	public Optional<ObjectNode> end(PostgresSnapshot before) {
		return snapshot().map((after) -> PostgresWindow.of(before, after));
	}

	private Optional<PostgresSnapshot> snapshot() {
		ProcessResult stats = this.compose.result("exec", "-T", "postgres", "psql", "-U", "bench", "-d", "bench", "-At",
				"-c", STATS_SQL);
		if (!stats.succeeded() || stats.output().isBlank()) {
			String reason = (stats.errors().isEmpty() ? stats.output() : stats.errors()).strip();
			this.log.line("no PostgreSQL statistics: " + reason.substring(Math.max(0, reason.length() - REASON)));
			return Optional.empty();
		}
		ProcessResult processes = this.compose.withInput(PostgresProcesses.SCRIPT, "exec", "-T", "postgres", "sh",
				"-s");
		return Optional.of(new PostgresSnapshot(PythonJsonReader.read(stats.output()),
				processes.succeeded() ? PostgresProcesses.of(processes.output()) : null));
	}

	private static String sql() {
		try (InputStream in = PostgresProbe.class.getResourceAsStream("postgres-stats.sql")) {
			if (in == null) {
				throw new IllegalStateException("missing resource postgres-stats.sql");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
