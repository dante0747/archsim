package io.archsim.app.limits;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.limits.RateLimits.Charge;
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

	/** The limits as the application wires them, with their transaction rule. */
	@Autowired
	private RateLimits proxiedLimits;

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
	void enforceAll_withinEveryLimit_countsEachKey() {
		String person = key();
		String workspace = key();
		List<Charge> charges = List.of(new Charge(person, TWO_A_DAY, "You."), new Charge(workspace, TWO_A_DAY, "Lab."));

		this.limits.enforceAll(charges, 2);

		assertThat(count(person)).isEqualTo(2);
		assertThat(count(workspace)).isEqualTo(2);
	}

	@Test
	void enforceAll_secondKeyOverItsLimit_countsNeither() {
		String person = key();
		String workspace = key();
		this.limits.enforceAll(List.of(new Charge(workspace, TWO_A_DAY, "Lab.")), 2);
		List<Charge> charges = List.of(new Charge(person, TWO_A_DAY, "You."), new Charge(workspace, TWO_A_DAY, "Lab."));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.limits.enforceAll(charges, 1));

		assertThat(count(person)).isZero();
		assertThat(count(workspace)).isEqualTo(2);
	}

	@Test
	void enforceAll_secondKeyOverItsLimit_saysWhenItsWindowEnds() {
		String person = key();
		String workspace = key();
		this.limits.enforceAll(List.of(new Charge(workspace, TWO_A_DAY, "Lab.")), 2);
		List<Charge> charges = List.of(new Charge(person, new RateLimit(2, RateLimit.HOUR), "You."),
				new Charge(workspace, TWO_A_DAY, "Lab."));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.limits.enforceAll(charges, 1))
			.satisfies((ex) -> assertThat(ex.headers().getFirst("Retry-After")).isEqualTo("43200"))
			.withMessage("Lab. Try again in 12 h.");
	}

	@Test
	void enforceAll_moreHitsThanTheLimit_isRefusedAndCostsNothing() {
		String key = key();
		List<Charge> charges = List.of(new Charge(key, TWO_A_DAY, "Too many."));

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> this.limits.enforceAll(charges, 3));

		assertThat(count(key)).isZero();
	}

	@Test
	void enforce_insideATransaction_isRefused() {
		String key = key();
		TransactionTemplate transaction = new TransactionTemplate(this.transactions);

		assertThatExceptionOfType(IllegalTransactionStateException.class).isThrownBy(() -> transaction
			.executeWithoutResult((status) -> this.proxiedLimits.enforce(key, TWO_A_DAY, "Too many.")));
		assertThat(count(key)).isZero();
	}

	@Test
	void enforceAll_insideATransaction_isRefused() {
		String key = key();
		TransactionTemplate transaction = new TransactionTemplate(this.transactions);
		List<Charge> charges = List.of(new Charge(key, TWO_A_DAY, "Too many."));

		assertThatExceptionOfType(IllegalTransactionStateException.class)
			.isThrownBy(() -> transaction.executeWithoutResult((status) -> this.proxiedLimits.enforceAll(charges, 1)));
		assertThat(count(key)).isZero();
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
	void pruneEnded_countersOfEndedAndCurrentWindows_deletesTheEndedOnes() {
		String ended = key();
		String current = key();
		RateLimits twoDaysAgo = new RateLimits(this.repository, this.properties,
				Clock.offset(NOON, Duration.ofDays(-2)));
		twoDaysAgo.enforce(ended, TWO_A_DAY, "Too many.");
		this.limits.enforce(current, TWO_A_DAY, "Too many.");

		this.limits.pruneEnded();

		assertThat(count(ended)).isZero();
		assertThat(count(current)).isEqualTo(1);
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
			.sql("SELECT coalesce(sum(count), 0) FROM rate_limits WHERE key = :key")
			.param("key", key)
			.query(Integer.class)
			.single();
	}

	private static String key() {
		return "test:" + UUID.randomUUID();
	}

}
