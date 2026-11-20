package io.archsim.benchmarks;

import java.util.Map;

import io.archsim.benchmarks.docker.HttpServices;
import io.archsim.benchmarks.docker.SystemProcesses;
import io.archsim.benchmarks.docker.Ticker;

/**
 * What a test's commands work with: this machine's Docker, services and clock, but an
 * environment of their own, so that a variable left in a developer's shell cannot change
 * them, and the range fit's cache in the repository the test made. By default the
 * environment names the machine the committed contention checks measured
 * ({@code BENCH_MACHINE}), so that the designs' host machine does not depend on the CPU
 * the tests run on.
 */
public final class Setups {

	/** The laptop the committed contention checks measured. */
	public static final String CHECKED_MACHINE = "i7-1165g7";

	private Setups() {
	}

	/**
	 * A setup on this machine.
	 * @param layout the repository
	 * @param log the log
	 * @return the setup
	 */
	public static HarnessSetup onThisMachine(RepositoryLayout layout, HarnessLog log) {
		return onThisMachine(layout, log, Map.of("BENCH_MACHINE", CHECKED_MACHINE));
	}

	/**
	 * A setup on this machine with the variables a test sets.
	 * @param layout the repository
	 * @param log the log
	 * @param environment the variables
	 * @return the setup
	 */
	public static HarnessSetup onThisMachine(RepositoryLayout layout, HarnessLog log, Map<String, String> environment) {
		return new HarnessSetup(layout, log, layout.root().resolve("range-fit-cache.json"), environment,
				new SystemProcesses(), new HttpServices(), Ticker.system());
	}

}
