package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's timers, called off when it ends: a cancel that comes after it ended sets no
 * grace that would later write it cancelled.
 */
class RunTimersTests {

	private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

	private final RunTimers timers = new RunTimers(this.executor);

	@AfterEach
	void stopTheExecutor() {
		this.executor.shutdownNow();
	}

	@Test
	void callOff_setTimers_callsThemOff() {
		this.timers.after(Duration.ofMinutes(1), () -> {
		});

		this.timers.callOff();

		assertThat(this.executor.getQueue()).isNotEmpty().allMatch((timer) -> ((Future<?>) timer).isCancelled());
	}

	@Test
	void after_onceCalledOff_setsNothing() {
		this.timers.callOff();

		this.timers.after(Duration.ofMinutes(1), () -> {
		});

		assertThat(this.executor.getQueue()).isEmpty();
	}

}
