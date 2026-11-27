package io.archsim.app.migration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import io.archsim.app.Role;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * A role that never migrates starts only on a database at the schema its build carries,
 * and otherwise says which version the database is at, which it needs and what to do. A
 * migration after V1 ({@code db/after-v1}, V9999) stands in for a later build's.
 */
class SchemaVersionTests {

	/** Flyway's own migrations. */
	private static final String MIGRATIONS = "classpath:db/migration";

	/** The migrations of a later build: this build's, then V9999. */
	private static final String[] LATER_BUILD = { MIGRATIONS, "classpath:db/after-v1" };

	@Test
	void requireCurrent_databaseAtTheBuildsVersion_passes() {
		DataSource database = freshDatabase();
		flyway(database, MIGRATIONS).migrate();

		assertThatNoException()
			.isThrownBy(() -> SchemaVersion.requireCurrent(flyway(database, MIGRATIONS), Role.WORKER));
	}

	@Test
	void requireCurrent_databaseTakenOverFromAlembic_passes() {
		DataSource database = freshDatabase();
		new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__schema_at_alembic_0008.sql"))
			.execute(database);
		flyway(database, MIGRATIONS).migrate();

		assertThatNoException()
			.isThrownBy(() -> SchemaVersion.requireCurrent(flyway(database, MIGRATIONS), Role.WORKER));
	}

	@Test
	void requireCurrent_databaseBehindTheBuild_isRefusedNamingBothVersions() {
		DataSource database = freshDatabase();
		flyway(database, MIGRATIONS).migrate();

		assertThatIllegalStateException()
			.isThrownBy(() -> SchemaVersion.requireCurrent(flyway(database, LATER_BUILD), Role.WORKER))
			.withMessage("the database is at V3, this worker needs V9999; start the API first");
	}

	@Test
	void requireCurrent_databaseAheadOfTheBuild_isRefusedNamingBothVersions() {
		DataSource database = freshDatabase();
		flyway(database, LATER_BUILD).migrate();

		assertThatIllegalStateException()
			.isThrownBy(() -> SchemaVersion.requireCurrent(flyway(database, MIGRATIONS), Role.WORKER))
			.withMessage("the database is at V9999, this worker needs V3; start a worker of the API's release");
	}

	@Test
	void requireCurrent_theSeedOnADatabaseAheadOfTheBuild_isRefusedNamingTheSeed() {
		DataSource database = freshDatabase();
		flyway(database, LATER_BUILD).migrate();

		assertThatIllegalStateException()
			.isThrownBy(() -> SchemaVersion.requireCurrent(flyway(database, MIGRATIONS), Role.SEED))
			.withMessage("the database is at V9999, this seed needs V3; start a seed of the API's release");
	}

	@Test
	void requireCurrent_emptyDatabase_isRefused() {
		DataSource database = freshDatabase();

		assertThatIllegalStateException()
			.isThrownBy(() -> SchemaVersion.requireCurrent(flyway(database, MIGRATIONS), Role.WORKER))
			.withMessage("the database has no schema history yet, this worker needs V3; start the API first");
	}

	private static DataSource freshDatabase() {
		return new DriverManagerDataSource(TestDatabases.freshJdbcUrl());
	}

	// Flyway as the application configures it, with these migrations
	private static Flyway flyway(DataSource database, String... locations) {
		FluentConfiguration configuration = Flyway.configure().dataSource(database).locations(locations);
		MigrationConfiguration.takeOverFromAlembic(configuration);
		return configuration.load();
	}

}
