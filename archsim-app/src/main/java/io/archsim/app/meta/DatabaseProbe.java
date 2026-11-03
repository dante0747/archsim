package io.archsim.app.meta;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Asks the database whether it answers.
 */
@Repository
class DatabaseProbe {

	private final JdbcClient jdbc;

	DatabaseProbe(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Whether the database answers a trivial query.
	 * @return {@code true} when it does
	 */
	boolean answers() {
		try {
			return this.jdbc.sql("SELECT 1").query(Integer.class).single() == 1;
		}
		catch (DataAccessException ex) {
			return false;
		}
	}

}
