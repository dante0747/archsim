package io.archsim.engine.reporting;

import java.util.List;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.components.client.TrafficSourceRuntime;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.runtime.SimContext;

/**
 * A finished simulation, as the report is written from it.
 *
 * @param prepared the run as it was prepared: the design, its resolution and validation
 * @param context the run's parts, links and traces
 * @param clients the end-to-end client metrics
 * @param sources the client groups, in the design's order
 * @param finalTime when the simulation stopped, in simulated milliseconds
 * @param drainS the time after the arrivals given to requests in flight, in seconds
 * @param events how many events the simulation processed
 */
public record SimulatedRun(PreparedRun prepared, SimContext context, ClientMetrics clients,
		List<TrafficSourceRuntime> sources, double finalTime, double drainS, long events) {

	/**
	 * Copies the client groups, so the run does not change.
	 * @param prepared the run as it was prepared
	 * @param context the run's parts
	 * @param clients the client metrics
	 * @param sources the client groups
	 * @param finalTime when the simulation stopped
	 * @param drainS the drain, in seconds
	 * @param events the events processed
	 */
	public SimulatedRun {
		sources = List.copyOf(sources);
	}

	/**
	 * The run's timeline.
	 * @return the timeline
	 */
	public Timeline timeline() {
		return this.context.timeline();
	}

	/**
	 * How many time buckets the run reached: series stop there.
	 * @return at least one
	 */
	int usedBuckets() {
		Timeline timeline = timeline();
		return Math.max(1, Math.min(timeline.buckets(), (int) Math.ceil(this.finalTime / timeline.bucketMs())));
	}

	/**
	 * The measurement window's length.
	 * @return seconds
	 */
	double windowSeconds() {
		return timeline().windowMs() / Figures.MS_PER_SECOND;
	}

}
