package io.archsim.app.limits;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.ArchsimApplication;
import io.archsim.app.Role;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The rate-limit counters of windows that ended go while the API runs; the API owns them,
 * so no other role prunes.
 */
class RateLimitPruningTests {

	private static final Duration A_WHILE = Duration.ofSeconds(30);

	@Test
	void start_theApiRole_deletesTheCountersOfEndedWindows() {
		String database = TestDatabases.migratedJdbcUrl();
		JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(database));
		count(jdbc, "ended", Instant.now().minus(1, ChronoUnit.HOURS));
		count(jdbc, "current", Instant.now().plus(1, ChronoUnit.HOURS));

		try (ConfigurableApplicationContext api = start(Role.API, database)) {
			assertThat(api.getBeansOfType(RateLimitPruning.class)).hasSize(1);
			await().atMost(A_WHILE).untilAsserted(() -> assertThat(keys(jdbc)).isEqualTo("current"));
		}
	}

	@Test
	void start_theWorkerRole_hasNoPruner() {
		try (ConfigurableApplicationContext worker = start(Role.WORKER, TestDatabases.migratedJdbcUrl())) {
			assertThat(worker.getBeansOfType(RateLimitPruning.class)).isEmpty();
		}
	}

	@Test
	void prune_aDatabaseThatFails_isNotThrown() {
		RateLimits limits = mock();
		given(limits.pruneEnded()).willThrow(new CannotGetJdbcConnectionException("down"));

		assertThatNoException().isThrownBy(new RateLimitPruning(limits)::prune);
	}

	private static ConfigurableApplicationContext start(Role role, String database) {
		return new SpringApplicationBuilder(ArchsimApplication.class).web(role.webApplicationType())
			.profiles(role.profile())
			.run("--archsim.database-url=" + database, "--server.port=0");
	}

	private static void count(JdbcClient jdbc, String key, Instant expires) {
		jdbc.sql("INSERT INTO rate_limits (key, window_start, count, expires_at) VALUES (:key, 0, 1, :expires)")
			.param("key", key)
			.param("expires", Timestamp.from(expires))
			.update();
	}

	private static String keys(JdbcClient jdbc) {
		return jdbc.sql("SELECT coalesce(string_agg(key, ',' ORDER BY key), '') FROM rate_limits")
			.query(String.class)
			.single();
	}

}
