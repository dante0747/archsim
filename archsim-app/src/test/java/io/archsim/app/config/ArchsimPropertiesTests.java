package io.archsim.app.config;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
	void engineRequests_set_isTheSetting() {
		ArchsimProperties properties = TestProperties.with("max-engine-requests=3");

		assertThat(properties.engineRequests()).isEqualTo(3);
	}

	// the heaps as JDK 25 reports -Xmx1g and -Xmx512m with the serial collector, which a
	// container of the hosting kit's size gets (its 1 GB on two cores, one replay slot,
	// first), the guide's 1,536 MB on four cores, which G1 reports whole in a 1,920 MB
	// container, then round sizes and a larger body cap
	@ParameterizedTest
	@CsvSource({ "1037959168, 1, 5000000, 5", "1610612736, 3, 5000000, 8", "518979584, 1, 5000000, 1",
			"1037959168, 3, 5000000, 3", "536870912, 1, 5000000, 1", "2147483648, 3, 5000000, 12",
			"4294967296, 7, 5000000, 16", "268435456, 1, 5000000, 1", "1073741824, 3, 50000000, 1" })
	void engineRequestsFor_heapReplaysAndBodyCap_isWhatTheHeapHoldsWithinBounds(long heap, int replays, long body,
			int requests) {
		assertThat(ArchsimProperties.engineRequestsFor(heap, replays, body)).isEqualTo(requests);
	}

	// the kit's 1 GB heap on two cores, two, four or eight cores on 512 MB or 1 GB, and a
	// small heap
	@ParameterizedTest
	@CsvSource({ "1037959168, 1, 5000000, 1", "518979584, 1, 5000000, 1", "518979584, 3, 5000000, 1",
			"1037959168, 3, 5000000, 3", "1037959168, 7, 5000000, 5", "268435456, 3, 5000000, 1",
			"4294967296, 7, 5000000, 7" })
	void replaySlotsFor_heapThreadsAndBodyCap_isWhatTheHeapHoldsUpToTheThreads(long heap, int threads, long body,
			int slots) {
		assertThat(ArchsimProperties.replaySlotsFor(heap, threads, body)).isEqualTo(slots);
	}

	@ParameterizedTest
	@CsvSource({ "8, 7", "2, 1", "1, 1" })
	void replayThreadsFor_processors_isOneLessButAtLeastOne(int processors, int threads) {
		assertThat(ArchsimProperties.replayThreadsFor(processors)).isEqualTo(threads);
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
	void publicAddress_withTrailingSlashes_isWithoutThem() {
		assertThat(properties(null, "https://archsim.test//").publicAddress()).contains("https://archsim.test");
	}

	@Test
	void publicAddress_empty_isNone() {
		assertThat(properties(null, "").publicAddress()).isEmpty();
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
	void limitsOn_withSignInAndNoSetting_isTrue() {
		assertThat(TestProperties.with("auth-mode=oauth").limitsOn()).isTrue();
	}

	@Test
	void limitsOn_withoutSignInOrSetting_isFalse() {
		assertThat(TestProperties.with().limitsOn()).isFalse();
	}

	@Test
	void limitsOn_setOff_isFalseEvenWithSignIn() {
		assertThat(TestProperties.with("auth-mode=oauth", "rate-limits=false").limitsOn()).isFalse();
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
