package io.archsim.app.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Workspaces that store as much as a test needs: a project's design padded with text
 * PostgreSQL cannot compress, so it stores every byte of it.
 */
public final class FullWorkspaces {

	/** A sandbox's quota of stored bytes, which it keeps without the hosted limits. */
	public static final long SANDBOX_QUOTA = 10_000_000;

	/** What a sandbox that stores its quota answers a write that would store more. */
	public static final String FULL_SANDBOX = "This workspace stores as much as it may (10 MB): "
			+ "delete runs, projects or share links first.";

	/** The characters of one block of padding, an MD5 in hex. */
	private static final int BLOCK = 32;

	private FullWorkspaces() {
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

}
