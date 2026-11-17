package io.archsim.engine.slo;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.JsonEnum;
import io.archsim.model.document.Objectives;
import io.archsim.model.text.PythonFormat;

/**
 * An objective a run is judged by, in the order the verdict checks them: the same checks
 * as the web app's verdict.
 */
public enum Objective implements JsonEnum {

	/** The p95 latency, at most the target. */
	P95("p95", "Latency p95", Better.LOWER),

	/** The p99 latency, at most the target. */
	P99("p99", "Latency p99", Better.LOWER),

	/**
	 * The share of requests that succeed and whose messages are not lost (ADR-0084), at
	 * least the target.
	 */
	AVAILABILITY("availability", "Availability", Better.HIGHER),

	/** The successful requests per second, at least the target. */
	THROUGHPUT("throughput", "Throughput", Better.HIGHER),

	/**
	 * The p95 age of a message when processed, at the slowest queue, at most the target.
	 */
	MESSAGE_AGE("messageAge", "Message age p95", Better.LOWER);

	/** A share is written as a percentage. */
	private static final int PERCENT = 100;

	private final String json;

	private final String label;

	private final Better better;

	Objective(String json, String label, Better better) {
		this.json = json;
		this.label = label;
		this.better = better;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * What the objective is called.
	 * @return such as {@code Latency p95}
	 */
	public String label() {
		return this.label;
	}

	/**
	 * Which way the figure is better.
	 * @return lower for a latency, higher otherwise
	 */
	public Better better() {
		return this.better;
	}

	/**
	 * The design's target for this objective.
	 * @param objectives the design's objectives
	 * @return the target, or {@code null} when it is off
	 */
	@Nullable Double target(Objectives objectives) {
		return switch (this) {
			case P95 -> objectives.latencyP95Ms();
			case P99 -> objectives.latencyP99Ms();
			case AVAILABILITY -> objectives.availability();
			case THROUGHPUT -> objectives.minThroughputRps();
			case MESSAGE_AGE -> objectives.messageAgeP95Ms();
		};
	}

	/**
	 * Whether the objective judges a run: the message age only one whose design has a
	 * queue (ADR-0084), every other always.
	 * @param figures the run's figures
	 * @return {@code true} when it judges the run
	 */
	boolean judges(RunFigures figures) {
		return this != MESSAGE_AGE || figures.queued();
	}

	/**
	 * A run's figure for this objective.
	 * @param figures the run's figures
	 * @return the figure, or {@code null} when the run has none
	 */
	@Nullable Double actual(RunFigures figures) {
		return switch (this) {
			case P95 -> figures.p95();
			case P99 -> figures.p99();
			case AVAILABILITY -> figures.availability();
			case THROUGHPUT -> figures.throughputRps();
			case MESSAGE_AGE -> figures.messageAgeP95Ms();
		};
	}

	/**
	 * How a verdict writes a figure of this objective: {@code 1,234.5 ms},
	 * {@code 99.95%}, {@code 1,235 req/s}.
	 * @param value the figure, or {@code null}
	 * @return its text; {@code n/a} without a figure
	 */
	String text(@Nullable Double value) {
		if (value == null) {
			return "n/a";
		}
		return switch (this) {
			case P95, P99, MESSAGE_AGE -> PythonFormat.format(value, ",.1f") + " ms";
			case AVAILABILITY -> PythonFormat.format(value * PERCENT, ".2f") + "%";
			case THROUGHPUT -> PythonFormat.format(value, ",.0f") + " req/s";
		};
	}

}
