package io.archsim.model.component.kubernetes;

import static io.archsim.model.component.Resources.MAX_CPU_CORES;
import static io.archsim.model.component.Resources.MAX_MEMORY_GIB;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import java.util.Map;

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
public record ClusterConfig(@Default("kubernetes") ClusterTechnology technology,
		@Default("3") @Ge(1) @Le(1000) int nodes, @Default("4.0") @Gt(0) @Le(MAX_CPU_CORES) double nodeVcpu,
		@Default("16.0") @Gt(0) @Le(MAX_MEMORY_GIB) double nodeMemoryGib,
		@Default("0.9") @Gt(0) @Le(1) double allocatableShare, @Default("true") boolean clusterAutoscaler,
		@Default("1") @Ge(1) @Le(1000) int minNodes, @Default("10") @Ge(1) @Le(1000) int maxNodes,
		@Default("120.0") @Ge(0) @Le(3600) double nodeProvisionS, @Default("10.0") @Gt(0) @Le(600) double scanIntervalS,
		@Default("600.0") @Ge(0) @Le(86_400) double scaleDownUnneededS,
		@Default("8.0") @Ge(0) @Le(3600) double imagePullS,
		@Default("0.5") @Gt(0) @Le(MAX_CPU_CORES) double defaultCpuRequest,
		@Default("0.5") @Gt(0) @Le(MAX_MEMORY_GIB) double defaultMemoryRequestGib,
		@Default("0.19") @Ge(0) @Le(100_000) double costPerHour) {

	/**
	 * Upstream Kubernetes is core; the distributions behave the same and differ only in
	 * their presets.
	 */
	static final TechnologyProfiles<ClusterTechnology> PROFILES = new TechnologyProfiles<>(ClusterTechnology.class,
			Map.of(ClusterTechnology.KUBERNETES, TechnologyProfile.core(), ClusterTechnology.EKS,
					TechnologyProfile.experimental(), ClusterTechnology.GKE, TechnologyProfile.experimental(),
					ClusterTechnology.AKS, TechnologyProfile.experimental(), ClusterTechnology.K3S,
					TechnologyProfile.experimental(), ClusterTechnology.OPENSHIFT, TechnologyProfile.experimental()));

	public TechnologyProfile profile() {
		return PROFILES.of(technology);
	}

	/** vCPU a node offers pods, after the system's reservations. */
	public double allocatableCpu() {
		return nodeVcpu * allocatableShare;
	}

	/** Memory a node offers pods, in GiB, after the system's reservations. */
	public double allocatableMemoryGib() {
		return nodeMemoryGib * allocatableShare;
	}
}
