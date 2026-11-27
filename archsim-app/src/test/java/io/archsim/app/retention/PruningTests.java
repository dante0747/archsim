package io.archsim.app.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import io.archsim.app.ArchsimApplication;
import io.archsim.app.Role;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * The scheduled pruning: every retention, one after another, at one time; the API owns
 * the rows, so no other role prunes.
 */
class PruningTests {

	private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void prune_retentions_prunesEachAsOfOneTime() {
		Retention sessions = mock();
		Retention runs = mock();

		new Pruning(List.of(sessions, runs), CLOCK).prune();

		then(sessions).should().prune(NOW);
		then(runs).should().prune(NOW);
	}

	@Test
	void prune_aRetentionTheDatabaseFails_prunesTheOthers() {
		Retention failing = mock();
		given(failing.prune(NOW)).willThrow(new CannotGetJdbcConnectionException("down"));
		Retention next = mock();

		new Pruning(List.of(failing, next), CLOCK).prune();

		then(next).should().prune(NOW);
	}

	@Test
	void prune_aRetentionTheDatabaseFails_isNotThrown() {
		Retention failing = mock();
		given(failing.prune(NOW)).willThrow(new CannotGetJdbcConnectionException("down"));

		assertThatNoException().isThrownBy(new Pruning(List.of(failing), CLOCK)::prune);
	}

	@Test
	void start_theApiRole_prunes() {
		try (ConfigurableApplicationContext api = start(Role.API)) {
			assertThat(api.getBeansOfType(Pruning.class)).hasSize(1);
		}
	}

	@Test
	void start_theWorkerRole_hasNoPruning() {
		try (ConfigurableApplicationContext worker = start(Role.WORKER)) {
			assertThat(worker.getBeansOfType(Pruning.class)).isEmpty();
		}
	}

	private static ConfigurableApplicationContext start(Role role) {
		return new SpringApplicationBuilder(ArchsimApplication.class).web(role.webApplicationType())
			.profiles(role.profile())
			.run("--archsim.database-url=" + TestDatabases.migratedJdbcUrl(), "--server.port=0");
	}

}
