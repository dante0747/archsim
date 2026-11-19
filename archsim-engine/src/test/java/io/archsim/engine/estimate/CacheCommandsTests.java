package io.archsim.engine.estimate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.estimate.RecordedEstimates.RecordedEstimate;
import io.archsim.engine.estimate.RecordedEstimates.Source;
import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.NodeLoad;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.document.EdgeSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How far the Java's estimate of a cache's slots and CPU is from the frozen engine's, for
 * every cache of every design {@link RecordedEstimates} reads with the frozen engine's
 * estimate of it. Python counted a cache's requests alone, all of them on one shard's
 * cores (ADR-0092). The Java counts its commands: its requests and, for a read-through
 * cache, one fill for each read it missed, spread over every shard's cores. So Python's
 * slot share over the Java's is the cache's requests over its commands, and its CPU share
 * over the Java's is its shards times that, and nothing else moves them.
 * {@code CacheCommandDepartures} reads the Java's shares as Python's; this holds them to
 * the amount.
 */
class CacheCommandsTests {

	private static final String SLOTS = "slotUtilization";

	private static final String CPU = "cpuUtilization";

	/** How far a share rounded to four decimals may be from the unrounded one. */
	private static final double ROUNDING = 5e-5;

	/** Floating-point noise. */
	private static final double NOISE = 1e-9;

	@TestFactory
	Stream<DynamicTest> estimate_eachRecordedCache_hasPythonsSlotShareOverItsOwnAsItsRequestsOverItsCommands()
			throws IOException {
		return recordedCaches().stream()
			.map((cache) -> DynamicTest.dynamicTest(cache.name(),
					() -> assertThat(cache.python(SLOTS) * cache.commands())
						.isCloseTo(cache.java(SLOTS) * cache.requests(), within(cache.rounding(1)))));
	}

	@TestFactory
	Stream<DynamicTest> estimate_eachRecordedSizedCache_hasPythonsCpuOverItsOwnAsItsShardsTimesRequestsOverCommands()
			throws IOException {
		return recordedCaches().stream()
			.filter((cache) -> cache.pythonPart().path(CPU).isNumber())
			.map((cache) -> DynamicTest.dynamicTest(cache.name(),
					() -> assertThat(cache.python(CPU) * cache.commands()).isCloseTo(
							cache.java(CPU) * cache.requests() * cache.shards(),
							within(cache.rounding(cache.shards())))));
	}

	@Test
	void estimate_theRecordings_holdShardedAndReadThroughCachesWithACpuSize() throws IOException {
		List<Cache> sized = recordedCaches().stream()
			.filter((cache) -> cache.pythonPart().path(CPU).isNumber())
			.toList();

		assertThat(sized).anyMatch((cache) -> cache.shards() > 1)
			.anyMatch((cache) -> cache.commands() > cache.requests());
	}

	@Test
	void estimate_everyKindOfRecordingWithACache_hasCachesToCheck() throws IOException {
		List<Source> checked = recordedCaches().stream().map(Cache::source).distinct().toList();

		assertThat(checked).containsExactlyInAnyOrder(Source.GOLDEN_ESTIMATE, Source.CALIBRATION_REPLAY,
				Source.GOLDEN_RUN, Source.SIMULATION, Source.ESTIMATE_CASE);
	}

	private static List<Cache> recordedCaches() throws IOException {
		List<Cache> caches = new ArrayList<>();
		RecordedEstimates.all().forEach((design) -> caches.addAll(caches(design)));
		return caches;
	}

	// the caches of a design whose slots the frozen engine estimated
	private static List<Cache> caches(RecordedEstimate recorded) {
		LoadEstimate load = recorded.peakLoad();
		EstimateGraph graph = recorded.graph();
		JsonNode java = recorded.javaParts();
		JsonNode python = recorded.pythonParts();
		List<Cache> caches = new ArrayList<>();
		for (int index = 0; index < python.size(); index++) {
			JsonNode part = python.get(index);
			String id = part.path("id").asString();
			if (part.path(SLOTS).isNumber() && graph.config(id).orElse(null) instanceof CacheConfig config) {
				double requests = load.node(id).orElse(NodeLoad.NONE).total();
				double fills = config.populateOnMiss() ? missedReads(graph, load, id) : 0.0;
				caches.add(new Cache(recorded.source(), recorded.source() + " " + recorded.name() + " " + id, part,
						java.get(index), requests, requests + fills, Math.max(1, config.shards())));
			}
		}
		return caches;
	}

	// the reads a cache sends on its miss links, which a read-through cache fills
	private static double missedReads(EstimateGraph graph, LoadEstimate load, String id) {
		double missed = 0.0;
		for (EdgeSpec edge : graph.calls(id)) {
			NodeLoad sent = load.edges().get(edge.id());
			if (CacheSpec.MISS.equals(edge.sourcePort()) && sent != null) {
				missed += sent.read();
			}
		}
		return missed;
	}

	/**
	 * A recorded cache in both estimates, and what its load runs.
	 *
	 * @param source the kind of recording
	 * @param name the recording and the cache
	 * @param pythonPart the frozen engine's estimate of it
	 * @param javaPart the Java's estimate of it
	 * @param requests the requests a second it takes
	 * @param commands the commands a second it runs: its requests and its fills
	 * @param shards its shards
	 */
	private record Cache(Source source, String name, JsonNode pythonPart, JsonNode javaPart, double requests,
			double commands, int shards) {

		double python(String share) {
			return this.pythonPart.path(share).doubleValue();
		}

		double java(String share) {
			return this.javaPart.path(share).doubleValue();
		}

		/**
		 * How far the two sides of a rule may be apart: each share is rounded to four
		 * decimals.
		 * @param spread the shards the Java's share is spread over beyond Python's
		 * @return the tolerance
		 */
		double rounding(int spread) {
			return ROUNDING * (this.commands + this.requests * spread) + NOISE;
		}

	}

}
