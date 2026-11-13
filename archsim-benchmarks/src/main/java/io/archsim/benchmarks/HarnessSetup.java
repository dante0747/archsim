package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.Map;

import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.HttpServices;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ProcessRunner;
import io.archsim.benchmarks.docker.ServiceHttp;
import io.archsim.benchmarks.docker.StackLifecycle;
import io.archsim.benchmarks.docker.SystemProcesses;
import io.archsim.benchmarks.docker.Ticker;
import io.archsim.benchmarks.range.RangeFit;
import io.archsim.engine.version.EngineVersions;

/**
 * What the harness's commands work with: the repository, the log, the harness's
 * environment, Docker, the stacks' services and the clock, which a test replaces with its
 * own.
 *
 * @param layout the repository
 * @param log the log
 * @param rangeFitCache the file the range fit keeps its finished work in
 * @param environment the variables the harness started with
 * @param processes what runs Docker
 * @param services the stacks' services
 * @param ticker the clock
 */
public record HarnessSetup(RepositoryLayout layout, HarnessLog log, Path rangeFitCache, Map<String, String> environment,
		ProcessRunner processes, ServiceHttp services, Ticker ticker) {

	/**
	 * Copies the environment.
	 */
	public HarnessSetup {
		environment = Map.copyOf(environment);
	}

	/**
	 * This machine: its environment, Docker, the services on their ports and the wall
	 * clock, the range fit's cache in the system's temporary folder.
	 * @param layout the repository
	 * @param log the log
	 * @return the setup
	 */
	public static HarnessSetup of(RepositoryLayout layout, HarnessLog log) {
		return new HarnessSetup(layout, log, RangeFit.cachePath(EngineVersions.ENGINE), System.getenv(),
				new SystemProcesses(), new HttpServices(), Ticker.system());
	}

	/**
	 * The benchmark's stacks.
	 * @return compose with {@code benchmarks/compose.yaml}
	 */
	public Compose compose() {
		return Compose.of(this.processes, this.layout.compose(), this.environment, this.log);
	}

	/**
	 * What drives and reads the stacks.
	 * @return the instruments
	 */
	public Instruments instruments() {
		return new Instruments(compose(), this.services, this.ticker);
	}

	/**
	 * What starts and stops the stacks.
	 * @return the lifecycle
	 */
	public StackLifecycle stacks() {
		return new StackLifecycle(instruments(), Cpusets.declaredIn(this.layout.compose()), this.environment, this.log);
	}

}
