package io.archsim.app.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Designs stored for the runs a test writes to the database itself, as the API stores a
 * run's design: once, under the digest of its JSON as PostgreSQL writes it out.
 */
public final class StoredDesigns {

	private StoredDesigns() {
	}

	/**
	 * Stores a design, unless it is stored already.
	 * @param jdbc the database
	 * @param design the design, as JSON
	 * @return its digest, for a run's {@code design_digest}
	 */
	public static String store(JdbcClient jdbc, String design) {
		return jdbc.sql("""
				INSERT INTO run_designs (digest, document)
				SELECT encode(sha256(convert_to(document::text, 'UTF8')), 'hex'), document
				FROM (SELECT CAST(:design AS jsonb) AS document) AS given
				ON CONFLICT (digest) DO UPDATE SET digest = EXCLUDED.digest
				RETURNING digest""").param("design", design).query(String.class).single();
	}

	/**
	 * Stores a project's saved design, as a run of it stores it.
	 * @param jdbc the database
	 * @param projectId the project
	 * @return the design's digest, for a run's {@code design_digest}
	 */
	public static String storeProjectsDesign(JdbcClient jdbc, String projectId) {
		String design = jdbc.sql("SELECT document::text FROM projects WHERE id = :id")
			.param("id", projectId)
			.query(String.class)
			.single();
		return store(jdbc, design);
	}

}
