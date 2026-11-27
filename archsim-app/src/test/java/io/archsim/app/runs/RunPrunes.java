package io.archsim.app.runs;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.TestProperties;

/**
 * The hourly prune of the runs past their project's limit, for the tests that read what
 * it leaves behind, in this package and others.
 */
public final class RunPrunes {

	private RunPrunes() {
	}

	/**
	 * Deletes the runs past their project's limit, as the hourly prune does.
	 * @param jdbc the database
	 * @param kept how many runs a project keeps
	 * @return how many runs it deleted
	 */
	public static int prune(JdbcClient jdbc, int kept) {
		return retention(jdbc, kept).prune(Instant.now());
	}

	/**
	 * The retention of the runs past their project's limit, as a server that keeps the
	 * runs given has it.
	 * @param jdbc the database
	 * @param kept how many runs a project keeps
	 * @return the retention
	 */
	static RunRetention retention(JdbcClient jdbc, int kept) {
		return new RunRetention(new RunRepository(jdbc, JsonMapper.builder().build()), new RunGroupRepository(jdbc),
				TestProperties.with("run-history-limit=" + kept));
	}

}
