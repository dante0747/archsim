package io.archsim.engine.components.service;

import java.util.Arrays;
import java.util.Optional;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.numeric.FloatSum;

/**
 * An autoscaling service's control loop, as a Kubernetes HPA scales: every interval it
 * measures the utilization of the replicas taking requests over the last interval (with a
 * CPU size, CPU use counts too), and wants {@code ceil(replicas × utilization ÷ target)}
 * replicas within its bounds. It starts the replicas it lacks, which take requests after
 * their start-up (on a cluster, once their pod is bound, has pulled the image and
 * started); it removes replicas only after three low readings in a row: on a cluster the
 * Pending pods first, else the newest replicas, never the last.
 */
final class Autoscaler {

	/** The counter of evaluations that started replicas. */
	static final String SCALE_UPS = "scaleUps";

	/** The counter of evaluations that removed replicas. */
	static final String SCALE_DOWNS = "scaleDowns";

	private static final double MS_PER_SECOND = 1000.0;

	/** The low readings in a row that scale down. */
	private static final int LOW_READINGS_TO_SCALE_DOWN = 3;

	private final Simulation simulation;

	private final ServiceState service;

	private final ReplicaSet.Scaling scaling;

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
		this.scaling = service.replicas().scaling().orElseThrow();
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
		int planned = active + this.scaling.starting();
		int pending = this.service.pods().map(Pods::pendingCount).orElse(0);
		if (wanted > planned) {
			this.lowReadings = 0;
			this.service.metrics().count(SCALE_UPS, now);
			for (int replica = 0; replica < wanted - planned; replica++) {
				this.scaling.expect();
				this.simulation.start(this::startReplica);
			}
		}
		else if (pending > 0 && wanted < planned) {
			int surplus = Math.min(pending, planned - wanted);
			countLowReading(() -> this.service.pods().orElseThrow().cancelPending(surplus));
		}
		else if (wanted < active && this.scaling.starting() == 0) {
			countLowReading(() -> retireNewest(now, active - wanted));
		}
		else {
			this.lowReadings = 0;
		}
		waitAnInterval();
	}

	// the replicas wanted at a utilization, within the service's bounds
	private int wanted(double utilization, int active) {
		return this.service.config().wantedReplicas(active * utilization);
	}

	// the busy handlers over the handlers taking requests since the last evaluation; with
	// a CPU size, at least the busy executors over theirs
	private double utilization(double now) {
		Gauge capacity = this.scaling.capacity();
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

	// counts a low reading; the third in a row scales down and starts the count again
	private void countLowReading(Runnable scaleDown) {
		this.lowReadings++;
		if (this.lowReadings < LOW_READINGS_TO_SCALE_DOWN) {
			return;
		}
		this.lowReadings = 0;
		scaleDown.run();
	}

	// the newest replicas retire, never the last; on a cluster, their pods free their
	// nodes
	private void retireNewest(double now, int surplus) {
		this.service.metrics().count(SCALE_DOWNS, now);
		Optional<Pods> pods = this.service.pods();
		for (int replica = 0; replica < surplus; replica++) {
			Optional<Replica> retired = this.scaling.retireNewest(now);
			if (retired.isPresent() && pods.isPresent()) {
				pods.get().release(retired.get());
			}
		}
	}

	// a replica asked for takes requests after its start-up; on a cluster, once its pod
	// is ready
	private void startReplica(Runnable finish) {
		this.service.pods().ifPresentOrElse((pods) -> startPod(pods, finish), () -> startAfterStartup(finish));
	}

	private void startAfterStartup(Runnable finish) {
		this.simulation.after(this.service.config().startupS() * MS_PER_SECOND, () -> {
			this.service.startReplica(this.simulation.now());
			finish.run();
		});
	}

	// the replica's pod is bound, pulls the image and starts, unless it is deleted while
	// Pending
	private void startPod(Pods pods, Runnable finish) {
		pods.startPod((node) -> {
			Replica started = this.service.startReplica(this.simulation.now());
			pods.recordNode(started, node);
			finish.run();
		}, () -> {
			this.scaling.abandon();
			finish.run();
		});
	}

	// a gauge's time integral so far: brought up to now first, which is safe mid-run
	private static double integral(Gauge gauge, double now) {
		gauge.finish(now);
		return FloatSum.of(Arrays.stream(gauge.area()));
	}

}
