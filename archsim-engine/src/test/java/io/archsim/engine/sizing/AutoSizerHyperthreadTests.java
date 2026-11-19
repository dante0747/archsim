package io.archsim.engine.sizing;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Auto-sizing a part that sets its sibling speed: on a design without a host machine it
 * is sized for its hyperthreads, two per physical core, which can do less than a core's
 * work each.
 */
class AutoSizerHyperthreadTests {

	/**
	 * What the explanation says of a part whose vCPUs are hyperthreads at a sibling speed
	 * of 0.6.
	 */
	private static final String AS_HYPERTHREADS = "vCPU can do as hyperthreads, each counting as 0.6 of a core "
			+ "(the sibling speed)";

	private static final double TOLERANCE = 1e-9;

	@Test
	void sizes_aServiceThatSetsItsSiblingSpeed_isSizedForWhatItsHyperthreadsCanDo() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"siblingSpeed\": 0.6, \"workersPerReplica\": 16}", "generic", 480));

		assertThat(api.cpuCores()).isEqualTo(8.0);
		assertThat(api.why().get(Spec.CPU_CORES)).isEqualTo(
				"240 req/s per replica at peak (480 over 2) × 10 ms of CPU each = 2.4 cores busy, which is what 4 "
						+ AS_HYPERTHREADS + "; at a 60% target that is 6.67 → 8 vCPU");
	}

	@Test
	void sizes_aServiceThatSetsItsSiblingSpeed_needsItsWholeCoreNeedOverWhatAVcpuCanDo() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"siblingSpeed\": 0.6, \"workersPerReplica\": 16}", "generic", 480));

		assertThat(api.need()).containsEntry(Need.BUSY_CORES, 2.4);
		assertThat(api.need().get(Need.CPU_CORES)).isCloseTo(2.4 / Sizing.TARGET_CPU / 0.6, within(TOLERANCE));
	}

	@Test
	void sizes_aServiceOnHyperthreadsThatNeedsNoMoreThanOneVcpu_isSizedAsOnWholeCores() {
		PartSize onWholeCores = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"siblingSpeed\": 1}", "generic", 100));

		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService("{\"siblingSpeed\": 0.6}", "generic", 100));

		assertThat(cpuOf(api)).isEqualTo(cpuOf(onWholeCores)).first().isEqualTo(1.0);
	}

	@Test
	void sizes_aServiceOnHyperthreadsWithFewWorkers_isCappedAtTwoVcpuForEach() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"siblingSpeed\": 0.6, \"workersPerReplica\": 3}", "generic", 500));

		assertThat(api.why().get(Spec.CPU_CORES)).contains("; at a 60% target that is 6.94 → 8 vCPU; capped at 6, "
				+ "the hyperthreads of 3 cores, because 3 workers cannot use more cores (add workers or replicas)");
	}

	@Test
	void sizes_aDatabaseOnHyperthreads_isCappedAtTwoVcpuForEachCoreItsConnectionsKeepBusy() {
		String database = "{\"id\": \"db\", \"type\": \"database\", \"config\": {\"siblingSpeed\": 0.6, "
				+ "\"maxConnections\": 6, \"readServiceTimeMs\": " + SizingDesigns.TEN_MS + ", \"writeServiceTimeMs\": "
				+ SizingDesigns.TEN_MS + "}}";

		PartSize db = SizingDesigns.size("db",
				SizingDesigns.design(SizingDesigns.service("{}") + ", " + database, "generic", 500, "api-db"));

		assertThat(db.why().get(Spec.CPU_CORES)).contains(" = 2.5 cores busy, which is what 4.17 " + AS_HYPERTHREADS
				+ "; at a 60% target that is 6.94 → 8 vCPU; capped at 6, the hyperthreads of 3 cores, because 6 "
				+ "connections × 50% CPU keep at most 3 cores busy (add connections, read replicas or shards)");
	}

	@Test
	void sizes_aCacheOnHyperthreads_isCappedAtTwoVcpuForEachCommandItRunsAtOnce() {
		String cache = "{\"id\": \"cache\", \"type\": \"cache\", \"config\": {\"siblingSpeed\": 0.6, "
				+ "\"technology\": \"memcached\", \"concurrency\": 3, \"serviceTimeMs\": " + SizingDesigns.TEN_MS
				+ "}}";

		PartSize node = SizingDesigns.size("cache",
				SizingDesigns.design(SizingDesigns.service("{}") + ", " + cache, "generic", 250, "api-cache"));

		assertThat(node.why().get(Spec.CPU_CORES)).contains(" = 2.5 cores busy, which is what 4.17 " + AS_HYPERTHREADS
				+ "; at a 60% target that is 6.94 → 8 vCPU; capped at 6, the hyperthreads of 3 "
				+ "cores, because it runs 3 commands at a time (add shards)");
	}

	@Test
	void sizes_aSingleThreadedCacheOnHyperthreads_isSizedAsOnWholeCores() {
		// one command at a time never has two vCPUs busy
		String cache = "{\"id\": \"cache\", \"type\": \"cache\", \"config\": {%s\"serviceTimeMs\": "
				+ SizingDesigns.TEN_MS + "}}";
		PartSize onWholeCores = SizingDesigns.size("cache",
				SizingDesigns.design(SizingDesigns.service("{}") + ", " + cache.formatted("\"siblingSpeed\": 1, "),
						"generic", 90, "api-cache"));

		PartSize node = SizingDesigns.size("cache",
				SizingDesigns.design(SizingDesigns.service("{}") + ", " + cache.formatted("\"siblingSpeed\": 0.6, "),
						"generic", 90, "api-cache"));

		assertThat(cpuOf(node)).isEqualTo(cpuOf(onWholeCores)).first().isEqualTo(1.0);
	}

	// a size's vCPU, how they were worked out and what it needs
	private static List<Object> cpuOf(PartSize size) {
		return List.of(Objects.requireNonNull(size.cpuCores()), size.why(), size.need());
	}

	@Test
	void sizes_aSiblingSpeedOfOne_isSizedOnWholeCores() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"siblingSpeed\": 1.0, \"workersPerReplica\": 16}", "generic", 480));

		assertThat(api.cpuCores()).isEqualTo(4.0);
	}

	@Test
	void sizes_aSiblingSpeedOnADesignWithAHost_isSizedAtTheHostsSpeedAsWithoutIt() {
		PartSize withoutIt = SizingDesigns.size("api",
				SizingDesigns.onABusyHost(SizingDesigns.ofService("{}", "generic", 1000)));

		PartSize api = SizingDesigns.size("api",
				SizingDesigns.onABusyHost(SizingDesigns.ofService("{\"siblingSpeed\": 0.6}", "generic", 1000)));

		assertThat(api).isEqualTo(withoutIt);
	}

	@Test
	void sizes_anInstanceTypeTooSmallForAPartsHyperthreads_givesWayToTheCheapestThatCoversThem() {
		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService(
				"{\"siblingSpeed\": 0.6, \"workersPerReplica\": 16, \"instanceType\": \"c6i.xlarge\"}", "aws", 480));

		assertThat(api.cpuCores()).isEqualTo(8.0);
		assertThat(api.why().get(Spec.CPU_CORES))
			.contains("(c6i.xlarge, the type set on the part, is not in it or is too small)");
	}

}
