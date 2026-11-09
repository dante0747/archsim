package io.archsim.app.worker;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.TestProperties;
import io.archsim.app.limits.WorkspaceLimits;

/**
 * The worker's side of the queue over a test's database, built as the worker role builds
 * it, with a lease of 30 s.
 */
final class QueueParts {

	/** A lease of 30 s. */
	static final WorkerProperties SETTINGS = new WorkerProperties(30.0, 2.0, 10.0, 10.0);

	static final JsonMapper JSON = JsonMapper.builder().build();

	private QueueParts() {
	}

	/**
	 * Claims, under a server's settings.
	 * @param queue the test's queue
	 * @param settings a setting under {@code archsim.}, such as {@code rate-limits=true}
	 * @return the claims
	 */
	static RunClaims claims(QueueFixture queue, String settings) {
		return new RunClaims(new ClaimRepository(queue.jdbc(), JSON),
				new WorkspaceLimits(TestProperties.with(settings)),
				new TransactionTemplate(new JdbcTransactionManager(queue.database())), SETTINGS);
	}

	/**
	 * The leases.
	 * @param queue the test's queue
	 * @return the leases
	 */
	static LeaseRepository leases(QueueFixture queue) {
		return new LeaseRepository(queue.jdbc(), JSON);
	}

}
