package io.archsim.engine.compare;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.RunSummary;

/** The run figures a comparison puts side by side, in the order it lists them. */
enum Kpi {

	/** The requests offered per second. */
	OFFERED_RPS("offeredRps", "Offered load", Unit.REQUESTS_PER_SECOND, Better.NEUTRAL),

	/** The requests that succeeded per second. */
	THROUGHPUT_RPS("throughputRps", "Throughput (successful)", Unit.REQUESTS_PER_SECOND, Better.HIGHER),

	/** The share of requests that succeeded. */
	SUCCESS_RATE("successRate", "Success rate", Unit.RATIO, Better.HIGHER),

	/** The share of requests that failed. */
	ERROR_RATE("errorRate", "Error rate", Unit.RATIO, Better.LOWER),

	/** The share of requests whose client gave up waiting. */
	TIMEOUT_RATE("timeoutRate", "Timeout rate", Unit.RATIO, Better.LOWER),

	/** The median latency. */
	LATENCY_P50("latencyMs.p50", "Latency p50", Unit.MILLISECONDS, Better.LOWER),

	/** The 95th percentile of latency. */
	LATENCY_P95("latencyMs.p95", "Latency p95", Unit.MILLISECONDS, Better.LOWER),

	/** The 99th percentile of latency. */
	LATENCY_P99("latencyMs.p99", "Latency p99", Unit.MILLISECONDS, Better.LOWER),

	/** The mean latency. */
	LATENCY_MEAN("latencyMs.mean", "Latency mean", Unit.MILLISECONDS, Better.LOWER),

	/** The attempts per request, retries included. */
	RETRY_AMPLIFICATION("retryAmplification", "Attempts per request", Unit.TIMES, Better.LOWER);

	private final String key;

	private final String label;

	private final Unit unit;

	private final Better better;

	Kpi(String key, String label, Unit unit, Better better) {
		this.key = key;
		this.label = label;
		this.unit = unit;
		this.better = better;
	}

	/**
	 * The figure in a run, as the report's summary names it.
	 * @return such as {@code latencyMs.p95}
	 */
	String key() {
		return this.key;
	}

	/**
	 * What a comparison calls the figure.
	 * @return such as {@code Latency p95}
	 */
	String label() {
		return this.label;
	}

	/**
	 * The figure's unit.
	 * @return the unit
	 */
	Unit unit() {
		return this.unit;
	}

	/**
	 * Which way the figure is better.
	 * @return the way
	 */
	Better better() {
		return this.better;
	}

	/**
	 * The figure in a run.
	 * @param summary the run's summary
	 * @return the figure; {@code null} when the run did not measure it
	 */
	@Nullable Double of(RunSummary summary) {
		return switch (this) {
			case OFFERED_RPS -> summary.offeredRps();
			case THROUGHPUT_RPS -> summary.throughputRps();
			case SUCCESS_RATE -> summary.successRate();
			case ERROR_RATE -> summary.errorRate();
			case TIMEOUT_RATE -> summary.timeoutRate();
			case LATENCY_P50 -> summary.latencyMs().p50();
			case LATENCY_P95 -> summary.latencyMs().p95();
			case LATENCY_P99 -> summary.latencyMs().p99();
			case LATENCY_MEAN -> summary.latencyMs().mean();
			case RETRY_AMPLIFICATION -> summary.retryAmplification();
		};
	}

}
