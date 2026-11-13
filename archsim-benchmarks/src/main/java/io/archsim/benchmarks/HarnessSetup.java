package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.docker.Cgroups;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.HttpServices;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ProcessRunner;
import io.archsim.benchmarks.docker.ServiceHttp;
import io.archsim.benchmarks.docker.StackLifecycle;
import io.archsim.benchmarks.docker.SystemProcesses;
import io.archsim.benchmarks.docker.Ticker;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.machine.HostSwitch;
import io.archsim.benchmarks.machine.MachineFit;
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

	/** The host's cgroup folder, which Linux hosts with cgroup v2 can read directly. */
	private static final Path HOST_CGROUPS = Path.of("/sys/fs/cgroup");

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
	 * The model inputs' derivation on the engine's simulations: for designs on the host
	 * machine {@code BENCH_HOST=on} switches on ({@link HostSwitch}), or on none.
	 * @param simulations the simulations
	 * @return the derivation
	 */
	public ModelInputs modelInputs(Simulations simulations) {
		return new ModelInputs(simulations, machine().orElse(null));
	}

	/**
	 * The machine the benchmark designs run on, when {@code BENCH_HOST=on} asks for one
	 * ({@link HostSwitch}); the log says so when it asks for one but none can be had.
	 * @return the machine's fit; empty for designs with no host machine
	 */
	public Optional<MachineFit> machine() {
		Optional<MachineFit> machine = HostSwitch.fit(this.environment, this.layout.results());
		if (machine.isEmpty() && HostSwitch.isOn(this.environment)) {
			this.log.line(HostSwitch.VARIABLE + "=on, but no machine to put the designs on (no quiet contention check, "
					+ "or no fit where " + HostSwitch.VARIABLE + "_FIT points): the designs have none");
		}
		return machine;
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
		Compose compose = compose();
		return new Instruments(compose, new Cgroups(compose, HOST_CGROUPS, this.ticker), this.services, this.ticker);
	}

	/**
	 * What starts and stops the stacks.
	 * @return the lifecycle
	 */
	public StackLifecycle stacks() {
		return new StackLifecycle(instruments(), Cpusets.declaredIn(this.layout.compose()), this.environment, this.log);
	}

}
