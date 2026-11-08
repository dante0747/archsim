package io.archsim.engine.components.kubernetes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Promise;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.MeanAccumulator;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.kubernetes.PodRequest;

/**
 * A cluster's nodes and the pods waiting for one, placed as kube-scheduler places them: a
 * pod is bound to the node with the most left among those with room for its request, or
 * waits Pending until a node has room, the longest waiting first.
 */
final class NodePool {

	/** The counter of pods that found no node with room. */
	static final String PODS_QUEUED = "podsQueued";

	/** The counter of pods bound to a node. */
	static final String PODS_SCHEDULED = "podsScheduled";

	private final Simulation simulation;

	private final NodeMetrics metrics;

	private final ClusterGauges gauges;

	private final double nodeCpu;

	private final double nodeMemoryGib;

	private final List<ClusterNode> nodes = new ArrayList<>();

	private final Deque<PodClaim> pending = new ArrayDeque<>();

	private final MeanAccumulator pendingWait;

	private double maxWaitMs;

	private int provisioning;

	/**
	 * A cluster's nodes at the start of a run, empty.
	 * @param simulation the run
	 * @param metrics the cluster's metrics
	 * @param config its settings
	 */
	NodePool(Simulation simulation, NodeMetrics metrics, ClusterConfig config) {
		this.simulation = simulation;
		this.metrics = metrics;
		this.nodeCpu = config.allocatableCpu();
		this.nodeMemoryGib = config.allocatableMemoryGib();
		for (int node = 0; node < config.nodes(); node++) {
			this.nodes.add(new ClusterNode(this.nodeCpu, this.nodeMemoryGib, 0.0));
		}
		this.gauges = new ClusterGauges(metrics.timeline(), config.nodes(), this.nodeCpu, this.nodeMemoryGib);
		this.pendingWait = new MeanAccumulator(metrics.timeline());
	}

	/**
	 * Asks for a pod: bound now to the least allocated node with room, else Pending.
	 * @param request what it requests
	 * @return the node the pod is bound to, once it is
	 */
	Promise<ClusterNode> request(PodRequest request) {
		double now = this.simulation.now();
		PodClaim claim = new PodClaim(request, this.simulation.promise(), now);
		ClusterNode node = fit(request);
		if (node != null) {
			bind(node, claim);
		}
		else {
			this.pending.addLast(claim);
			this.gauges.queuePod(now);
			this.metrics.count(PODS_QUEUED, now);
		}
		return claim.placed();
	}

	/**
	 * Binds the Pending pods that fit now, the longest waiting first; the others keep
	 * waiting.
	 */
	void schedulePending() {
		if (this.pending.isEmpty()) {
			return;
		}
		double now = this.simulation.now();
		Deque<PodClaim> still = new ArrayDeque<>();
		while (!this.pending.isEmpty()) {
			PodClaim claim = this.pending.removeFirst();
			ClusterNode node = fit(claim.request());
			if (node == null) {
				still.addLast(claim);
			}
			else {
				this.gauges.dequeuePod(now);
				bind(node, claim);
			}
		}
		this.pending.addAll(still);
	}

	/**
	 * Counts a node the cluster autoscaler has asked for.
	 * @param now the time
	 */
	void startProvisioning(double now) {
		this.provisioning++;
		this.gauges.startProvisioning(now);
	}

	/**
	 * Adds a node that is ready, empty, and binds the Pending pods that fit.
	 */
	void addProvisioned() {
		double now = this.simulation.now();
		this.provisioning--;
		this.nodes.add(new ClusterNode(this.nodeCpu, this.nodeMemoryGib, now));
		this.gauges.addProvisioned(now);
		schedulePending();
	}

	/**
	 * Removes a node.
	 * @param node the node, empty
	 * @param now the time
	 */
	void remove(ClusterNode node, double now) {
		this.nodes.remove(node);
		this.gauges.removeNode(now);
	}

	/**
	 * The nodes now.
	 * @return a copy, in the order they joined
	 */
	List<ClusterNode> nodes() {
		return List.copyOf(this.nodes);
	}

	int nodeCount() {
		return this.nodes.size();
	}

	int provisioningCount() {
		return this.provisioning;
	}

	/**
	 * What the Pending pods request.
	 * @return the requests, the longest waiting first
	 */
	List<PodRequest> pendingRequests() {
		return this.pending.stream().map(PodClaim::request).toList();
	}

	int pendingCount() {
		return this.pending.size();
	}

	double nodeCpu() {
		return this.nodeCpu;
	}

	double nodeMemoryGib() {
		return this.nodeMemoryGib;
	}

	ClusterGauges gauges() {
		return this.gauges;
	}

	/**
	 * How long a pod waited for a node, on average over the pods bound in the window
	 * after waiting.
	 * @return the wait, in milliseconds; empty when none waited
	 */
	OptionalDouble meanWaitMs() {
		return this.pendingWait.windowMean();
	}

	/**
	 * The longest a pod waited for a node, those still Pending included.
	 * @return the wait, in milliseconds
	 */
	double maxWaitMs() {
		return this.maxWaitMs;
	}

	/**
	 * Counts the waits of the pods still Pending, and integrates the gauges up to the end
	 * of the run.
	 * @param end the run's end
	 */
	void finish(double end) {
		for (PodClaim claim : this.pending) {
			this.maxWaitMs = Math.max(this.maxWaitMs, end - claim.requestedAt());
		}
		this.gauges.finish(end);
	}

	// the node with the most left among those with room, the first of them on a tie
	private @Nullable ClusterNode fit(PodRequest request) {
		ClusterNode best = null;
		for (ClusterNode node : this.nodes) {
			if (node.fits(request) && (best == null || node.freeShare() > best.freeShare())) {
				best = node;
			}
		}
		return best;
	}

	private void bind(ClusterNode node, PodClaim claim) {
		double now = this.simulation.now();
		node.bind(claim.request());
		this.gauges.bindPod(now, claim.request());
		double waitMs = now - claim.requestedAt();
		if (waitMs > 0) {
			this.pendingWait.record(now, waitMs);
			this.maxWaitMs = Math.max(this.maxWaitMs, waitMs);
		}
		this.metrics.count(PODS_SCHEDULED, now);
		claim.placed().succeed(node);
	}

	/**
	 * A pod's claim on a node: bound at once, or Pending until a node has room.
	 *
	 * @param request what the pod requests
	 * @param placed the node it is bound to, once it is
	 * @param requestedAt when it asked
	 */
	private record PodClaim(PodRequest request, Promise<ClusterNode> placed, double requestedAt) {
	}

}
