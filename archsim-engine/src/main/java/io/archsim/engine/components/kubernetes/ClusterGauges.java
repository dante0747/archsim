package io.archsim.engine.components.kubernetes;

import io.archsim.engine.metrics.AmountGauge;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;
import io.archsim.model.component.kubernetes.PodRequest;

/**
 * What a cluster measures over a run: its nodes and those being provisioned, its pods
 * running and Pending, and the CPU and memory its pods request against what its nodes
 * offer.
 */
final class ClusterGauges {

	private final Gauge nodes;

	private final Gauge provisioning;

	private final Gauge running;

	private final Gauge pending;

	private final AmountGauge cpuRequested;

	private final AmountGauge memoryRequested;

	private final AmountGauge cpuAllocatable;

	private final AmountGauge memoryAllocatable;

	private final double nodeCpu;

	private final double nodeMemoryGib;

	/**
	 * The gauges of a cluster that starts with empty nodes.
	 * @param timeline the run's timeline
	 * @param startingNodes the nodes it starts with
	 * @param nodeCpu the vCPU each node offers pods
	 * @param nodeMemoryGib the memory each node offers pods, in GiB
	 */
	ClusterGauges(Timeline timeline, int startingNodes, double nodeCpu, double nodeMemoryGib) {
		this.nodes = new Gauge(timeline);
		this.provisioning = new Gauge(timeline);
		this.running = new Gauge(timeline);
		this.pending = new Gauge(timeline);
		this.cpuRequested = new AmountGauge(timeline);
		this.memoryRequested = new AmountGauge(timeline);
		this.cpuAllocatable = new AmountGauge(timeline);
		this.memoryAllocatable = new AmountGauge(timeline);
		this.nodeCpu = nodeCpu;
		this.nodeMemoryGib = nodeMemoryGib;
		this.nodes.add(0.0, startingNodes);
		this.cpuAllocatable.add(0.0, startingNodes * this.nodeCpu);
		this.memoryAllocatable.add(0.0, startingNodes * this.nodeMemoryGib);
	}

	/**
	 * Counts a node the cluster autoscaler asked for.
	 * @param now the time
	 */
	void startProvisioning(double now) {
		this.provisioning.add(now, 1);
	}

	/**
	 * Counts a node that joined the cluster after its provisioning.
	 * @param now the time
	 */
	void addProvisioned(double now) {
		this.provisioning.add(now, -1);
		this.nodes.add(now, 1);
		this.cpuAllocatable.add(now, this.nodeCpu);
		this.memoryAllocatable.add(now, this.nodeMemoryGib);
	}

	/**
	 * Counts a node that left the cluster.
	 * @param now the time
	 */
	void removeNode(double now) {
		this.nodes.add(now, -1);
		this.cpuAllocatable.add(now, -this.nodeCpu);
		this.memoryAllocatable.add(now, -this.nodeMemoryGib);
	}

	/**
	 * Counts a pod that found no node with room.
	 * @param now the time
	 */
	void queuePod(double now) {
		this.pending.add(now, 1);
	}

	/**
	 * Counts a Pending pod that found a node.
	 * @param now the time
	 */
	void dequeuePod(double now) {
		this.pending.add(now, -1);
	}

	/**
	 * Counts a pod bound to a node.
	 * @param now the time
	 * @param request what it requests
	 */
	void bindPod(double now, PodRequest request) {
		this.running.add(now, 1);
		this.cpuRequested.add(now, request.cpu());
		this.memoryRequested.add(now, request.memoryGib());
	}

	Gauge nodes() {
		return this.nodes;
	}

	Gauge provisioning() {
		return this.provisioning;
	}

	Gauge running() {
		return this.running;
	}

	Gauge pending() {
		return this.pending;
	}

	AmountGauge cpuRequested() {
		return this.cpuRequested;
	}

	AmountGauge memoryRequested() {
		return this.memoryRequested;
	}

	AmountGauge cpuAllocatable() {
		return this.cpuAllocatable;
	}

	AmountGauge memoryAllocatable() {
		return this.memoryAllocatable;
	}

	/**
	 * Integrates every gauge up to the end of the run.
	 * @param end the run's end
	 */
	void finish(double end) {
		this.nodes.finish(end);
		this.provisioning.finish(end);
		this.running.finish(end);
		this.pending.finish(end);
		this.cpuRequested.finish(end);
		this.memoryRequested.finish(end);
		this.cpuAllocatable.finish(end);
		this.memoryAllocatable.finish(end);
	}

}
