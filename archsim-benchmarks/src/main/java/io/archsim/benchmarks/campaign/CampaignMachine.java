package io.archsim.benchmarks.campaign;

import java.util.Map;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.ProcessRunner;

/**
 * The machine a campaign runs on: the repository it writes its results to, the harness's
 * environment, what runs its commands, and its log.
 *
 * @param layout the repository: its results, its compose file and its stacks' cpusets
 * @param environment the variables the harness started with, which may name the CPUs
 * @param processes what runs Docker and the operating system's tools
 * @param log the log
 */
public record CampaignMachine(RepositoryLayout layout, Map<String, String> environment, ProcessRunner processes,
		HarnessLog log) {

	/**
	 * Copies the environment.
	 */
	public CampaignMachine {
		environment = Map.copyOf(environment);
	}

}
