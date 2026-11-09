package io.archsim.app.migration;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;

/**
 * The schema a worker needs: the database's Flyway history must end at the latest
 * migration its build carries, so that a run never meets a column the worker does not
 * know, or misses one it needs, halfway through (ADR-0053).
 */
final class SchemaVersion {

	/** What to do about a database behind the worker: the API migrates it. */
	private static final String START_THE_API = "start the API first";

	/** What to do about a database ahead of the worker: a later API migrated it. */
	private static final String MATCH_THE_API = "start a worker of the API's release";

	private SchemaVersion() {
	}

	/**
	 * Refuses a database at another schema than the build's, saying which version it is
	 * at, which the worker needs, and what to start.
	 * @param flyway the database's Flyway, configured as the application configures it
	 * @throws IllegalStateException when the database is at another version
	 */
	static void requireCurrent(Flyway flyway) {
		MigrationInfoService info = flyway.info();
		MigrationVersion needed = latest(info);
		MigrationInfo current = info.current();
		if (current == null) {
			throw new IllegalStateException("the database has no schema history yet, this worker needs V%s; %s"
				.formatted(needed.getVersion(), START_THE_API));
		}
		int order = current.getVersion().compareTo(needed);
		if (order != 0) {
			throw new IllegalStateException(
					"the database is at V%s, this worker needs V%s; %s".formatted(current.getVersion().getVersion(),
							needed.getVersion(), (order < 0) ? START_THE_API : MATCH_THE_API));
		}
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
