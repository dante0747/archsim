package io.archsim.model.component.kubernetes;

import io.archsim.model.component.service.ServiceConfig;

/**
 * What each pod of a service on a cluster asks of a node: the service's CPU and memory,
 * or the cluster's defaults for what the service does not set.
 *
 * @param cpu the vCPU it requests
 * @param memoryGib the memory it requests, in GiB
 */
public record PodRequest(double cpu, double memoryGib) {

	/**
	 * What each pod of a service requests on a cluster.
	 * @param service the service's settings
	 * @param cluster the cluster's settings
	 * @return the request
	 */
	public static PodRequest of(ServiceConfig service, ClusterConfig cluster) {
		Double cpu = service.cpuCores();
		Double memoryGib = service.memoryGib();
		return new PodRequest((cpu != null) ? cpu : cluster.defaultCpuRequest(),
				(memoryGib != null) ? memoryGib : cluster.defaultMemoryRequestGib());
	}

}
