package io.archsim.app.support;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.awaitility.Awaitility.await;

/**
 * Workspaces that store as much as a test needs: a project's design padded with text
 * PostgreSQL cannot compress, so it stores every byte of it, also while a write waits for
 * the workspace.
 */
public final class FullWorkspaces {

	/** A sandbox's quota of stored bytes, which it keeps without the hosted limits. */
	public static final long SANDBOX_QUOTA = 10_000_000;

	/** What a sandbox that stores its quota answers a write that would store more. */
	public static final String FULL_SANDBOX = "This workspace stores as much as it may (10 MB): "
			+ "delete runs, projects or share links first.";

	/** The characters of one block of padding, an MD5 in hex. */
	private static final int BLOCK = 32;

	/** Longer than a request takes to reach a lock it waits for. */
	private static final Duration A_WHILE = Duration.ofSeconds(30);

	private FullWorkspaces() {
	}

	/**
	 * Makes a request while another transaction holds a sandbox, as a write does, and
	 * fills the sandbox once the request waits for it, before that transaction lets go: a
	 * request that checks the quota with the sandbox locked finds it full.
	 * @param <T> what the request answers
	 * @param jdbc the test database, which sees the request wait
	 * @param other connections of the other transaction's own
	 * @param projectId a project of the sandbox, whose design has {@code metadata}
	 * @param request the request
	 * @return its answer
	 */
	public static <T> T fillWhileWaiting(JdbcClient jdbc, DataSource other, String projectId, Supplier<T> request) {
		TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(other));
		CompletableFuture<T> answered = transaction
			.execute((status) -> fillOnceWaiting(jdbc, JdbcClient.create(other), projectId, request));
		return Objects.requireNonNull(answered).join();
	}

	/**
	 * Pads a project's design until it alone stores a sandbox's whole quota.
	 * @param jdbc the test database
	 * @param projectId the project, whose design has {@code metadata}
	 */
	public static void fillSandbox(JdbcClient jdbc, String projectId) {
		pad(jdbc, projectId, SANDBOX_QUOTA);
	}

	/**
	 * Pads a project's design until it alone stores at least the bytes given.
	 * @param jdbc the test database
	 * @param projectId the project, whose design has {@code metadata}
	 * @param bytes how many bytes it should store
	 */
	public static void pad(JdbcClient jdbc, String projectId, long bytes) {
		jdbc.sql("UPDATE projects SET document = jsonb_set(document, '{metadata,padding}', to_jsonb(%s)) WHERE id = :id"
			.formatted(incompressible(bytes))).param("id", projectId).update();
	}

	/**
	 * Takes the padding out of a project's design again.
	 * @param jdbc the test database
	 * @param projectId the project
	 */
	public static void removePadding(JdbcClient jdbc, String projectId) {
		jdbc.sql("UPDATE projects SET document = document #- '{metadata,padding}' WHERE id = :id")
			.param("id", projectId)
			.update();
	}

	/**
	 * Text PostgreSQL cannot compress, as SQL: random MD5s in hex.
	 * @param bytes how many bytes of it, at least
	 * @return an SQL expression whose value is the text
	 */
	public static String incompressible(long bytes) {
		return "(SELECT string_agg(md5(random()::text), '') FROM generate_series(1, %d))".formatted(bytes / BLOCK + 1);
	}

	/**
	 * Holds a project's workspace, makes a request, and fills the workspace once the
	 * request waits for it.
	 * @param <T> what the request answers
	 * @param jdbc the test database, which sees the request wait
	 * @param holder the connection that holds the workspace, in its transaction
	 * @param projectId the project
	 * @param request the request
	 * @return the request, made
	 */
	private static <T> CompletableFuture<T> fillOnceWaiting(JdbcClient jdbc, JdbcClient holder, String projectId,
			Supplier<T> request) {
		holder
			.sql("SELECT w.id FROM workspaces w JOIN projects p ON p.workspace_id = w.id "
					+ "WHERE p.id = :id FOR NO KEY UPDATE OF w")
			.param("id", projectId)
			.query(String.class)
			.single();
		int holding = holder.sql("SELECT pg_backend_pid()").query(Integer.class).single();
		CompletableFuture<T> made = CompletableFuture.supplyAsync(request);
		await().atMost(A_WHILE).until(() -> made.isDone() || waitsFor(jdbc, holding));
		fillSandbox(holder, projectId);
		return made;
	}

	/**
	 * Whether a connection waits for a lock another one holds.
	 * @param jdbc the test database
	 * @param holder the backend that holds it
	 * @return {@code true} while one waits
	 */
	private static boolean waitsFor(JdbcClient jdbc, int holder) {
		return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :holder = ANY(pg_blocking_pids(pid))")
			.param("holder", holder)
			.query(Integer.class)
			.single() > 0;
	}

}
