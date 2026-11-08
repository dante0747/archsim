package io.archsim.engine.components.service;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.components.kubernetes.ClusterNode;
import io.archsim.engine.components.kubernetes.ClusterRuntime;
import io.archsim.engine.kernel.Promise;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.kubernetes.PodRequest;
import io.archsim.model.component.service.ServiceConfig;

/**
 * A service's pods on its Kubernetes cluster: each replica is a pod, bound to a node with
 * room for its request. A pod that finds none waits Pending, its replica taking no
 * requests; once bound it pulls the service's image (on a node new to the service) and
 * starts before its replica takes requests again.
 */
final class Pods {

	private static final double MS_PER_SECOND = 1000.0;

	private final SimContext context;

	private final String serviceId;

	private final String clusterId;

	private final ServiceConfig config;

	private final Gauge pending;

	private final Gauge starting;

	private @Nullable ClusterRuntime cluster;

	/**
	 * A service's pods, before the run places them.
	 * @param context the run
	 * @param serviceId the service
	 * @param clusterId the cluster it runs on
	 * @param config its settings
	 */
	Pods(SimContext context, String serviceId, String clusterId, ServiceConfig config) {
		this.context = context;
		this.serviceId = serviceId;
		this.clusterId = clusterId;
		this.config = config;
		this.pending = new Gauge(context.timeline());
		this.starting = new Gauge(context.timeline());
	}

	/**
	 * Places the pods of the replicas the service starts with, once the run starts: a pod
	 * bound at once ran before the run (its node has the image); a replica whose pod
	 * waits stops taking requests until the pod is ready.
	 * @param replicas the service's replicas
	 */
	void placeInitial(ReplicaSet replicas) {
		Simulation simulation = this.context.simulation();
		simulation.start((finish) -> {
			replicas.routableReplicas().forEach((replica) -> place(replica, replicas.scaling().orElseThrow()));
			simulation.timeout(0).then(finish);
		});
	}

	/**
	 * The cluster the pods run on.
	 * @return its id
	 */
	String clusterId() {
		return this.clusterId;
	}

	/**
	 * The cluster's settings.
	 * @return its config
	 */
	ClusterConfig clusterConfig() {
		return cluster().config();
	}

	/**
	 * The most of the service's pods Pending at once.
	 * @return the peak
	 */
	long pendingPeak() {
		return this.pending.runPeak();
	}

	/**
	 * The service's Pending pods in each bucket.
	 * @return one mean per bucket; {@code null} for a bucket the run never reached
	 */
	List<@Nullable Double> pendingSeries() {
		return this.pending.meanSeriesUntilEnd();
	}

	/**
	 * The service's pods pulling their image or starting, in each bucket.
	 * @return one mean per bucket; {@code null} for a bucket the run never reached
	 */
	List<@Nullable Double> startingSeries() {
		return this.starting.meanSeriesUntilEnd();
	}

	void finish(double end) {
		this.pending.finish(end);
		this.starting.finish(end);
	}

	// a starting replica's pod: bound at once, it ran before the run; else its replica
	// takes no requests until the pod is ready
	private void place(Replica replica, ReplicaSet.Scaling scaling) {
		Simulation simulation = this.context.simulation();
		Promise<ClusterNode> placed = cluster().requestPod(PodRequest.of(this.config, clusterConfig()));
		if (placed.triggered()) {
			cluster().cacheImage(placed.value(), this.serviceId);
			return;
		}
		scaling.withdraw(replica, simulation.now());
		simulation.start((done) -> awaitPod(placed, () -> {
			scaling.rejoin(replica, simulation.now());
			done.run();
		}));
	}

	// a Pending pod: once bound, its image pull and start-up, then ready
	private void awaitPod(Promise<ClusterNode> placed, Runnable ready) {
		Simulation simulation = this.context.simulation();
		this.pending.add(simulation.now(), 1);
		placed.thenAccept((node) -> {
			this.pending.add(simulation.now(), -1);
			this.starting.add(simulation.now(), 1);
			double delayMs = cluster().pullImage(node, this.serviceId) + this.config.startupS() * MS_PER_SECOND;
			simulation.after(delayMs, () -> {
				this.starting.add(simulation.now(), -1);
				ready.run();
			});
		});
	}

	// the cluster, found once the run starts, when every part is built
	private ClusterRuntime cluster() {
		ClusterRuntime known = this.cluster;
		if (known == null) {
			known = (ClusterRuntime) this.context.component(this.clusterId);
			this.cluster = known;
		}
		return known;
	}

}
