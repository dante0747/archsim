package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.StackLifecycle;

/**
 * What the machine's checks run with.
 *
 * @param instruments what drives and reads the stacks, through Docker
 * @param cpusets the cpusets the compose file declares
 * @param environment the harness's variables, which may name the CPUs
 * @param summaries the folder k6's summaries land in ({@code results/.k6})
 * @param machine the machine's part of a check's file, as {@code environment.json}
 * records it
 * @param log the log
 */
public record CheckTools(Instruments instruments, Cpusets cpusets, Map<String, String> environment, Path summaries,
		Supplier<ObjectNode> machine, HarnessLog log) {

	/**
	 * Copies the variables.
	 */
	public CheckTools {
		environment = Map.copyOf(environment);
	}

	/**
	 * What starts and stops the stacks.
	 * @return the lifecycle
	 */
	StackLifecycle stacks() {
		return new StackLifecycle(this.instruments, this.cpusets, this.environment, this.log);
	}

}
