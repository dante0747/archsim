package io.archsim.engine.host;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.document.HostSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Each part's CPU speed on the design's host or its own hyperthreads: the Python engine's
 * values.
 */
class HostSpeedsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HostSpec HOST = new HostSpec(4, 2, 0.6, 0.8, 0.5, 0.2);

	@Test
	void onHost_partsSharingTheHost_allRunAtItsSpeed() {
		Map<String, PartWork> parts = new LinkedHashMap<>();
		parts.put("api", work("service", """
				{"serviceTimeMs": {"kind": "constant", "value": 4}, "replicas": 2, "cpuShare": 0.5,
				 "serviceTimeByLoad": [{"rps": 100, "factor": 1.0}, {"rps": 400, "factor": 0.5}]}""", 150, 50));
		parts.put("db", work("database", """
				{"readServiceTimeMs": {"kind": "constant", "value": 2},
				 "writeServiceTimeMs": {"kind": "constant", "value": 4},
				 "readReplicas": 1, "shards": 2}""", 200, 100));
		parts.put("cache",
				work("cache", "{\"serviceTimeMs\": {\"kind\": \"constant\", \"value\": 0.5}, \"shards\": 2}", 400, 0));

		Map<String, Double> speeds = HostSpeeds.onHost(HOST, parts, 200);

		assertThat(speeds).containsOnlyKeys("api", "db", "cache");
		assertThat(speeds.values())
			.allSatisfy((speed) -> assertThat(speed).isCloseTo(0.9841101010090327, within(1e-9)));
	}

	@Test
	void onHost_littleWork_leavesEveryPartAtFullSpeed() {
		Map<String, PartWork> parts = Map.of("cache",
				work("cache", "{\"serviceTimeMs\": {\"kind\": \"constant\", \"value\": 0.01}}", 10, 0));

		assertThat(HostSpeeds.onHost(new HostSpec(8, 2, 0.6, 1.0, 0, 0), parts, 10)).isEmpty();
	}

	@Test
	void onOwnThreads_aBusyPartWithHyperthreads_runsAtTheirSpeed() {
		Map<String, Double> speeds = HostSpeeds.onOwnThreads(Map.of("api", runWork("""
				{"cpuCores": 4, "siblingSpeed": 0.6, "serviceTimeMs": {"kind": "constant", "value": 10},
				 "replicas": 1}""", 240, 60)), Map.of("api", 0.6));

		assertThat(speeds).containsOnlyKeys("api");
		assertThat(speeds.get("api")).isCloseTo(0.009266409266409266, within(1e-15));
	}

	@Test
	void onOwnThreads_aPartWithOneVcpu_hasNoSiblingThreads() {
		Map<String, PartWork> parts = Map.of("api", runWork("""
				{"cpuCores": 1, "serviceTimeMs": {"kind": "constant", "value": 10}, "replicas": 1}""", 240, 60));

		assertThat(HostSpeeds.onOwnThreads(parts, Map.of("api", 0.6))).isEmpty();
	}

	@Test
	void onOwnThreads_aPartWithoutASiblingSpeed_runsAtFullSpeed() {
		Map<String, PartWork> parts = Map.of("api", runWork("""
				{"cpuCores": 4, "serviceTimeMs": {"kind": "constant", "value": 10}, "replicas": 1}""", 240, 60));

		assertThat(HostSpeeds.onOwnThreads(parts, Map.of())).isEmpty();
	}

	@Test
	void onOwnThreads_aSiblingSpeedOfOne_runsAtFullSpeed() {
		Map<String, PartWork> parts = Map.of("api", runWork("""
				{"cpuCores": 4, "serviceTimeMs": {"kind": "constant", "value": 10}, "replicas": 1}""", 240, 60));

		assertThat(HostSpeeds.onOwnThreads(parts, Map.of("api", 1.0))).isEmpty();
	}

	@Test
	void onOwnThreads_littleWork_leavesThePartAtFullSpeed() {
		Map<String, PartWork> parts = Map.of("api", runWork("""
				{"cpuCores": 4, "serviceTimeMs": {"kind": "constant", "value": 1}, "replicas": 1}""", 24, 6));

		assertThat(HostSpeeds.onOwnThreads(parts, Map.of("api", 0.6))).isEmpty();
	}

	private static PartWork runWork(String config, double read, double write) {
		return PartWork.withCpuSize("service", LenientSettings.of(JSON.readTree(config)), new NodeLoad(read, write))
			.orElseThrow();
	}

	private static PartWork work(String type, String config, double read, double write) {
		return PartWork.forSizing(type, LenientSettings.of(JSON.readTree(config)), new NodeLoad(read, write))
			.orElseThrow();
	}

}
