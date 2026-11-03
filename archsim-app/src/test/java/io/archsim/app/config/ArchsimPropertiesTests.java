package io.archsim.app.config;

import java.io.File;
import java.nio.file.Path;
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
		ArchsimProperties properties = new ArchsimProperties("postgresql://h/db", null, null,
				new CorsOrigins(List.of()), true, 3, 300, 1, 10_000, null);

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
	void toString_databaseUrlWithPassword_masksIt() {
		ArchsimProperties properties = new ArchsimProperties("postgresql://archsim:s3cret@db/archsim", null, null,
				new CorsOrigins(List.of()), true, null, 300, 1, 10_000, null);

		assertThat(properties.toString()).contains("databaseUrl=postgresql://archsim:***@db/archsim")
			.doesNotContain("s3cret");
	}

	private static ArchsimProperties properties(@Nullable String pricesDir, @Nullable String publicUrl) {
		return new ArchsimProperties("postgresql://h/db", null, pricesDir, new CorsOrigins(List.of()), true, null, 300,
				1, 10_000, publicUrl);
	}

}
