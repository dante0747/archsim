package io.archsim.benchmarks;

import java.util.Map;

import io.archsim.benchmarks.docker.HttpServices;
import io.archsim.benchmarks.docker.SystemProcesses;
import io.archsim.benchmarks.docker.Ticker;

/**
 * What a test's commands work with: this machine's Docker, services and clock, but an
 * empty environment, so that a variable left in a developer's shell cannot change them,
 * and the range fit's cache in the repository the test made.
 */
public final class Setups {

	private Setups() {
	}

	/**
	 * A setup on this machine.
	 * @param layout the repository
	 * @param log the log
	 * @return the setup
	 */
	public static HarnessSetup onThisMachine(RepositoryLayout layout, HarnessLog log) {
		return new HarnessSetup(layout, log, layout.root().resolve("range-fit-cache.json"), Map.of(),
				new SystemProcesses(), new HttpServices(), Ticker.system());
	}

}
