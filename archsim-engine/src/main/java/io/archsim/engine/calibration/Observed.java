package io.archsim.engine.calibration;

import java.util.List;

import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;

/**
 * What a calibrated part used over the range, for the replay to compare the simulation
 * with.
 *
 * @param cpu the CPU cores in use over all its replicas, by time; none when not fitted
 * @param memory its working set per replica in MiB, by time; none when not fitted
 */
record Observed(List<Point> cpu, List<Point> memory) {

	/** Nothing observed. */
	static final Observed NONE = new Observed(List.of(), List.of());

	/**
	 * Copies the points.
	 */
	Observed {
		cpu = List.copyOf(cpu);
		memory = List.copyOf(memory);
	}

	Observed withCpu(List<Point> points) {
		return new Observed(points, this.memory);
	}

	Observed withMemory(List<Point> points) {
		return new Observed(this.cpu, points);
	}

}
