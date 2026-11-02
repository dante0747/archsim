package io.archsim.apitests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SuiteConfigTest {

	private static Properties properties(String... namesAndValues) {
		Properties p = new Properties();
		p.setProperty("archsim.repoRoot", Examples.repoRoot().toString());
		for (int i = 0; i < namesAndValues.length; i += 2) {
			p.setProperty(namesAndValues[i], namesAndValues[i + 1]);
		}
		return p;
	}

	@Test
	void urlsAndDatabasesPerProfile() {
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
	void startingServersHasDefaults() {
		SuiteConfig config = SuiteConfig.from(properties("archsim.api.start", "python"));
		assertThat(config.start()).isEqualTo("python");
		assertThat(config.basePort()).isEqualTo(8101);
		assertThat(config.fakeGitHubUrl()).isEqualTo(URI.create("http://127.0.0.1:8190"));
		assertThat(config.postgresUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/archsim");
		assertThat(Profile.HOSTED.settings(config)).containsEntry("ARCHSIM_AUTH_MODE", "oauth")
			.containsEntry("ARCHSIM_GITHUB_URL", "http://127.0.0.1:8190");
	}

	@Test
	void theRepositoryMustBeFound() {
		Properties p = new Properties();
		p.setProperty("archsim.repoRoot", "does-not-exist");
		assertThatThrownBy(() -> SuiteConfig.from(p)).hasMessageContaining("archsim.repoRoot");
	}

	@Test
	void profilesHaveDistinctPorts() {
		assertThat(java.util.Arrays.stream(Profile.values()).map(Profile::portOffset)).doesNotHaveDuplicates();
	}

}
