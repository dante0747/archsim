package io.archsim.benchmarks.baselines;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The CPUs each container of a run could use, the ceiling its utilisation is extrapolated
 * to, and the containers no request waits for.
 *
 * @param cpus how many CPUs each container was pinned to
 * @param behindQueue the containers that work behind a queue
 * @param source where the CPUs come from, in the file's words
 */
record ContainerCpus(Map<String, Integer> cpus, Set<String> behindQueue, String source) {

	/** A run that recorded its cpusets. */
	static final String RECORDED = "environment.json's cpusets";

	/** A run from before the runner recorded them. */
	static final String DECLARED = "compose.yaml's default cpusets: the run recorded none";

	/**
	 * Copies the CPUs and the containers.
	 */
	ContainerCpus {
		cpus = Map.copyOf(cpus);
		behindQueue = Set.copyOf(behindQueue);
	}

	/**
	 * The CPUs of a run's containers: the cpusets its {@code environment.json} recorded,
	 * else the ones {@code compose.yaml} declares, the runner's defaults.
	 * @param environment the run's {@code environment.json}, or None
	 * @param declared the cpusets {@code compose.yaml} declares
	 * @param stack the run's stack
	 * @return the containers' CPUs
	 */
	static ContainerCpus of(JsonNode environment, Cpusets declared, String stack) {
		JsonNode recorded = PythonValues.isNone(environment) ? PythonValues.none()
				: PythonValues.get(environment, "cpusets");
		Map<String, Integer> cpus = new LinkedHashMap<>();
		if (PythonText.truthy(recorded)) {
			recorded.properties()
				.forEach((cpuset) -> cpus.put(cpuset.getKey(), Cpusets.count(cpuset.getValue().stringValue())));
		}
		else {
			declared.of(stack, Map.of()).forEach((container, cpuset) -> cpus.put(container, Cpusets.count(cpuset)));
		}
		return new ContainerCpus(cpus, Set.copyOf(Stacks.standard().behindQueue(stack)),
				PythonText.truthy(recorded) ? RECORDED : DECLARED);
	}

}
