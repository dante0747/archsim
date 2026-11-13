package io.archsim.benchmarks.parity;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

class SpeedRowsTests {

	@Test
	void of_bothEnginesTimed_comparesEachTemplatesMedianRuns() {
		List<ObjectNode> rows = SpeedRows.of(ParityRuns.timed(1.6, 1.5, 1.4, 2.5, 1.5),
				ParityRuns.timed(0.16, 0.15, 0.3, 0.14, 0.15));

		assertThat(rows).containsExactly(JsonText.object("""
				{"template": "queueing-lab", "requests": 24154, "runs": [5, 5], "python": 1.5, "java": 0.15,
				 "speedUp": 10.0, "sameResult": true}"""));
	}

	@Test
	void of_anEvenCountOfRuns_takesTheMiddleTwo() {
		JsonNode row = SpeedRows.of(ParityRuns.timed(1.0, 2.0), ParityRuns.timed(0.1, 0.3)).getFirst();

		assertThat(row.get("python").doubleValue()).isEqualTo(1.5);
		assertThat(row.get("java").doubleValue()).isEqualTo(0.2);
	}

	@Test
	void of_aTemplateJavaDidNotTime_isKeptUnmeasured() {
		JsonNode row = SpeedRows.of(ParityRuns.timed(1.5), JsonText.read("{\"templates\": []}")).getFirst();

		assertThat(row.get("java").isNull()).isTrue();
		assertThat(row.get("sameResult").booleanValue()).isFalse();
	}

}
