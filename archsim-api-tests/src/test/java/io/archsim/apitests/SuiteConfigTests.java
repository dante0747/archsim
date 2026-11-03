package io.archsim.apitests;

import java.net.URI;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuiteConfigTests {

	@Test
	void from_urlAndJdbcProperties_readsThemByProfile() {
		SuiteConfig config = SuiteConfig
			.from(properties("archsim.api.url.local", "http://127.0.0.1:8000/", "archsim.api.url.small-runs",
					" http://h:1 ", "archsim.api.jdbc.local", "jdbc:postgresql://127.0.0.1:5432/archsim"));
		assertThat(config.start()).isNull();
		assertThat(config.urls()).containsEntry(Profile.LOCAL, URI.create("http://127.0.0.1:8000"))
			.containsEntry(Profile.SMALL_RUNS, URI.create("http://h:1"))
			.doesNotContainKey(Profile.HOSTED);
		assertThat(config.jdbcUrls()).containsOnlyKeys(Profile.LOCAL);
	}

	@Test
	void from_startingServers_hasDefaults() {
		SuiteConfig config = SuiteConfig.from(properties("archsim.api.start", "python"));
		assertThat(config.start()).isEqualTo(StartableApi.PYTHON);
		assertThat(config.basePort()).isEqualTo(8101);
		assertThat(config.fakeGitHubUrl()).isEqualTo(URI.create("http://127.0.0.1:8190"));
		assertThat(config.postgresUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/archsim");
		assertThat(Profile.HOSTED.settings(config)).containsEntry("ARCHSIM_AUTH_MODE", "oauth")
			.containsEntry("ARCHSIM_GITHUB_URL", "http://127.0.0.1:8190");
	}

	@Test
	void from_missingRepository_fails() {
		Properties suiteProperties = new Properties();
		suiteProperties.setProperty("archsim.repoRoot", "does-not-exist");
		assertThatThrownBy(() -> SuiteConfig.from(suiteProperties)).hasMessageContaining("archsim.repoRoot");
	}

	@Test
	void portOffset_ofEveryProfile_isDistinct() {
		assertThat(Arrays.stream(Profile.values()).map(Profile::portOffset)).doesNotHaveDuplicates();
	}

	private static Properties properties(String... namesAndValues) {
		Properties suiteProperties = new Properties();
		suiteProperties.setProperty("archsim.repoRoot", Examples.repoRoot().toString());
		for (int i = 0; i < namesAndValues.length; i += 2) {
			suiteProperties.setProperty(namesAndValues[i], namesAndValues[i + 1]);
		}
		return suiteProperties;
	}

}
