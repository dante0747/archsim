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
 * ArchSim's settings bound from the environment variables the Python API reads, in its
 * formats.
 */
class ArchsimPropertiesBindingTests {

	private static ApplicationContextRunner withEnvironment(Map<String, Object> environment) {
		return new ApplicationContextRunner().withUserConfiguration(Settings.class)
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new SystemEnvironmentPropertySource("environment", environment)));
	}

	@Test
	void bind_pythonEnvironment_readsTheArchsimVariables() {
		Map<String, Object> environment = Map.of("ARCHSIM_DATABASE_URL", "postgresql+psycopg://a:b@db:5432/archsim",
				"ARCHSIM_CORS_ORIGINS", "[\"https://a.test\", \"https://b.test\"]", "ARCHSIM_MAX_BODY_BYTES", "123456");

		withEnvironment(environment).run((context) -> assertThat(context.getBean(ArchsimProperties.class))
			.extracting(ArchsimProperties::databaseUrl, (properties) -> properties.corsOrigins().origins(),
					ArchsimProperties::maxBodyBytes)
			.containsExactly("postgresql+psycopg://a:b@db:5432/archsim", List.of("https://a.test", "https://b.test"),
					123456L));
	}

	@Test
	void bind_trustedProxiesCommaSeparated_areEachEntry() {
		Map<String, Object> environment = Map.of("ARCHSIM_TRUSTED_PROXIES", " 10.0.0.0/8, web ,");

		withEnvironment(environment)
			.run((context) -> assertThat(context.getBean(ArchsimProperties.class).trustedProxies().hosts())
				.containsExactly("10.0.0.0/8", "web"));
	}

	@Test
	void bind_trustedProxiesAsJsonArray_areEachEntry() {
		Map<String, Object> environment = Map.of("ARCHSIM_TRUSTED_PROXIES", "[\"10.0.0.0/8\", \"web\"]");

		withEnvironment(environment)
			.run((context) -> assertThat(context.getBean(ArchsimProperties.class).trustedProxies().hosts())
				.containsExactly("10.0.0.0/8", "web"));
	}

	@Test
	void bind_bodyLimitBelowTheMinimum_failsNamingTheSetting() {
		Map<String, Object> environment = Map.of("ARCHSIM_MAX_BODY_BYTES", "9999");

		withEnvironment(environment)
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("maxBodyBytes"));
	}

	@Test
	void bind_noHistoryDays_keepsTheHistoryAYear() {
		withEnvironment(Map.of())
			.run((context) -> assertThat(context.getBean(ArchsimProperties.class).historyDays()).isEqualTo(365));
	}

	@Test
	void bind_historyDays_isTheVariable() {
		Map<String, Object> environment = Map.of("ARCHSIM_HISTORY_DAYS", "90");

		withEnvironment(environment)
			.run((context) -> assertThat(context.getBean(ArchsimProperties.class).historyDays()).isEqualTo(90));
	}

	@Test
	void bind_historyDaysBelowOne_failsNamingTheSetting() {
		Map<String, Object> environment = Map.of("ARCHSIM_HISTORY_DAYS", "0");

		withEnvironment(environment)
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("historyDays"));
	}

	@Test
	void bind_historyDaysPastACentury_failsNamingTheSetting() {
		Map<String, Object> environment = Map.of("ARCHSIM_HISTORY_DAYS", "36501");

		withEnvironment(environment)
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("historyDays"));
	}

	/** Binds the settings as the application does. */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ArchsimProperties.class)
	static class Settings {

	}

}
