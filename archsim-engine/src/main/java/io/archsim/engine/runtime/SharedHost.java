package io.archsim.engine.runtime;

import java.util.OptionalDouble;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import io.archsim.engine.host.HostMachine;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.MeanAccumulator;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.numeric.FloatSum;

/**
 * The design's host machine during a run, which every part with a CPU size shares: the
 * parts' busy threads, the machine's background and the CPU the clients' traffic costs on
 * it all slow its threads. It keeps what the report sums up: the busy threads over time
 * and the speed each piece of work started at.
 */
public final class SharedHost implements CpuMachine {

	private static final double MS_PER_SECOND = 1000.0;

	/** The points the clients' mean rate over the window is taken at. */
	private static final int RATE_STEPS = 50;

	private static final double HALF_STEP = 0.5;

	private final Simulation simulation;

	private final HostMachine machine;

	private final Timeline timeline;

	private final DoubleUnaryOperator rateAt;

	private final Gauge busyThreads;

	private final MeanAccumulator speeds;

	private int busy;

	/** The CPU work started inside the window, at full speed, in milliseconds. */
	private double windowWorkMs;

	/**
	 * The design's host at the start of a run.
	 * @param simulation the run
	 * @param machine its CPU
	 * @param timeline the run's timeline
	 * @param rateAt the clients' offered rate, in requests per second, at a time in
	 * seconds
	 */
	public SharedHost(Simulation simulation, HostMachine machine, Timeline timeline, DoubleUnaryOperator rateAt) {
		this.simulation = simulation;
		this.machine = machine;
		this.timeline = timeline;
		this.rateAt = rateAt;
		this.busyThreads = new Gauge(timeline);
		this.speeds = new MeanAccumulator(timeline);
	}

	/**
	 * The machine's CPU.
	 * @return its cores, threads and speeds
	 */
	public HostMachine machine() {
		return this.machine;
	}

	@Override
	public double start(double workMs) {
		double now = this.simulation.now();
		this.busy++;
		this.busyThreads.add(now, 1);
		double speed = this.machine
			.speed(this.busy + this.machine.other(this.rateAt.applyAsDouble(now / MS_PER_SECOND)));
		this.speeds.record(now, speed);
		if (this.timeline.inWindow(now)) {
			this.windowWorkMs += workMs;
		}
		return workMs / speed;
	}

	@Override
	public void stop() {
		this.busy--;
		this.busyThreads.add(this.simulation.now(), -1);
	}

	/**
	 * The multiple of the load at which the part's threads fill while every part's load
	 * and the clients' grow in proportion to what the window saw; with no load seen, the
	 * part alone on the machine.
	 */
	@Override
	public double capacity(double fullSpeed, double ownThreads, double observedRps) {
		if (!CpuMachine.slows(fullSpeed, ownThreads)) {
			return fullSpeed;
		}
		double ownMs = ownThreads * MS_PER_SECOND / fullSpeed;
		if (observedRps > 0) {
			double ownBase = observedRps * ownMs / MS_PER_SECOND;
			double base = Math.max(windowBase(), ownBase);
			return this.machine.scaleFilling(base, ownBase, ownThreads, windowClientRps()) * observedRps;
		}
		return this.machine.scaleFilling(ownThreads, ownThreads, ownThreads, 0.0) * fullSpeed;
	}

	/**
	 * Ends the busy threads' series at the end of the run.
	 * @param end when the run stopped
	 */
	public void finish(double end) {
		this.busyThreads.finish(end);
	}

	/**
	 * The host over the measurement window, for the report.
	 * @return the parts' mean busy threads, the background's and the clients' traffic's,
	 * and the mean speed work started at
	 */
	public Window window() {
		return new Window(this.busyThreads.windowMean(), this.machine.other(windowClientRps()),
				this.speeds.windowMean());
	}

	/**
	 * The clients' mean offered rate over the window, taken at the middles of 50 steps.
	 * @return requests per second
	 */
	double windowClientRps() {
		double from = this.timeline.windowStart() / MS_PER_SECOND;
		double to = this.timeline.windowEnd() / MS_PER_SECOND;
		DoubleStream rates = IntStream.range(0, RATE_STEPS)
			.mapToDouble((step) -> this.rateAt.applyAsDouble(from + (step + HALF_STEP) * (to - from) / RATE_STEPS));
		return FloatSum.of(rates) / RATE_STEPS;
	}

	// the parts' CPU work over the window, in threads at full speed
	private double windowBase() {
		double windowMs = this.timeline.windowMs();
		return (windowMs > 0) ? this.windowWorkMs / windowMs : 0.0;
	}

	/**
	 * The host over the measurement window.
	 *
	 * @param partsBusyThreads the parts' mean busy threads
	 * @param otherBusyThreads the background's and the clients' traffic's threads
	 * @param meanSpeed the mean speed work started at; empty when none started
	 */
	public record Window(double partsBusyThreads, double otherBusyThreads, OptionalDouble meanSpeed) {
	}

}
