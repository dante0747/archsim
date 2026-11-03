package io.archsim.model.component.kubernetes;

/**
 * What each setting of a Kubernetes cluster means, as the editor's inspector and the
 * component catalog show it.
 */
final class Descriptions {

	static final String CLUSTER_AUTOSCALER_GROUP = "Cluster autoscaler";

	static final String COST_GROUP = "Cost";

	static final String NODE_POOL_GROUP = "Node pool";

	static final String PODS_GROUP = "Pods";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TECHNOLOGY = "Kubernetes distribution or managed service. Presets fill in typical node sizes "
			+ "and provisioning times.";

	static final String NODES = "Nodes (worker VMs) running when the simulation starts.";

	static final String NODE_VCPU = "vCPU per node.";

	static final String NODE_MEMORY_GIB = "Memory per node (GiB).";

	static final String ALLOCATABLE_SHARE = "Share of each node's CPU and memory left for pods after the operating "
			+ "system, kubelet and system daemons reserve theirs.";

	static final String CLUSTER_AUTOSCALER = "Add nodes for Pending pods and remove nodes left empty (Kubernetes "
			+ "cluster autoscaler).";

	static final String MIN_NODES = "Fewest nodes the cluster autoscaler keeps.";

	static final String MAX_NODES = "Most nodes the cluster autoscaler may run.";

	static final String NODE_PROVISION_S = "Time from the decision to add a node until it is ready for pods (VM boot, "
			+ "join, CNI) (s).";

	static final String SCAN_INTERVAL_S = "How often the cluster autoscaler looks for Pending pods (s). The "
			+ "autoscaler's default is 10 s.";

	static final String SCALE_DOWN_UNNEEDED_S = "Time a node must stay empty before it is removed (s). The "
			+ "autoscaler's default is 10 minutes.";

	static final String IMAGE_PULL_S = "Time to pull a service's container image on a node that has not run it yet "
			+ "(s).";

	static final String DEFAULT_CPU_REQUEST = "CPU request of pods whose service sets no CPU (vCPU).";

	static final String DEFAULT_MEMORY_REQUEST_GIB = "Memory request of pods whose service sets no memory (GiB).";

	static final String COST_PER_HOUR = "Price of one node per hour, in USD; services on the cluster are paid for "
			+ "through their nodes. Illustrative default.";

	private Descriptions() {
	}

}
