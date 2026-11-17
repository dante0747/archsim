package io.archsim.engine.components.database;

import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;

/**
 * What one instance of a database of several did inside the measurement window, for the
 * database's CPU ceiling (ADR-0083): the operations sent to it, the ones it refused too,
 * the operations it served and their CPU work at full speed, a replica's apply work, and
 * its busy executors.
 */
final class InstanceLoad {

	private final Timeline timeline;

	private final Gauge busy;

	private long sent;

	private long served;

	private double servedCpuMs;

	private double applyCpuMs;

	/**
	 * An instance's load at the start of a run.
	 * @param timeline the run's timeline
	 */
	InstanceLoad(Timeline timeline) {
		this.timeline = timeline;
		this.busy = new Gauge(timeline);
	}

	/**
	 * Its busy executors, which its cores count in.
	 * @return the gauge
	 */
	Gauge busy() {
		return this.busy;
	}

	/**
	 * Counts an operation sent to it, whether it serves it or not.
	 * @param time when
	 */
	void sent(double time) {
		if (this.timeline.inWindow(time)) {
			this.sent++;
		}
	}

	/**
	 * Counts an operation it serves, and its CPU work.
	 * @param time when the work is handed to its cores, before any wait for a free one
	 * @param cpuMs the work at full speed, in milliseconds
	 */
	void served(double time, double cpuMs) {
		if (this.timeline.inWindow(time)) {
			this.served++;
			this.servedCpuMs += cpuMs;
		}
	}

	/**
	 * Counts a replica's work applying its primary's writes.
	 * @param time when the work is handed to its cores
	 * @param cpuMs the work at full speed, in milliseconds
	 */
	void applied(double time, double cpuMs) {
		if (this.timeline.inWindow(time)) {
			this.applyCpuMs += cpuMs;
		}
	}

	/**
	 * The CPU work at full speed it would have done in the window had it served every
	 * operation sent to it: what it refused costs what the ones it served did, and a
	 * replica's apply work counts as it came.
	 * @return the work, in milliseconds
	 */
	double demandMs() {
		double perOperation = (this.served > 0) ? this.servedCpuMs / this.served : 0.0;
		return perOperation * this.sent + this.applyCpuMs;
	}

	/**
	 * Its mean busy executors inside the window.
	 * @return the mean
	 */
	double meanBusy() {
		return this.busy.windowMean();
	}

}
