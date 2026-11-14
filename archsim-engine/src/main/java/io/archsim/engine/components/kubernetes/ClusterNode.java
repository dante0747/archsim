package io.archsim.engine.components.kubernetes;

import java.util.HashSet;
import java.util.Set;

import io.archsim.model.component.kubernetes.NodePacking;
import io.archsim.model.component.kubernetes.PodRequest;

/**
 * One node (a worker VM) of a cluster during a run: the CPU and memory it offers pods,
 * what its pods reserve, the images it has pulled, and since when it has been empty.
 */
public final class ClusterNode {

	private final double cpu;

	private final double memoryGib;

	private final Set<String> images = new HashSet<>();

	private double emptySince;

	private double usedCpu;

	private double usedMemoryGib;

	private int pods;

	/**
	 * An empty node.
	 * @param cpu the vCPU it offers pods
	 * @param memoryGib the memory it offers pods, in GiB
	 * @param emptySince when it joined the cluster
	 */
	ClusterNode(double cpu, double memoryGib, double emptySince) {
		this.cpu = cpu;
		this.memoryGib = memoryGib;
		this.emptySince = emptySince;
	}

	/**
	 * Whether a pod's request fits in what the node has left.
	 * @param request the pod's request
	 * @return {@code true} when both its CPU and its memory fit
	 */
	boolean fits(PodRequest request) {
		return this.cpu - this.usedCpu >= request.cpu() - NodePacking.ROUNDING_TOLERANCE
				&& this.memoryGib - this.usedMemoryGib >= request.memoryGib() - NodePacking.ROUNDING_TOLERANCE;
	}

	/**
	 * The share of the node left, the mean of its CPU's and its memory's: the scheduler
	 * prefers the least allocated node.
	 * @return the share, from 0 to 1
	 */
	double freeShare() {
		return ((this.cpu - this.usedCpu) / this.cpu + (this.memoryGib - this.usedMemoryGib) / this.memoryGib) / 2.0;
	}

	/**
	 * Reserves a pod's request on the node.
	 * @param request the pod's request
	 */
	void bind(PodRequest request) {
		this.usedCpu += request.cpu();
		this.usedMemoryGib += request.memoryGib();
		this.pods++;
	}

	/**
	 * Frees a pod's request on the node; the node is empty from now when it was its last.
	 * @param request the pod's request
	 * @param now the time
	 */
	void release(PodRequest request, double now) {
		this.usedCpu = Math.max(0.0, this.usedCpu - request.cpu());
		this.usedMemoryGib = Math.max(0.0, this.usedMemoryGib - request.memoryGib());
		this.pods = Math.max(0, this.pods - 1);
		if (this.pods == 0) {
			this.emptySince = now;
		}
	}

	/**
	 * Whether the node has had no pod for a while.
	 * @param now the time
	 * @param forMs how long
	 * @return {@code true} when it has been empty at least that long
	 */
	boolean emptyFor(double now, double forMs) {
		return this.pods == 0 && now - this.emptySince >= forMs;
	}

	/**
	 * Keeps a service's image on the node.
	 * @param serviceId the service
	 * @return {@code true} when the node did not have it yet, so a pod pulls it first
	 */
	boolean cacheImage(String serviceId) {
		return this.images.add(serviceId);
	}

}
