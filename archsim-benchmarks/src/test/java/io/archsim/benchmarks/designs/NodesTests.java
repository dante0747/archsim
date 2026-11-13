package io.archsim.benchmarks.designs;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.experiments.ServiceRuntime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service node carries the GC and the load curve the service under test was calibrated
 * with; chain's API behind the gateway keeps neither, and without them the node is the
 * one engine 0.14.0 predicted from.
 */
class NodesTests {

	private static final ServiceRuntime NODE = Experiments.standard().runtime(Experiments.standard().get("pg-mixed"));

	private static final String CALIBRATED = """
			{"serviceTimeMs": {"kind": "constant", "value": 0.2}, "gcPauseMs": 12.0, "gcEveryRequests": 9000,
			 "serviceTimeByLoad": [{"rps": 50, "factor": 3.0}, {"rps": 1600, "factor": 1.0}]}""";

	@Test
	void service_theServiceUnderTest_carriesItsGcAndItsCurve() {
		ObjectNode values = JsonText.object(CALIBRATED);

		JsonNode config = Nodes.service(values, new Nodes.Service("api", NODE, "serviceTimeMs", "Node.js", "service"))
			.get("config");

		assertThat(List.of(config.get("gcPauseMs"), config.get("gcEveryRequests"), config.get("serviceTimeByLoad")))
			.containsExactly(values.get("gcPauseMs"), values.get("gcEveryRequests"), values.get("serviceTimeByLoad"));
	}

	// they were measured on the gateway
	@Test
	void service_chainsApi_keepsNeitherTheGcNorTheCurve() {
		ObjectNode values = JsonText.object(CALIBRATED);
		values.set("apiServiceTimeMs", values.get("serviceTimeMs"));

		JsonNode config = Nodes
			.service(values, new Nodes.Service("api", NODE, "apiServiceTimeMs", "Node.js API", "api"))
			.get("config");

		assertThat(config.get("gcPauseMs").intValue()).isZero();
		assertThat(config.has("serviceTimeByLoad")).isFalse();
	}

	@Test
	void service_withoutGcCurveOrMemory_isTheNodeEngine0140PredictedFrom() {
		JsonNode config = Nodes
			.service(JsonText.object("{\"serviceTimeMs\": {\"kind\": \"constant\", \"value\": 0.2}}"),
					new Nodes.Service("api", NODE, "serviceTimeMs", "Node.js", "service"))
			.get("config");

		assertThat(config.get("gcPauseMs").intValue()).isZero();
		assertThat(config.propertyNames()).doesNotContain("gcEveryRequests", "serviceTimeByLoad", "memoryBaselineMb",
				"memoryPerRpsMb", "memoryPerRequestMb");
	}

}
