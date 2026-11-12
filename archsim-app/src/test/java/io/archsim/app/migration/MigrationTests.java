package io.archsim.app.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Flyway builds the schema the Python API's Alembic migrations built at their revision
 * 0008 ({@code parity/schema}), and takes over a database Alembic made only at the
 * revision its baseline reproduces (ADR-0032). The schema is compared at V1, the version
 * that reproduces Alembic's revision 0008, so a later migration does not change the
 * comparison.
 */
class MigrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The version whose schema is Alembic's revision 0008, which the snapshot records:
	 * the one a database Alembic made is taken over at.
	 */
	private static final String SNAPSHOT_VERSION = MigrationConfiguration.BASELINE_VERSION;

	/** Flyway's own migrations. */
	private static final String MIGRATIONS = "classpath:db/migration";

	/** A migration after V1, standing in for a later change to the schema. */
	private static final String LATER_MIGRATION = "classpath:db/after-v1";

	/**
	 * The later migration's version: far above any real one, since Flyway refuses two
	 * migrations of one version across its locations.
	 */
	private static final MigrationVersion LATER_VERSION = MigrationVersion.fromVersion("9999");

	private static final Path SCHEMA = Path.of(System.getProperty("archsim.repoRoot", "..")).resolve("parity/schema");

	@Test
	void migrate_emptyDatabase_buildsTheSchemaAlembicBuilds() {
		DataSource database = freshDatabase();

		flywayToTheSnapshot(database, MIGRATIONS).migrate();

		assertThat(describe(database)).isEqualTo(JSON.readTree(read(SCHEMA.resolve("alembic-0008.json"))));
	}

	@Test
	void migrate_toTheSnapshotWithALaterMigration_stillBuildsTheSchemaAlembicBuilds() {
		DataSource database = freshDatabase();
		Flyway flyway = flywayToTheSnapshot(database, MIGRATIONS, LATER_MIGRATION);

		flyway.migrate();

		assertThat(describe(database)).isEqualTo(JSON.readTree(read(SCHEMA.resolve("alembic-0008.json"))));
		assertThat(laterMigration(flyway).getState()).isEqualTo(MigrationState.ABOVE_TARGET);
	}

	@Test
	void migrate_emptyDatabase_makesTheLocalUserOwnerOfTheDefaultWorkspace() {
		DataSource database = freshDatabase();

		flyway(database).migrate();

		String role = JdbcClient.create(database)
			.sql("SELECT role FROM memberships WHERE workspace_id = 'default' AND user_id = 'local'")
			.query(String.class)
			.single();
		assertThat(role).isEqualTo("owner");
	}

	/**
	 * V1 builds exactly what Alembic builds (the snapshot test above proves it), so
	 * running its script outside Flyway stands in for a database Alembic made.
	 */
	@Test
	void migrate_databaseAlembicLeftAtTheBaselineRevision_isTakenOverUnchanged() {
		DataSource database = freshDatabase();
		runScript(database, "db/migration/V1__schema_at_alembic_0008.sql");
		Flyway flyway = flywayToTheSnapshot(database, MIGRATIONS);

		flyway.migrate();

		assertThat(flyway.info().current().getVersion().getVersion())
			.isEqualTo(MigrationConfiguration.BASELINE_VERSION);
		assertThat(describe(database)).isEqualTo(JSON.readTree(read(SCHEMA.resolve("alembic-0008.json"))));
	}

	@Test
	void migrate_databaseAlembicLeftAtTheBaselineRevision_appliesTheMigrationsAfterIt() {
		DataSource database = freshDatabase();
		runScript(database, "db/migration/V1__schema_at_alembic_0008.sql");
		Flyway flyway = configuration(database).locations(MIGRATIONS, LATER_MIGRATION).load();

		flyway.migrate();

		assertThat(laterMigration(flyway).getState()).isEqualTo(MigrationState.SUCCESS);
	}

	@Test
	void migrate_databaseAlembicLeftAtTheBaselineRevision_keepsItsRows() {
		DataSource database = freshDatabase();
		runScript(database, "db/migration/V1__schema_at_alembic_0008.sql");
		JdbcClient.create(database)
			.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES ('team1', 'Team', 'team', now())")
			.update();

		flyway(database).migrate();

		assertThat(JdbcClient.create(database)
			.sql("SELECT name FROM workspaces WHERE id = 'team1'")
			.query(String.class)
			.single()).isEqualTo("Team");
	}

	@Test
	void migrate_databaseAtAnOlderAlembicRevision_isRefusedBeforeFlywayWritesAnything() {
		DataSource database = freshDatabase();
		JdbcClient.create(database).sql("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)").update();
		JdbcClient.create(database).sql("INSERT INTO alembic_version VALUES ('0007')").update();

		assertThatExceptionOfType(FlywayException.class).isThrownBy(() -> flyway(database).migrate());
		assertThat(hasFlywayHistory(database)).isFalse();
	}

	/**
	 * The Alembic migrations are gone (ADR-0063): the refusal names the tag that keeps
	 * them, where {@code archsim-api migrate} brings the database to the revision Flyway
	 * takes over.
	 */
	@Test
	void migrate_databaseAtAnOlderAlembicRevision_isRefusedWithTheTagToUpgradeFrom() {
		DataSource database = freshDatabase();
		JdbcClient.create(database).sql("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)").update();
		JdbcClient.create(database).sql("INSERT INTO alembic_version VALUES ('0007')").update();

		// Flyway puts "Error while executing beforeBaseline callback: " before the
		// callback's own words
		assertThatExceptionOfType(FlywayException.class).isThrownBy(() -> flyway(database).migrate())
			.withMessageEndingWith("This database is at Alembic revision 0007: bring it to revision 0008 with the "
					+ "Alembic migrations the tag `alembic-0008` keeps (in a checkout of that tag, `uv sync`, then "
					+ "`uv run archsim-api migrate` with ARCHSIM_DATABASE_URL set to this database's "
					+ "postgresql+psycopg:// URL), then migrate again with this version.");
	}

	@Test
	void migrate_tablesWithoutAlembicRevision_isRefused() {
		DataSource database = freshDatabase();
		JdbcClient.create(database).sql("CREATE TABLE something_else (id INTEGER)").update();

		assertThatExceptionOfType(FlywayException.class).isThrownBy(() -> flyway(database).migrate())
			.withMessageContaining("no Alembic revision");
	}

	private static boolean hasFlywayHistory(DataSource database) {
		return JdbcClient.create(database)
			.sql("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")
			.query(Boolean.class)
			.single();
	}

	private static DataSource freshDatabase() {
		return new DriverManagerDataSource(TestDatabases.freshJdbcUrl());
	}

	private static Flyway flyway(DataSource database) {
		return configuration(database).load();
	}

	/**
	 * Flyway as the application configures it, migrating no further than V1, whose schema
	 * the snapshot records.
	 * @param database the database to migrate
	 * @param locations where to look for migrations, {@link #MIGRATIONS} first
	 * @return Flyway that applies the migrations up to V1 and leaves later ones
	 * {@code ABOVE_TARGET}
	 */
	private static Flyway flywayToTheSnapshot(DataSource database, String... locations) {
		return configuration(database).locations(locations).target(SNAPSHOT_VERSION).load();
	}

	/** The later migration, as Flyway reports it. */
	private static MigrationInfo laterMigration(Flyway flyway) {
		return Arrays.stream(flyway.info().all())
			.filter((migration) -> LATER_VERSION.equals(migration.getVersion()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Flyway did not find the later migration"));
	}

	private static FluentConfiguration configuration(DataSource database) {
		FluentConfiguration configuration = Flyway.configure().dataSource(database);
		MigrationConfiguration.takeOverFromAlembic(configuration);
		return configuration;
	}

	private static JsonNode describe(DataSource database) {
		String json = JdbcClient.create(database)
			.sql(read(SCHEMA.resolve("describe.sql")))
			.query(String.class)
			.single();
		return JSON.readTree(json);
	}

	private static void runScript(DataSource database, String resource) {
		new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(database);
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
