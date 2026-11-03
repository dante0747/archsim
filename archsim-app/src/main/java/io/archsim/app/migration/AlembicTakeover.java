package io.archsim.app.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * Lets Flyway take over a database the Python API's Alembic migrations made, only when
 * they left it at the revision Flyway's baseline reproduces (ADR-0032). Flyway records
 * its baseline instead of migrating a database that already has tables; before it does,
 * this checks the Alembic revision, so a database at an older revision is never mistaken
 * for an up-to-date one.
 */
class AlembicTakeover implements Callback {

	/** The Alembic revision {@code V1__schema_at_alembic_0008.sql} reproduces. */
	static final String BASELINE_REVISION = "0008";

	@Override
	public boolean supports(Event event, Context context) {
		return event == Event.BEFORE_BASELINE;
	}

	@Override
	public boolean canHandleInTransaction(Event event, Context context) {
		return true;
	}

	@Override
	public void handle(Event event, Context context) {
		Optional<String> revision = revision(context.getConnection());
		if (revision.isEmpty()) {
			throw new FlywayException("This database has tables but no Alembic revision, so it is not one ArchSim "
					+ "made: migrate an empty database instead.");
		}
		if (!BASELINE_REVISION.equals(revision.get())) {
			throw new FlywayException("This database is at Alembic revision " + revision.get()
					+ ": bring it to revision " + BASELINE_REVISION + " with the Python API's `archsim-api migrate`, "
					+ "then migrate again.");
		}
	}

	@Override
	public String getCallbackName() {
		return "alembic-takeover";
	}

	private static Optional<String> revision(Connection connection) {
		try {
			return hasAlembicTable(connection) ? firstRevision(connection) : Optional.empty();
		}
		catch (SQLException ex) {
			throw new FlywayException("Cannot read this database's Alembic revision", ex);
		}
	}

	private static boolean hasAlembicTable(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection
			.prepareStatement("SELECT to_regclass('public.alembic_version') IS NOT NULL");
				ResultSet row = statement.executeQuery()) {
			return row.next() && row.getBoolean(1);
		}
	}

	private static Optional<String> firstRevision(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT version_num FROM alembic_version");
				ResultSet row = statement.executeQuery()) {
			return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
		}
	}

}
