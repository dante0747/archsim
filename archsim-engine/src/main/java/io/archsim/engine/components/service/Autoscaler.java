package io.archsim.engine.components.service;

import java.util.Arrays;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.model.component.service.ServiceConfig;

/**
 * An autoscaling service's control loop, as a Kubernetes HPA scales: every interval it
 * measures the utilization of the replicas taking requests over the last interval (with a
 * CPU size, CPU use counts too), and wants {@code ceil(replicas × utilization ÷ target)}
 * replicas within its bounds. It starts the replicas it lacks, which take requests after
 * their start-up; it removes replicas only after three low readings in a row, the newest
 * first, never the last.
 */
final class Autoscaler {

	/** The counter of evaluations that started replicas. */
	static final String SCALE_UPS = "scaleUps";

	/** The counter of evaluations that removed replicas. */
	static final String SCALE_DOWNS = "scaleDowns";

	private static final double MS_PER_SECOND = 1000.0;

	/** Absorbs floating-point error in the wanted replicas, as the Python engine does. */
	private static final double ROUNDING_TOLERANCE = 1e-9;

	/** The low readings in a row that scale down. */
	private static final int LOW_READINGS_TO_SCALE_DOWN = 3;

	private final Simulation simulation;

	private final ServiceState service;

	private int starting;

	private int lowReadings;

	private double busySeen;

	private double capacitySeen;

	private double coresSeen;

	/**
	 * A service's autoscaler.
	 * @param simulation the run
	 * @param service the service, its replicas scaling
	 */
	Autoscaler(Simulation simulation, ServiceState service) {
		this.simulation = simulation;
		this.service = service;
	}

	/**
	 * Starts the loop, which evaluates after each interval for the rest of the run.
	 */
	void start() {
		this.simulation.start((finish) -> waitAnInterval());
	}

	private void waitAnInterval() {
		this.simulation.after(this.service.config().scaleIntervalS() * MS_PER_SECOND, this::evaluate);
	}

	private void evaluate() {
		double now = this.simulation.now();
		int active = this.service.replicas().routableCount();
		int wanted = wanted(utilization(now), active);
		int planned = active + this.starting;
		if (wanted > planned) {
			this.lowReadings = 0;
			this.service.metrics().count(SCALE_UPS, now);
			for (int replica = 0; replica < wanted - planned; replica++) {
				this.starting++;
				this.simulation.start(this::startReplica);
			}
		}
		else if (wanted < active && this.starting == 0) {
			lowReading(now, active - wanted);
		}
		else {
			this.lowReadings = 0;
		}
		waitAnInterval();
	}

	// the replicas wanted at a utilization, within the service's bounds
	private int wanted(double utilization, int active) {
		ServiceConfig config = this.service.config();
		int wanted = (utilization > 0)
				? (int) Math.ceil(active * utilization / config.targetUtilization() - ROUNDING_TOLERANCE) : 0;
		return Math.min(Math.max(wanted, config.minReplicas()), config.maxReplicas());
	}

	// the busy handlers over the handlers taking requests since the last evaluation; with
	// a CPU size, at least the busy executors over theirs
	private double utilization(double now) {
		Gauge capacity = this.service.replicas().scaling().orElseThrow().capacity();
		double busy = integral(this.service.metrics().busy(), now);
		double handlers = integral(capacity, now);
		double newBusy = busy - this.busySeen;
		double newHandlers = handlers - this.capacitySeen;
		this.busySeen = busy;
		this.capacitySeen = handlers;
		double utilization = (newHandlers > 0) ? newBusy / newHandlers : 0.0;
		Gauge cores = this.service.coreBusy();
		if (cores != null) {
			double coresBusy = integral(cores, now);
			double newCores = coresBusy - this.coresSeen;
			this.coresSeen = coresBusy;
			double executors = newHandlers * this.service.replicas().first().cores().executors()
					/ Math.max(1, this.service.perReplica());
			if (executors > 0) {
				utilization = Math.max(utilization, newCores / executors);
			}
		}
		return utilization;
	}

	private void lowReading(double now, int surplus) {
		this.lowReadings++;
		if (this.lowReadings < LOW_READINGS_TO_SCALE_DOWN) {
			return;
		}
		this.lowReadings = 0;
		this.service.metrics().count(SCALE_DOWNS, now);
		for (int replica = 0; replica < surplus; replica++) {
			this.service.replicas().scaling().orElseThrow().retireNewest(now);
		}
	}

	// a replica takes requests after its start-up
	private void startReplica(Runnable finish) {
		this.simulation.after(this.service.config().startupS() * MS_PER_SECOND, () -> {
			this.starting--;
			this.service.startReplica(this.simulation.now());
			finish.run();
		});
	}

	// a gauge's time integral so far: brought up to now first, which is safe mid-run
	private static double integral(Gauge gauge, double now) {
		gauge.finish(now);
		return FloatSum.of(Arrays.stream(gauge.area()));
	}

}
