package io.archsim.app.worker;

import java.time.Duration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The worker's timings, read from the Python worker's {@code ARCHSIM_*} environment
 * variables ({@code ARCHSIM_LEASE_SECONDS} is {@code leaseSeconds}), with its defaults
 * and limits. How many runs it takes at once is {@code ARCHSIM_MAX_CONCURRENT_RUNS},
 * which the API reports too.
 *
 * @param leaseSeconds how long a run stays the worker's without a renewal: a run whose
 * worker stops renewing it is requeued, or failed after its second attempt
 * @param heartbeatSeconds how often the worker renews its leases and its
 * {@code job_workers} row, at most half the lease
 * @param reapSeconds how often the worker looks for leases that ran out
 * @param cancelGraceSeconds how long a cancelled run may take to stop
 */
@ConfigurationProperties("archsim")
@Validated
record WorkerProperties(@DefaultValue("30") @DecimalMin("1") @DecimalMax("3600") double leaseSeconds,
		@DefaultValue("2") @Positive @DecimalMax("60") double heartbeatSeconds,
		@DefaultValue("10") @Positive @DecimalMax("600") double reapSeconds,
		@DefaultValue("10") @PositiveOrZero @DecimalMax("600") double cancelGraceSeconds) {

	private static final double NANOS_PER_SECOND = 1e9;

	WorkerProperties {
		if (heartbeatSeconds * 2 > leaseSeconds) {
			throw new IllegalArgumentException(
					"ARCHSIM_HEARTBEAT_SECONDS must be at most half of ARCHSIM_LEASE_SECONDS");
		}
	}

	/**
	 * How long a run stays the worker's without a renewal.
	 * @return the lease's length
	 */
	Duration lease() {
		return seconds(this.leaseSeconds);
	}

	/**
	 * How long a cancelled run may take to stop before it is written cancelled.
	 * @return the grace
	 */
	Duration cancelGrace() {
		return seconds(this.cancelGraceSeconds);
	}

	/**
	 * How often the worker looks for leases that ran out.
	 * @return the time between two reaps
	 */
	Duration reapPeriod() {
		return seconds(this.reapSeconds);
	}

	/**
	 * How often the worker beats.
	 * @return the time between two beats
	 */
	Duration heartbeatPeriod() {
		return seconds(this.heartbeatSeconds);
	}

	private static Duration seconds(double seconds) {
		return Duration.ofNanos(Math.round(seconds * NANOS_PER_SECOND));
	}

}
