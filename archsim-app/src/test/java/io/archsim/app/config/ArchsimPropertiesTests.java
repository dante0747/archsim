package io.archsim.app.config;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What ArchSim's settings work out to. */
class ArchsimPropertiesTests {

	@Test
	void concurrentRuns_notSet_isOneLessThanTheProcessorsWithinBounds() {
		int runs = properties(null, null).concurrentRuns();

		assertThat(runs).isBetween(1, ArchsimProperties.MAX_SLOTS);
	}

	@Test
	void concurrentRuns_set_isTheSetting() {
		ArchsimProperties properties = TestProperties.with("max-concurrent-runs=3");

		assertThat(properties.concurrentRuns()).isEqualTo(3);
	}

	@Test
	void priceFolders_pathList_isSplitOnThePlatformsSeparator() {
		ArchsimProperties properties = properties("a" + File.pathSeparator + File.pathSeparator + "b", null);

		assertThat(properties.priceFolders()).containsExactly(Path.of("a"), Path.of("b"));
	}

	@Test
	void servedOverHttps_httpsPublicUrl_isTrue() {
		assertThat(properties(null, "https://archsim.test").servedOverHttps()).isTrue();
	}

	@Test
	void servedOverHttps_httpPublicUrl_isFalse() {
		assertThat(properties(null, "http://localhost:3000").servedOverHttps()).isFalse();
	}

	@Test
	void hosted_oauth_isTrue() {
		assertThat(TestProperties.with("auth-mode=oauth").hosted()).isTrue();
	}

	@Test
	void hosted_byDefault_isFalse() {
		assertThat(TestProperties.with().hosted()).isFalse();
	}

	@Test
	void trustedProxies_notSet_areThisMachine() {
		assertThat(TestProperties.with().trustedProxies().hosts()).containsExactly("127.0.0.1", "::1");
	}

	@Test
	void toString_databaseUrlWithPassword_masksIt() {
		ArchsimProperties properties = TestProperties.with("database-url=postgresql://archsim:s3cret@db/archsim");

		assertThat(properties.toString()).contains("databaseUrl=postgresql://archsim:***@db/archsim")
			.doesNotContain("s3cret");
	}

	private static ArchsimProperties properties(@Nullable String pricesDir, @Nullable String publicUrl) {
		List<String> settings = new ArrayList<>();
		if (pricesDir != null) {
			settings.add("prices-dir=" + pricesDir);
		}
		if (publicUrl != null) {
			settings.add("public-url=" + publicUrl);
		}
		return TestProperties.with(settings.toArray(String[]::new));
	}

}
