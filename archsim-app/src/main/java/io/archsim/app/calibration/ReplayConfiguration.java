package io.archsim.app.calibration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.archsim.engine.calibration.ReplaySimulator;
import io.archsim.engine.simulator.Simulator;

/**
 * Where a calibration's replay runs: on the application's simulator, on the pool of
 * replay threads (ADR-0054), which closes with the application.
 */
@Configuration(proxyBeanMethods = false)
class ReplayConfiguration {

	@Bean
	ReplayPool replaySimulator(Simulator simulator) {
		return new ReplayPool(ReplaySimulator.of(simulator),
				ReplayPool.threadsFor(Runtime.getRuntime().availableProcessors()));
	}

}
