package io.archsim.model.component.kubernetes;

import java.util.List;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Ui;
import io.archsim.model.component.Resources;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;

/**
 * A Kubernetes cluster: its node pool, the cluster autoscaler, and the requests of pods
 * whose service sets no size.
 *
 * @param technology the distribution or managed service
 * @param nodes nodes running when the simulation starts
 * @param nodeVcpu vCPU per node
 * @param nodeMemoryGib memory per node, in GiB
 * @param allocatableShare the share of a node's CPU and memory left for pods
 * @param clusterAutoscaler whether nodes are added for Pending pods and removed when
 * empty
 * @param minNodes the fewest nodes the autoscaler keeps
 * @param maxNodes the most nodes the autoscaler runs
 * @param nodeProvisionS the time until a new node takes pods
 * @param scanIntervalS how often the autoscaler looks for Pending pods
 * @param scaleDownUnneededS how long a node stays empty before it is removed
 * @param imagePullS the time to pull a service's image on a node that has not run it
 * @param defaultCpuRequest the CPU request of pods whose service sets no CPU
 * @param defaultMemoryRequestGib the memory request of pods whose service sets no memory
 * @param costPerHour the price of one node per hour, in USD
 */
public record ClusterConfig(
		@Default("kubernetes") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) ClusterTechnology technology,
		@Default("3") @Description(Descriptions.NODES) @Ui(group = Descriptions.NODE_POOL_GROUP,
				headline = true) @Ge(1) @Le(1000) int nodes,
		@Default("4.0") @Description(Descriptions.NODE_VCPU) @Ui(label = "vCPU per node", unit = "vCPU",
				group = Descriptions.NODE_POOL_GROUP, headline = true,
				step = 1) @Gt(0) @Le(Resources.MAX_CPU_CORES) double nodeVcpu,
		@Default("16.0") @Description(Descriptions.NODE_MEMORY_GIB) @Ui(label = "Memory per node", unit = "GiB",
				group = Descriptions.NODE_POOL_GROUP,
				headline = true) @Gt(0) @Le(Resources.MAX_MEMORY_GIB) double nodeMemoryGib,
		@Default("0.9") @Description(Descriptions.ALLOCATABLE_SHARE) @Ui(group = Descriptions.NODE_POOL_GROUP,
				assumption = true, step = 0.05) @Gt(0) @Le(1) double allocatableShare,
		@Default("true") @Description(Descriptions.CLUSTER_AUTOSCALER) @Ui(
				group = Descriptions.CLUSTER_AUTOSCALER_GROUP) boolean clusterAutoscaler,
		@Default("1") @Description(Descriptions.MIN_NODES) @Ui(group = Descriptions.CLUSTER_AUTOSCALER_GROUP,
				visibleWhen = "clusterAutoscaler=true") @Ge(1) @Le(1000) int minNodes,
		@Default("10") @Description(Descriptions.MAX_NODES) @Ui(group = Descriptions.CLUSTER_AUTOSCALER_GROUP,
				visibleWhen = "clusterAutoscaler=true") @Ge(1) @Le(1000) int maxNodes,
		@Default("120.0") @Description(Descriptions.NODE_PROVISION_S) @Ui(label = "Node provisioning time", unit = "s",
				group = Descriptions.CLUSTER_AUTOSCALER_GROUP, assumption = true,
				visibleWhen = "clusterAutoscaler=true") @Ge(0) @Le(3600) double nodeProvisionS,
		@Default("10.0") @Description(Descriptions.SCAN_INTERVAL_S) @Ui(label = "Scan interval", unit = "s",
				group = Descriptions.CLUSTER_AUTOSCALER_GROUP,
				visibleWhen = "clusterAutoscaler=true") @Gt(0) @Le(600) double scanIntervalS,
		@Default("600.0") @Description(Descriptions.SCALE_DOWN_UNNEEDED_S) @Ui(label = "Scale-down after empty",
				unit = "s", group = Descriptions.CLUSTER_AUTOSCALER_GROUP,
				visibleWhen = "clusterAutoscaler=true") @Ge(0) @Le(86_400) double scaleDownUnneededS,
		@Default("8.0") @Description(Descriptions.IMAGE_PULL_S) @Ui(label = "Image pull time", unit = "s",
				group = Descriptions.PODS_GROUP, assumption = true) @Ge(0) @Le(3600) double imagePullS,
		@Default("0.5") @Description(Descriptions.DEFAULT_CPU_REQUEST) @Ui(label = "Default CPU request", unit = "vCPU",
				group = Descriptions.PODS_GROUP,
				step = 0.25) @Gt(0) @Le(Resources.MAX_CPU_CORES) double defaultCpuRequest,
		@Default("0.5") @Description(Descriptions.DEFAULT_MEMORY_REQUEST_GIB) @Ui(label = "Default memory request",
				unit = "GiB", group = Descriptions.PODS_GROUP,
				step = 0.25) @Gt(0) @Le(Resources.MAX_MEMORY_GIB) double defaultMemoryRequestGib,
		@Default("0.19") @Description(Descriptions.COST_PER_HOUR) @Ui(label = "Cost per node-hour", unit = "$/h",
				group = Descriptions.COST_GROUP, assumption = true) @Ge(0) @Le(100_000) double costPerHour) {

	/**
	 * Upstream Kubernetes is core; the distributions behave the same and differ only in
	 * their presets.
	 */
	static final TechnologyProfiles<ClusterTechnology> PROFILES = TechnologyProfiles.builder(ClusterTechnology.class)
		.with(ClusterTechnology.KUBERNETES, TechnologyProfile.core())
		.with(ClusterTechnology.EKS, TechnologyProfile.experimental())
		.with(ClusterTechnology.GKE, TechnologyProfile.experimental())
		.with(ClusterTechnology.AKS, TechnologyProfile.experimental())
		.with(ClusterTechnology.K3S, TechnologyProfile.experimental())
		.with(ClusterTechnology.OPENSHIFT, TechnologyProfile.experimental())
		.build();

	public TechnologyProfile profile() {
		return PROFILES.of(this.technology);
	}

	/**
	 * vCPU a node offers pods, after the system's reservations.
	 * @return the allocatable vCPU per node
	 */
	public double allocatableCpu() {
		return this.nodeVcpu * this.allocatableShare;
	}

	/**
	 * Memory a node offers pods, after the system's reservations.
	 * @return the allocatable memory per node, in GiB
	 */
	public double allocatableMemoryGib() {
		return this.nodeMemoryGib * this.allocatableShare;
	}

	/**
	 * The nodes the cluster settles at for some pods while the load holds: with its
	 * autoscaler, as many as they need within its bounds; without, its nodes.
	 * @param pods what each pod requests
	 * @return the nodes
	 */
	public int nodesFor(List<PodRequest> pods) {
		if (!this.clusterAutoscaler) {
			return this.nodes;
		}
		int needed = NodePacking.nodesFor(pods, allocatableCpu(), allocatableMemoryGib());
		return Math.min(Math.max(needed, this.minNodes), this.maxNodes);
	}

}
