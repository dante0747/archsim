package io.archsim.app.migration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import io.archsim.app.Role;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.TestProperties;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * When a started role migrates the database: the {@code api} role unless
 * {@code ARCHSIM_AUTO_MIGRATE=false}, the {@code migrate} role always, the {@code worker}
 * and {@code seed} roles never.
 */
class MigrationStrategyTests {

	private final Flyway flyway = mock();

	@Test
	void migrationStrategy_apiRoleWithAutoMigrate_migrates() {
		strategy(Role.API, true).migrate(this.flyway);

		then(this.flyway).should().migrate();
	}

	@Test
	void migrationStrategy_apiRoleWithoutAutoMigrate_leavesTheDatabase() {
		strategy(Role.API, false).migrate(this.flyway);

		then(this.flyway).shouldHaveNoInteractions();
	}

	@Test
	void migrationStrategy_migrateRoleWithoutAutoMigrate_migrates() {
		strategy(Role.MIGRATE, false).migrate(this.flyway);

		then(this.flyway).should().migrate();
	}

	@Test
	void migrationStrategy_workerRoleWithAutoMigrate_refusesAnEmptyDatabaseAndLeavesItEmpty() {
		DataSource database = new DriverManagerDataSource(TestDatabases.freshJdbcUrl());
		Flyway onTheDatabase = Flyway.configure().dataSource(database).load();

		assertThatIllegalStateException().isThrownBy(() -> strategy(Role.WORKER, true).migrate(onTheDatabase))
			.withMessageEndingWith("start the API first");
		assertThat(JdbcClient.create(database)
			.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void migrationStrategy_seedRoleWithAutoMigrate_refusesAnEmptyDatabaseNamingTheSeed() {
		DataSource database = new DriverManagerDataSource(TestDatabases.freshJdbcUrl());
		Flyway onTheDatabase = Flyway.configure().dataSource(database).load();

		assertThatIllegalStateException().isThrownBy(() -> strategy(Role.SEED, true).migrate(onTheDatabase))
			.withMessageContaining("this seed needs")
			.withMessageEndingWith("start the API first");
	}

	private static FlywayMigrationStrategy strategy(Role role, boolean autoMigrate) {
		ArchsimProperties properties = TestProperties.with("auto-migrate=" + autoMigrate);
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(role.profile());
		return new MigrationConfiguration().migrationStrategy(properties, environment);
	}

}
