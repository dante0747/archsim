import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Drops and creates a PostgreSQL database for the end-to-end tests. api-server.mjs runs it as a
 * single-file program with the PostgreSQL driver from the API's own jar, so the tests need no
 * other database client:
 *
 * <pre>
 * PGUSER=archsim PGPASSWORD=archsim java -cp postgresql.jar FreshDatabase.java \
 *     jdbc:postgresql://127.0.0.1:5432/archsim archsim_e2e_8002
 * </pre>
 *
 * The first argument is a database to connect to as a user who may create databases (from
 * {@code PGUSER} and {@code PGPASSWORD}, kept out of the command line); the second, the name of
 * the database to make afresh, lower-case letters, digits and underscores only.
 */
final class FreshDatabase {

	private static final String DATABASE_NAME = "[a-z0-9_]+";

	private FreshDatabase() {
	}

	static void main(String[] args) throws SQLException {
		if (args.length != 2 || !args[1].matches(DATABASE_NAME)) {
			throw new IllegalArgumentException("usage: FreshDatabase <jdbc-url> <database: " + DATABASE_NAME + ">");
		}
		String adminUrl = args[0];
		// A database's name cannot be a bind parameter. Its pattern admits no quote, so quoting it is
		// safe, and keeps a reserved word or a leading digit a name, as psycopg's sql.Identifier did.
		String identifier = '"' + args[1] + '"';
		String user = System.getenv("PGUSER");
		String password = System.getenv("PGPASSWORD");
		try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + identifier + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + identifier);
		}
	}

}
