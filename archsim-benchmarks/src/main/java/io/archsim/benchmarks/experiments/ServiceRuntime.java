package io.archsim.benchmarks.experiments;

import org.jspecify.annotations.Nullable;

/**
 * How a stack's service runs, as its container runs it and as the model's node models it.
 *
 * @param technology the engine's runtime profile
 * @param label the node's label
 * @param cpuCores the container's CPUs (its cpuset)
 * @param workers the requests served at once (threads or worker processes); {@code null}
 * for an event loop
 * @param image the image, as {@code environment.json} records it
 */
public record ServiceRuntime(String technology, String label, int cpuCores, @Nullable Integer workers, String image) {

	/**
	 * This runtime in a container with other CPUs: the same service, image and workers.
	 * @param cpus the container's CPUs
	 * @return the runtime on those CPUs
	 */
	public ServiceRuntime onCpus(int cpus) {
		return new ServiceRuntime(this.technology, this.label, cpus, this.workers, this.image);
	}

}
