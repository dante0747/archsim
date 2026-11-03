package io.archsim.app.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calibration settings bound from the environment variables the Python API reads.
 */
class CalibrationPropertiesBindingTests {

	private static ApplicationContextRunner withEnvironment(Map<String, Object> environment) {
		return new ApplicationContextRunner().withUserConfiguration(Settings.class)
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("environment", environment)));
	}

	@Test
	void bind_pythonEnvironment_readsTheCalibrationVariables() {
		Map<String, Object> environment = Map.of("ARCHSIM_OUTBOUND", "allowlist", "ARCHSIM_PROMETHEUS_ALLOWED_HOSTS",
				"prom.internal:9090, other", "ARCHSIM_PROMETHEUS_TIMEOUT_S", "12.5",
				"ARCHSIM_CALIBRATE_MAX_WALL_SECONDS", "30");

		withEnvironment(environment).run((context) -> assertThat(context.getBean(CalibrationProperties.class))
			.extracting(CalibrationProperties::outbound, (properties) -> properties.prometheusAllowedHosts().hosts(),
					CalibrationProperties::prometheusTimeoutS, CalibrationProperties::calibrateMaxWallSeconds)
			.containsExactly(CalibrationProperties.Outbound.ALLOWLIST, List.of("prom.internal:9090", "other"), 12.5,
					30.0));
	}

	@Test
	void bind_nothingSet_hasThePythonDefaults() {
		withEnvironment(Map.of()).run((context) -> assertThat(context.getBean(CalibrationProperties.class))
			.extracting(CalibrationProperties::outbound, (properties) -> properties.prometheusAllowedHosts().hosts(),
					CalibrationProperties::prometheusTimeoutS, CalibrationProperties::prometheusMaxBytes,
					CalibrationProperties::calibrateMaxWallSeconds)
			.containsExactly(null, List.of(), 30.0, 52_428_800L, 60.0));
	}

	@Test
	void bind_timeoutPastTenMinutes_failsNamingTheSetting() {
		withEnvironment(Map.of("ARCHSIM_PROMETHEUS_TIMEOUT_S", "601"))
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("prometheusTimeoutS"));
	}

	/** Binds the settings as the application does. */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CalibrationProperties.class)
	static class Settings {

	}

}
