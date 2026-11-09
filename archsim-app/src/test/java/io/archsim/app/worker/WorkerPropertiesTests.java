package io.archsim.app.worker;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's timings, bound from the environment variables the Python worker reads,
 * with its defaults and its limits.
 */
class WorkerPropertiesTests {

	private static ApplicationContextRunner withEnvironment(Map<String, Object> environment) {
		return new ApplicationContextRunner().withUserConfiguration(Settings.class)
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("environment", environment)));
	}

	@Test
	void bind_notSet_isThePythonWorkersTimings() {
		withEnvironment(Map.of()).run((context) -> assertThat(context.getBean(WorkerProperties.class))
			.isEqualTo(new WorkerProperties(30.0, 2.0, 10.0, 10.0)));
	}

	@Test
	void bind_pythonEnvironment_readsTheArchsimVariables() {
		Map<String, Object> environment = Map.of("ARCHSIM_LEASE_SECONDS", "60", "ARCHSIM_HEARTBEAT_SECONDS", "5",
				"ARCHSIM_REAP_SECONDS", "20", "ARCHSIM_CANCEL_GRACE_SECONDS", "0");

		withEnvironment(environment).run((context) -> assertThat(context.getBean(WorkerProperties.class))
			.isEqualTo(new WorkerProperties(60.0, 5.0, 20.0, 0.0)));
	}

	@Test
	void bind_heartbeatOverHalfTheLease_failsNamingBoth() {
		Map<String, Object> environment = Map.of("ARCHSIM_LEASE_SECONDS", "10", "ARCHSIM_HEARTBEAT_SECONDS", "6");

		withEnvironment(environment).run((context) -> assertThat(context).getFailure()
			.rootCause()
			.hasMessage("ARCHSIM_HEARTBEAT_SECONDS must be at most half of ARCHSIM_LEASE_SECONDS"));
	}

	@Test
	void bind_leaseBelowASecond_failsNamingTheSetting() {
		Map<String, Object> environment = Map.of("ARCHSIM_LEASE_SECONDS", "0.5", "ARCHSIM_HEARTBEAT_SECONDS", "0.1");

		withEnvironment(environment)
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("leaseSeconds"));
	}

	@Test
	void bind_noHeartbeat_failsNamingTheSetting() {
		Map<String, Object> environment = Map.of("ARCHSIM_HEARTBEAT_SECONDS", "0");

		withEnvironment(environment)
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("heartbeatSeconds"));
	}

	/** Binds the settings as the worker role does. */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(WorkerProperties.class)
	static class Settings {

	}

}
