package io.archsim.model.component.kubernetes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How many empty nodes some pods need, as the Kubernetes cluster autoscaler packs them:
 * first fit, the largest first; a pod no node can hold needs none.
 */
public final class NodePacking {

	/**
	 * Absorbs floating-point error in what a node has left, as the Python engine does.
	 */
	public static final double ROUNDING_TOLERANCE = 1e-9;

	/** Pods are packed the largest first. */
	private static final Comparator<PodRequest> LARGEST_FIRST = Comparator.comparingDouble(PodRequest::cpu)
		.thenComparingDouble(PodRequest::memoryGib)
		.reversed();

	private NodePacking() {
	}

	/**
	 * The empty nodes some pods need.
	 * @param pods what each pod requests
	 * @param nodeCpu the vCPU a node allocates to pods
	 * @param nodeMemoryGib the memory a node allocates to pods, in GiB
	 * @return the nodes
	 */
	public static int nodesFor(List<PodRequest> pods, double nodeCpu, double nodeMemoryGib) {
		List<Room> nodes = new ArrayList<>();
		for (PodRequest pod : pods.stream().sorted(LARGEST_FIRST).toList()) {
			if (!packIntoFirstWithRoom(nodes, pod) && fits(pod, nodeCpu, nodeMemoryGib)) {
				nodes.add(new Room(nodeCpu - pod.cpu(), nodeMemoryGib - pod.memoryGib()));
			}
		}
		return nodes.size();
	}

	// packs a pod into the first node with room for it; false when none has room
	private static boolean packIntoFirstWithRoom(List<Room> nodes, PodRequest pod) {
		for (Room node : nodes) {
			if (node.take(pod)) {
				return true;
			}
		}
		return false;
	}

	private static boolean fits(PodRequest pod, double nodeCpu, double nodeMemoryGib) {
		return pod.cpu() <= nodeCpu + ROUNDING_TOLERANCE && pod.memoryGib() <= nodeMemoryGib + ROUNDING_TOLERANCE;
	}

	/**
	 * What a new node has left after the pods packed into it so far.
	 */
	private static final class Room {

		private double cpu;

		private double memoryGib;

		Room(double cpu, double memoryGib) {
			this.cpu = cpu;
			this.memoryGib = memoryGib;
		}

		/**
		 * Takes a pod when the node has room for it.
		 * @param pod the pod
		 * @return whether it took it
		 */
		boolean take(PodRequest pod) {
			if (this.cpu < pod.cpu() - ROUNDING_TOLERANCE || this.memoryGib < pod.memoryGib() - ROUNDING_TOLERANCE) {
				return false;
			}
			this.cpu -= pod.cpu();
			this.memoryGib -= pod.memoryGib();
			return true;
		}

	}

}
