package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The worker role's parts, made only when the role starts, so the API never reads or
 * refuses its settings: who the worker is, the Java simulator its runs simulate on, and
 * what every run shares.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

	/**
	 * Added to a run's wall-clock limit for its hard deadline, as the Python worker did.
	 */
	private static final Duration DEADLINE_MARGIN = Duration.ofSeconds(30);

	@Bean
	WorkerContext workerContext(ArchsimProperties archsim, WorkerProperties timings, Clock clock) {
		return new WorkerContext(WorkerIdentity.ofThisProcess(), archsim.concurrentRuns(),
				new SimulationLimits(archsim.maxRequests(), archsim.maxWallSeconds()), timings, clock);
	}

	@Bean
	RunSimulator runSimulator(ComponentRegistry registry, PriceSnapshots prices) {
		return new Simulator(registry, prices)::run;
	}

	@Bean
	RunWriter runWriter(LeaseRepository leases, WorkerContext context) {
		return new RunWriter(leases, context, Pause.SLEEP);
	}

	@Bean
	RunParts runParts(RunSimulator simulator, RunWriter writer, WorkerContext context, WorkerThreads threads) {
		return new RunParts(simulator, writer, new RunOutcomes(), context, DEADLINE_MARGIN, System::nanoTime,
				threads.runs(), threads.timers());
	}

}
