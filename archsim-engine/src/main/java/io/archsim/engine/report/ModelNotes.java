package io.archsim.engine.report;

import java.util.List;

/**
 * What every report says about the model behind its numbers, before its notes on
 * experimental technologies ({@code MODEL_NOTES}).
 */
public final class ModelNotes {

	/** The results are a simulation's estimates, not measurements. */
	private static final String SYNTHETIC = "Results come from a synthetic discrete-event simulation of the model you "
			+ "drew, driven by the assumptions you entered. They are estimates, not measurements, and not a production "
			+ "guarantee.";

	/** Without calibration, compare scenarios rather than trust absolute numbers. */
	private static final String UNCALIBRATED = "Without calibration against real telemetry, absolute numbers (latency, "
			+ "capacity) can be far off. Comparisons between scenarios under the same assumptions are more trustworthy "
			+ "than any single absolute value.";

	/** Clients keep sending at their rate however slow the system gets. */
	private static final String OPEN_LOOP = "Arrivals are open-loop: clients keep sending at the configured rate even "
			+ "when the system slows down (no back-pressure on users).";

	/** What the network's model leaves out. */
	private static final String NETWORK = "Network latency is sampled per link and direction. Bandwidth, packet loss, "
			+ "connection setup and TLS are not modeled.";

	/** Parts behave like their technology, with settings that can be overridden. */
	private static final String TECHNOLOGIES = "Components behave like the technology you picked (row locks and group "
			+ "commit, replica lag, single-threaded caches, event loops, GC pauses, partitions, visibility "
			+ "timeouts...), with documented defaults where they exist and illustrative ones elsewhere; every such "
			+ "setting is listed under Technology behaviour and can be overridden. CPU contention and memory limits "
			+ "apply when you set CPU and memory. Noisy neighbours are not modeled. Capacity is fixed unless a service "
			+ "has autoscaling on or runs serverless.";

	/** Faults apply only in their windows. */
	private static final String FAULTS = "Faults (outages, slowdowns, link latency) apply only inside their time "
			+ "windows; components do not fail on their own otherwise (third-party APIs may have an error rate).";

	/** How the summary statistics are measured. */
	private static final String STATISTICS = "Summary statistics exclude the warm-up period. Percentiles come from a "
			+ "log-bucketed histogram with about 2% resolution.";

	/** The same inputs reproduce the same results. */
	private static final String REPRODUCIBLE = "The same document, scenario, seed and engine version reproduce "
			+ "identical results on the same platform.";

	private static final List<String> ALL = List.of(SYNTHETIC, UNCALIBRATED, OPEN_LOOP, NETWORK, TECHNOLOGIES, FAULTS,
			STATISTICS, REPRODUCIBLE);

	private ModelNotes() {
	}

	/**
	 * The notes.
	 * @return them, in the order a report gives them
	 */
	public static List<String> all() {
		return ALL;
	}

}
