package io.archsim.engine.components.kubernetes;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.kubernetes.NodePacking;

/**
 * A cluster's autoscaler, as the Kubernetes cluster autoscaler scales a node group: every
 * scan interval, while pods are Pending, it adds the nodes they need within the cluster's
 * maximum, each ready after its provisioning time; with none Pending, it removes the
 * nodes empty for long enough, down to the cluster's minimum.
 */
final class ClusterAutoscaler {

	/** The counter of nodes asked for. */
	static final String NODE_ADDS = "nodeAdds";

	/** The counter of empty nodes removed. */
	static final String NODE_REMOVALS = "nodeRemovals";

	private static final double MS_PER_SECOND = 1000.0;

	private final Simulation simulation;

	private final NodePool pool;

	private final ClusterConfig config;

	private final NodeMetrics metrics;

	/**
	 * A cluster's autoscaler.
	 * @param simulation the run
	 * @param pool the cluster's nodes
	 * @param config its settings
	 * @param metrics its metrics
	 */
	ClusterAutoscaler(Simulation simulation, NodePool pool, ClusterConfig config, NodeMetrics metrics) {
		this.simulation = simulation;
		this.pool = pool;
		this.config = config;
		this.metrics = metrics;
	}

	/**
	 * Starts the loop, which scans after each interval for the rest of the run.
	 */
	void start() {
		this.simulation.start((finish) -> scheduleNextScan());
	}

	private void scheduleNextScan() {
		this.simulation.after(this.config.scanIntervalS() * MS_PER_SECOND, this::scan);
	}

	private void scan() {
		double now = this.simulation.now();
		if (this.pool.pendingCount() > 0) {
			addNodes(now);
		}
		else if (this.pool.nodeCount() + this.pool.provisioningCount() > this.config.minNodes()) {
			removeEmptyNodes(now);
		}
		scheduleNextScan();
	}

	// the nodes the Pending pods need, less those on their way, within the maximum
	private void addNodes(double now) {
		int room = this.config.maxNodes() - this.pool.nodeCount() - this.pool.provisioningCount();
		int adding = Math.min(room, nodesNeeded() - this.pool.provisioningCount());
		for (int node = 0; node < adding; node++) {
			this.pool.startProvisioning(now);
			this.metrics.count(NODE_ADDS, now);
			this.simulation.start(this::provisionNode);
		}
	}

	// a node joins the cluster after its provisioning time
	private void provisionNode(Runnable finish) {
		this.simulation.after(this.config.nodeProvisionS() * MS_PER_SECOND, () -> {
			this.pool.addProvisioned();
			finish.run();
		});
	}

	// the nodes empty for long enough, in the order they joined, down to the minimum
	private void removeEmptyNodes(double now) {
		double unneededMs = this.config.scaleDownUnneededS() * MS_PER_SECOND;
		for (ClusterNode node : this.pool.nodes()) {
			if (this.pool.nodeCount() <= this.config.minNodes()) {
				return;
			}
			if (node.emptyFor(now, unneededMs)) {
				this.pool.remove(node, now);
				this.metrics.count(NODE_REMOVALS, now);
			}
		}
	}

	// the new empty nodes the Pending pods need
	private int nodesNeeded() {
		return NodePacking.nodesFor(this.pool.pendingRequests(), this.pool.nodeCpu(), this.pool.nodeMemoryGib());
	}

}
