package io.archsim.app.migration;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.mock.env.MockEnvironment;

import io.archsim.app.Role;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.CorsOrigins;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * When a started role migrates the database: the {@code api} role unless
 * {@code ARCHSIM_AUTO_MIGRATE=false}, the {@code migrate} role always.
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

	private static FlywayMigrationStrategy strategy(Role role, boolean autoMigrate) {
		ArchsimProperties properties = new ArchsimProperties("postgresql://h/db", null, null,
				new CorsOrigins(List.of()), autoMigrate, null, 300, 1, 10_000, null);
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(role.profile());
		return new MigrationConfiguration().migrationStrategy(properties, environment);
	}

}
