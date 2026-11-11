package io.archsim.app.migration;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;

import io.archsim.app.Role;

/**
 * The schema a role that never migrates needs (the worker, the seed): the database's
 * Flyway history must end at the latest migration its build carries, so that the role
 * never meets a column it does not know, or misses one it needs: a run halfway through on
 * the worker (ADR-0053), a project on the seed.
 */
final class SchemaVersion {

	/** What to do about a database behind the role: the API migrates it. */
	private static final String START_THE_API = "start the API first";

	private SchemaVersion() {
	}

	/**
	 * Refuses a database at another schema than the build's, saying which version it is
	 * at, which the role needs, and what to start.
	 * @param flyway the database's Flyway, configured as the application configures it
	 * @param role the role that needs it
	 * @throws IllegalStateException when the database is at another version
	 */
	static void requireCurrent(Flyway flyway, Role role) {
		MigrationInfoService info = flyway.info();
		MigrationVersion needed = latest(info);
		MigrationInfo current = info.current();
		if (current == null) {
			throw new IllegalStateException("the database has no schema history yet, this %s needs V%s; %s"
				.formatted(role.profile(), needed.getVersion(), START_THE_API));
		}
		int order = current.getVersion().compareTo(needed);
		if (order != 0) {
			throw new IllegalStateException("the database is at V%s, this %s needs V%s; %s".formatted(
					current.getVersion().getVersion(), role.profile(), needed.getVersion(), remedy(order, role)));
		}
	}

	// what to start: the API for a database behind the role; for one ahead of it, which a
	// later API migrated, the role of that API's release
	private static String remedy(int order, Role role) {
		return (order < 0) ? START_THE_API : "start a " + role.profile() + " of the API's release";
	}

	// the highest version among the migrations this build carries
	private static MigrationVersion latest(MigrationInfoService info) {
		return Arrays.stream(info.all())
			.filter((migration) -> migration.getState().isResolved())
			.map(MigrationInfo::getVersion)
			.filter(Objects::nonNull)
			.max(Comparator.naturalOrder())
			.orElse(MigrationVersion.EMPTY);
	}

}
