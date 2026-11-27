package io.archsim.app.runs;

import java.time.Instant;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.database.TestDatabases;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.engine.run.QuickCheck;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration (V4) that stores each run's design once: the copy each run carried moves
 * to one stored design per digest, and a run queued afterwards finds a moved design by
 * the same digest.
 */
class RunDesignsMigrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The last version before the migration. */
	private static final String BEFORE = "2";

	private static final String SHOP = """
			{"metadata": {"name": "Shop"}, "architecture": {"nodes": [{"id": "api"}], "edges": []}}""";

	/**
	 * {@link #SHOP} with its keys in another order and spaced otherwise: the same design.
	 */
	private static final String SHOP_REWRITTEN = """
			{ "architecture" : { "edges" : [ ], "nodes" : [ { "id" : "api" } ] },
			  "metadata" : { "name" : "Shop" } }""";

	private static final String CART = """
			{"metadata": {"name": "Cart"}}""";

	private final DataSource database = databaseBefore();

	private final JdbcClient jdbc = JdbcClient.create(this.database);

	private final RunRepository runs = new RunRepository(this.jdbc, JSON);

	@Test
	void migrate_runsOfOneDesign_storeItOnce() {
		storeRun("r1", SHOP);
		storeRun("r2", SHOP_REWRITTEN);
		storeRun("r3", CART);

		migrate();

		assertThat(storedDesignCount()).isEqualTo(2);
	}

	@Test
	void migrate_eachRun_keepsItsDesign() {
		storeRun("r1", SHOP);
		storeRun("r2", SHOP_REWRITTEN);
		storeRun("r3", CART);

		migrate();

		assertThat(this.runs.inputDocument("r1")).isEqualTo(JSON.readTree(SHOP));
		assertThat(this.runs.inputDocument("r2")).isEqualTo(JSON.readTree(SHOP));
		assertThat(this.runs.inputDocument("r3")).isEqualTo(JSON.readTree(CART));
	}

	@Test
	void migrate_runs_keepNoCopyOfTheirDesign() {
		storeRun("r1", SHOP);

		migrate();

		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_name = 'simulation_runs' AND column_name = 'input_document'""")
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void add_runOfADesignTheMigrationMoved_doesNotStoreItAgain() {
		storeRun("r1", SHOP);
		migrate();

		this.runs.add(queued("r2", (ObjectNode) JSON.readTree(SHOP_REWRITTEN)));

		assertThat(storedDesignCount()).isOne();
	}

	@Test
	void delete_onlyRunOfADesignTheMigrationMoved_deletesTheDesign() {
		storeRun("r1", SHOP);
		storeRun("r2", CART);
		migrate();

		this.runs.delete("r1");

		assertThat(storedDesignCount()).isOne();
	}

	private static DataSource databaseBefore() {
		DataSource database = new DriverManagerDataSource(TestDatabases.freshJdbcUrl());
		Flyway.configure().dataSource(database).target(BEFORE).load().migrate();
		JdbcClient jdbc = JdbcClient.create(database);
		jdbc.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES ('w1', 'Team', 'team', now())").update();
		jdbc.sql("""
				INSERT INTO projects (id, workspace_id, name, document, schema_version, revision, created_at,
				                      updated_at)
				VALUES ('p1', 'w1', 'Shop', '{}', '1.7', 1, now(), now())""").update();
		return database;
	}

	private void migrate() {
		Flyway.configure().dataSource(this.database).load().migrate();
	}

	/**
	 * A run as the API stored one before the migration, with a copy of its design.
	 * @param runId the run
	 * @param design its design, as JSON
	 */
	private void storeRun(String runId, String design) {
		this.jdbc.sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, label, status, progress, seed,
				                             engine_version, input_document, created_at)
				VALUES (:id, 'p1', 'w1', 'Baseline', 'succeeded', 1, 7, '0.16.0', CAST(:design AS jsonb), now())""")
			.param("id", runId)
			.param("design", design)
			.update();
	}

	private int storedDesignCount() {
		return this.jdbc.sql("SELECT count(*) FROM run_designs").query(Integer.class).single();
	}

	private static QueuedRun queued(String runId, ObjectNode design) {
		NewRun run = new NewRun(new Destination("p1", "w1", new WorkspaceLimits.RunLimits(1000, 20.0)),
				new PlannedRun(null, null, 7, design, new QuickCheck(true, "simulates 30 s", 60.0, null, null)),
				"Baseline", RunMode.FULL);
		return new QueuedRun(runId, run, "local", null, Instant.parse("2026-11-27T09:00:00Z"));
	}

}
