package io.archsim.engine.estimate;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.NodeLoad;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.database.DatabaseConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How the estimate reads a database's commit and a cache's commands, as a run models
 * them. A write waits for the log's flush on its connection, and no core works on it
 * ({@code Operation} and {@code LogFlusher} of the database component). A cache's
 * commands spread over its shards, each with its own cores, and a read-through cache runs
 * one more command to fill a key after a miss ({@code CacheShards} and
 * {@code CacheRuntime} of the cache component). A part on its own hyperthread pairs fills
 * them where every vCPU is busy at the sibling speed, at whatever speed they run at the
 * peak (ADR-0091).
 */
class PartShapesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double EXACT = 1e-12;

	/** 300 reads and 100 writes a second. */
	private static final NodeLoad LOAD = new NodeLoad(300.0, 100.0);

	/** The reads a second a cache misses and asks its data store for. */
	private static final double MISSED = 120.0;

	/**
	 * Reads of 1 ms and writes of 4 ms, half of each on one of 2 cores, and a log flush
	 * of 6 ms.
	 */
	private static final String FLUSHING = """
			{"technology": "postgresql", "cpuCores": 2, "cpuShare": 0.5, "maxConnections": 100,
			 "readServiceTimeMs": {"kind": "constant", "value": 1},
			 "writeServiceTimeMs": {"kind": "constant", "value": 4},
			 "commitFlushMs": {"kind": "constant", "value": 6}}""";

	/** Commands of 0.5 ms on 3 shards of 2 cores each, filling a key after a miss. */
	private static final String SHARDED_CACHE = """
			{"technology": "redis", "shards": 3, "cpuCores": 2, "populateOnMiss": true,
			 "serviceTimeMs": {"kind": "constant", "value": 0.5}}""";

	/** The same cache, leaving a key it missed unfilled. */
	private static final String UNFILLED_CACHE = SHARDED_CACHE.replace("\"populateOnMiss\": true",
			"\"populateOnMiss\": false");

	@Test
	void of_aDatabaseThatFlushesCommits_countsItsQueriesAloneAsCpu() {
		PartShape shape = database(FLUSHING);

		assertThat(shape.cpu()).isCloseTo((300 * 1.0 + 100 * 4.0) * 0.5 / 1000 / 2, within(EXACT));
	}

	@Test
	void of_aDatabaseThatFlushesCommits_keepsAWriteWaitingForTheFlush() {
		PartShape shape = database(FLUSHING);

		assertThat(shape.own(Op.WRITE).mean()).isCloseTo(4.0 + 6.0, within(EXACT));
	}

	@Test
	void of_aDatabaseThatFlushesCommits_leavesAReadItsQuery() {
		PartShape shape = database(FLUSHING);

		assertThat(shape.own(Op.READ).mean()).isCloseTo(1.0, within(EXACT));
	}

	@Test
	void of_aDatabaseThatDoesNotFlushCommits_leavesAWriteItsQuery() {
		PartShape shape = database(FLUSHING.replace("\"postgresql\"", "\"postgresql\", \"commitFlush\": \"off\""));

		assertThat(shape.own(Op.WRITE).mean()).isCloseTo(4.0, within(EXACT));
	}

	@Test
	void slowedTo_aDatabaseThatFlushesCommits_slowsAWritesQueryAndNotItsFlush() {
		PartShape slowed = database(FLUSHING).slowedTo(0.5, 0.5);

		// half of the 4 ms query on a core at half speed, the other half and the flush as
		// they were
		assertThat(slowed.own(Op.WRITE).mean()).isCloseTo(2.0 / 0.5 + 2.0 + 6.0, within(EXACT));
	}

	@Test
	void slowedTo_aDatabaseThatFlushesCommits_keepsItsQueriesAloneOnItsCores() {
		PartShape slowed = database(FLUSHING).slowedTo(0.5, 0.5);

		assertThat(slowed.cpu()).isCloseTo((300 * 1.0 + 100 * 4.0) * 0.5 / 1000 / 2 / 0.5, within(EXACT));
	}

	@Test
	void of_aShardedCache_spreadsItsCommandsOverEveryShardsCores() {
		PartShape shape = cache(UNFILLED_CACHE, MISSED);

		assertThat(shape.cpu()).isCloseTo(400 * 0.5 / 1000 / (2 * 3), within(EXACT));
	}

	@Test
	void of_aReadThroughCache_countsTheCommandThatFillsAMissedKeyOnItsCores() {
		PartShape shape = cache(SHARDED_CACHE, MISSED);

		assertThat(shape.cpu()).isCloseTo((400 + 120) * 0.5 / 1000 / (2 * 3), within(EXACT));
	}

	@Test
	void of_aReadThroughCache_fillsAKeyForEachReadItMissed() {
		PartShape shape = cache(SHARDED_CACHE, MISSED);

		assertThat(shape.fills()).isEqualTo(MISSED);
	}

	@Test
	void of_aCacheThatDoesNotFillMissedKeys_runsNoMoreCommands() {
		PartShape shape = cache(UNFILLED_CACHE, MISSED);

		assertThat(shape.fills()).isZero();
	}

	@Test
	void slowedTo_aReadThroughCache_keepsItsRequestsAndFillsOnItsSlowedCores() {
		PartShape slowed = cache(SHARDED_CACHE, MISSED).slowedTo(0.5, 1.0);

		assertThat(slowed.cpu()).isCloseTo((400 + 120) * 0.5 / 1000 / (2 * 3) / 0.5, within(EXACT));
	}

	@Test
	void slowedTo_aReadThroughCache_keepsFillingTheKeysItMissed() {
		PartShape slowed = cache(SHARDED_CACHE, MISSED).slowedTo(0.5, 1.0);

		assertThat(slowed.fills()).isEqualTo(MISSED);
	}

	@Test
	void onPairsAt_aPartWithACpuSize_takesItsShareAtTheSiblingSpeed() {
		PartShape paired = database(FLUSHING).onPairsAt(0.5);

		assertThat(paired.cpuAtSiblingSpeed()).isCloseTo(0.175 / 0.5, within(EXACT));
	}

	@Test
	void slowedTo_aPartOnItsOwnPairs_keepsItsShareAtTheSiblingSpeed() {
		PartShape slowed = database(FLUSHING).onPairsAt(0.5).slowedTo(0.8, 0.5);

		assertThat(slowed.cpuAtSiblingSpeed()).isCloseTo(0.175 / 0.5, within(EXACT));
	}

	@Test
	void cpuLoad_aPartOnItsOwnPairsSlowedLessThanItsSiblingSpeed_isItsShareAtTheSiblingSpeed() {
		PartShape slowed = database(FLUSHING).onPairsAt(0.5).slowedTo(0.8, 0.5);

		assertThat(slowed.cpuLoad()).isCloseTo(0.175 / 0.5, within(EXACT));
	}

	@Test
	void cpuLoad_aPartOnWholeCores_isItsShareInUse() {
		PartShape shape = database(FLUSHING);

		assertThat(shape.cpuLoad()).isEqualTo(shape.cpu());
	}

	private static PartShape database(String database) {
		DatabaseConfig config = RecordCodec.read((ObjectNode) JSON.readTree(database), DatabaseConfig.class);
		return PartShapes.of(config, LOAD, 0.0).orElseThrow();
	}

	private static PartShape cache(String cache, double missed) {
		CacheConfig config = RecordCodec.read((ObjectNode) JSON.readTree(cache), CacheConfig.class);
		return PartShapes.of(config, LOAD, missed).orElseThrow();
	}

}
