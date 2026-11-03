package io.archsim.app.limits;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.support.HostedTestSupport;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Counters every API instance shares, in the {@code rate_limits} table: a request over
 * its limit is refused with how long to wait, and costs nothing. The clock stands at
 * noon, so no test's window ends while it runs.
 */
class RateLimitsTests extends HostedTestSupport {

	private static final RateLimit TWO_A_DAY = new RateLimit(2, RateLimit.DAY);

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);

	@Autowired
	private RateLimitRepository repository;

	@Autowired
	private ArchsimProperties properties;

	@Autowired
	private PlatformTransactionManager transactions;

	private RateLimits limits;

	@BeforeEach
	void atNoon() {
		this.limits = new RateLimits(this.repository, this.properties, NOON);
	}

	@Test
	void enforce_withinTheLimit_letsItThrough() {
		String key = key();

		this.limits.enforce(key, TWO_A_DAY, "Too many.");
		this.limits.enforce(key, TWO_A_DAY, "Too many.");

		assertThat(count(key)).isEqualTo(2);
	}

	@Test
	void enforce_overTheLimit_isTooManyRequestsWithWhenToTryAgain() {
		String key = key();
		this.limits.enforce(key, TWO_A_DAY, "Too many.");
		this.limits.enforce(key, TWO_A_DAY, "Too many.");

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.limits.enforce(key, TWO_A_DAY, "Too many."))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
				assertThat(ex.headers().getFirst("Retry-After")).matches("\\d+");
				assertThat(ex.getMessage()).matches("Too many\\. Try again in \\d+ (s|min|h)\\.");
			});
	}

	@Test
	void enforce_refused_costsNothing() {
		String key = key();
		this.limits.enforce(key, TWO_A_DAY, "Too many.");
		this.limits.enforce(key, TWO_A_DAY, "Too many.");

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.limits.enforce(key, TWO_A_DAY, "Too many."));

		assertThat(count(key)).isEqualTo(2);
	}

	@Test
	void enforce_inATransactionThatRollsBack_staysCounted() {
		String key = key();
		TransactionTemplate failing = new TransactionTemplate(this.transactions);

		failing.executeWithoutResult((status) -> {
			this.limits.enforce(key, TWO_A_DAY, "Too many.");
			status.setRollbackOnly();
		});

		assertThat(count(key)).isEqualTo(1);
	}

	@Test
	void enforce_hourlyLimit_saysWhenTheHourEnds() {
		RateLimit oncePerHour = new RateLimit(1, RateLimit.HOUR);
		String key = key();
		this.limits.enforce(key, oncePerHour, "Too many.");

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.limits.enforce(key, oncePerHour, "Too many."))
			.satisfies((ex) -> assertThat(ex.headers().getFirst("Retry-After")).isEqualTo("3600"))
			.withMessage("Too many. Try again in 60 min.");
	}

	@Test
	void waitText_underNinetySeconds_isSeconds() {
		assertThat(RateLimits.waitText(89)).isEqualTo("89 s");
	}

	@Test
	void waitText_underNinetyMinutes_isMinutesRoundedUp() {
		assertThat(RateLimits.waitText(91)).isEqualTo("2 min");
	}

	@Test
	void waitText_longer_isHoursRoundedUp() {
		assertThat(RateLimits.waitText(5400)).isEqualTo("2 h");
	}

	private int count(String key) {
		return this.jdbc()
			.sql("SELECT sum(count) FROM rate_limits WHERE key = :key")
			.param("key", key)
			.query(Integer.class)
			.single();
	}

	private static String key() {
		return "test:" + UUID.randomUUID();
	}

}
