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

/** Each part's CPU speed on the design's host, for sizing: the Python engine's values. */
class HostSpeedsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HostSpec HOST = new HostSpec(4, 2, 0.6, 0.8, 0.5, 0.2);

	@Test
	void forSizing_partsSharingTheHost_allRunAtItsSpeed() {
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

		Map<String, Double> speeds = HostSpeeds.forSizing(HOST, parts, 200);

		assertThat(speeds).containsOnlyKeys("api", "db", "cache");
		assertThat(speeds.values())
			.allSatisfy((speed) -> assertThat(speed).isCloseTo(0.9841101010090327, within(1e-9)));
	}

	@Test
	void forSizing_littleWork_leavesEveryPartAtFullSpeed() {
		Map<String, PartWork> parts = Map.of("cache",
				work("cache", "{\"serviceTimeMs\": {\"kind\": \"constant\", \"value\": 0.01}}", 10, 0));

		assertThat(HostSpeeds.forSizing(new HostSpec(8, 2, 0.6, 1.0, 0, 0), parts, 10)).isEmpty();
	}

	private static PartWork work(String type, String config, double read, double write) {
		return PartWork.forSizing(type, LenientSettings.of(JSON.readTree(config)), new NodeLoad(read, write))
			.orElseThrow();
	}

}
