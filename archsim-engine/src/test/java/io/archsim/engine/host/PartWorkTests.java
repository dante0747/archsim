package io.archsim.engine.host;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.settings.LenientSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The CPU work a part does at a load: the Python engine's values. */
class PartWorkTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void forSizing_aService_isItsWorkAtItsReplicasRate() {
		PartWork work = work("service", """
				{"serviceTimeMs": {"kind": "constant", "value": 4}, "replicas": 2, "cpuShare": 0.5,
				 "serviceTimeByLoad": [{"rps": 100, "factor": 1.0}, {"rps": 400, "factor": 0.5}]}""", 150, 50);

		assertThat(work.busy()).isCloseTo(0.4, within(1e-12));
		assertThat(work.busiest()).isCloseTo(0.2, within(1e-12));
		assertThat(work.cores()).isEqualTo(1.0);
	}

	@Test
	void forSizing_aDatabase_putsTheWritesOnEachShardsPrimary() {
		PartWork work = work("database", """
				{"readServiceTimeMs": {"kind": "constant", "value": 2},
				 "writeServiceTimeMs": {"kind": "constant", "value": 4},
				 "readReplicas": 1, "shards": 2, "cpuCores": 4}""", 200, 100);

		assertThat(work).isEqualTo(new PartWork(0.4, 0.15, 4));
	}

	@Test
	void forSizing_aServerlessFunction_hasNoWorkOnTheHost() {
		assertThat(PartWork.forSizing("service", LenientSettings.of(JSON.readTree("{\"platform\": \"serverless\"}")),
				new NodeLoad(50, 0)))
			.isEmpty();
	}

	private static PartWork work(String type, String config, double read, double write) {
		return PartWork.forSizing(type, LenientSettings.of(JSON.readTree(config)), new NodeLoad(read, write))
			.orElseThrow();
	}

}
