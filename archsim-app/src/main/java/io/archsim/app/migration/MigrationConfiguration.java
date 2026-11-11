package io.archsim.app.migration;

import java.util.List;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.archsim.app.Role;
import io.archsim.app.config.ArchsimProperties;

/**
 * How the database is migrated: Flyway takes over a database the Python API's Alembic
 * made, at its revision 0008 (ADR-0032), and migrates always in the {@code migrate} role
 * and when the {@code api} role starts, unless {@code ARCHSIM_AUTO_MIGRATE=false} (a
 * {@code migrate} service did it first). The {@code worker} and {@code seed} roles never
 * migrate: they start only on a database at their build's schema (ADR-0053).
 */
@Configuration(proxyBeanMethods = false)
class MigrationConfiguration {

	/** The version Flyway gives a database the Python API's Alembic made. */
	static final String BASELINE_VERSION = "1";

	/** The roles that start only on a database at their build's schema. */
	private static final List<Role> NEVER_MIGRATING = List.of(Role.WORKER, Role.SEED);

	@Bean
	FlywayConfigurationCustomizer alembicTakeover() {
		return MigrationConfiguration::takeOverFromAlembic;
	}

	@Bean
	FlywayMigrationStrategy migrationStrategy(ArchsimProperties properties, Environment environment) {
		for (Role role : NEVER_MIGRATING) {
			if (environment.matchesProfiles(role.profile())) {
				return (flyway) -> SchemaVersion.requireCurrent(flyway, role);
			}
		}
		boolean migrate = properties.autoMigrate() || environment.matchesProfiles(Role.MIGRATE.profile());
		return (flyway) -> {
			if (migrate) {
				flyway.migrate();
			}
		};
	}

	/**
	 * Lets Flyway take over a database Alembic made: one with tables but no Flyway
	 * history is recorded at the baseline, once its Alembic revision is the one the
	 * baseline reproduces.
	 * @param configuration the Flyway configuration, changed in place
	 */
	static void takeOverFromAlembic(FluentConfiguration configuration) {
		configuration.baselineOnMigrate(true)
			.baselineVersion(BASELINE_VERSION)
			.baselineDescription("Alembic revision " + AlembicTakeover.BASELINE_REVISION)
			.callbacks(new AlembicTakeover());
	}

}
