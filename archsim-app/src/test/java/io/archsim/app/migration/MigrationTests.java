package io.archsim.app.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
 * Flyway builds the schema the Python API's Alembic migrations build, and takes over a
 * database Alembic made only at the revision its baseline reproduces (ADR-0032).
 */
class MigrationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Path SCHEMA = Path.of(System.getProperty("archsim.repoRoot", "..")).resolve("parity/schema");

	@Test
	void migrate_emptyDatabase_buildsTheSchemaAlembicBuilds() {
		DataSource database = freshDatabase();

		flyway(database).migrate();

		assertThat(describe(database)).isEqualTo(JSON.readTree(read(SCHEMA.resolve("alembic-0008.json"))));
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

		flyway(database).migrate();

		assertThat(flyway(database).info().current().getVersion().getVersion())
			.isEqualTo(MigrationConfiguration.BASELINE_VERSION);
		assertThat(describe(database)).isEqualTo(JSON.readTree(read(SCHEMA.resolve("alembic-0008.json"))));
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

	@Test
	void migrate_databaseAtAnOlderAlembicRevision_isRefused() {
		DataSource database = freshDatabase();
		JdbcClient.create(database).sql("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)").update();
		JdbcClient.create(database).sql("INSERT INTO alembic_version VALUES ('0007')").update();

		assertThatExceptionOfType(FlywayException.class).isThrownBy(() -> flyway(database).migrate())
			.withMessageContaining("at Alembic revision 0007");
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
		FluentConfiguration configuration = Flyway.configure().dataSource(database);
		MigrationConfiguration.takeOverFromAlembic(configuration);
		return configuration.load();
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
