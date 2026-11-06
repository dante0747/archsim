package io.archsim.app.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.archsim.app.store.StrictlyIncreasingClock;

/**
 * The clock the application tells time by: UTC, as the database stores it, and never the
 * same microsecond twice, so rows made one after another sort in the order they were made
 * (ADR-0041). Tests give their own.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	@Bean
	Clock clock() {
		return new StrictlyIncreasingClock(Clock.systemUTC());
	}

}
