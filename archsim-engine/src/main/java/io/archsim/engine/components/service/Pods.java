package io.archsim.engine.components.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

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
 * starts before its replica takes requests. A pod deleted while Pending never starts; a
 * retired replica's pod frees its node.
 */
final class Pods {

	private static final double MS_PER_SECOND = 1000.0;

	private final SimContext context;

	private final String serviceId;

	private final String clusterId;

	private final ServiceConfig config;

	private final Gauge pending;

	private final Gauge starting;

	private final Map<Replica, ClusterNode> nodes = new HashMap<>();

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
	 * Asks for the pod of a replica the service scales up to: once it is bound, has
	 * pulled the image and started, the replica takes requests.
	 * @param ready what to do with the node of a pod that is ready
	 * @param deleted what to do when the pod is deleted while Pending
	 */
	void startPod(Consumer<ClusterNode> ready, Runnable deleted) {
		awaitPod(cluster().requestPod(this.serviceId, request()), ready, deleted);
	}

	/**
	 * Notes the node a replica's pod runs on.
	 * @param replica the replica
	 * @param node its pod's node
	 */
	void recordNode(Replica replica, ClusterNode node) {
		this.nodes.put(replica, node);
	}

	/**
	 * Frees the node of a retired replica's pod.
	 * @param replica the replica
	 * @throws IllegalStateException when the replica has no pod on a node
	 */
	void release(Replica replica) {
		ClusterNode node = this.nodes.remove(replica);
		if (node == null) {
			throw new IllegalStateException("a replica with no pod on a node cannot be released");
		}
		cluster().releasePod(node, request());
	}

	/**
	 * Deletes the service's Pending pods, the newest first.
	 * @param count how many at most
	 */
	void cancelPending(int count) {
		cluster().cancelPending(this.serviceId, count);
	}

	/**
	 * The service's pods Pending now.
	 * @return how many
	 */
	int pendingCount() {
		return (int) this.pending.value();
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
		Promise<Optional<ClusterNode>> placed = cluster().requestPod(this.serviceId, request());
		if (placed.triggered()) {
			ClusterNode node = placed.value().orElseThrow();
			cluster().cacheImage(node, this.serviceId);
			recordNode(replica, node);
			return;
		}
		scaling.withdraw(replica, simulation.now());
		simulation.start((done) -> awaitPod(placed, (node) -> {
			recordNode(replica, node);
			scaling.rejoin(replica, simulation.now());
			done.run();
		}, () -> {
			scaling.abandon();
			done.run();
		}));
	}

	// a Pending pod: once bound, its image pull and start-up, then ready
	private void awaitPod(Promise<Optional<ClusterNode>> placed, Consumer<ClusterNode> ready, Runnable deleted) {
		Simulation simulation = this.context.simulation();
		this.pending.add(simulation.now(), 1);
		placed.thenAccept((bound) -> {
			this.pending.add(simulation.now(), -1);
			bound.ifPresentOrElse((node) -> startOn(node, ready), deleted);
		});
	}

	// a bound pod pulls the service's image (on a node new to the service) and starts
	private void startOn(ClusterNode node, Consumer<ClusterNode> ready) {
		Simulation simulation = this.context.simulation();
		this.starting.add(simulation.now(), 1);
		double delayMs = cluster().pullImage(node, this.serviceId) + this.config.startupS() * MS_PER_SECOND;
		simulation.after(delayMs, () -> {
			this.starting.add(simulation.now(), -1);
			ready.accept(node);
		});
	}

	private PodRequest request() {
		return PodRequest.of(this.config, clusterConfig());
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
