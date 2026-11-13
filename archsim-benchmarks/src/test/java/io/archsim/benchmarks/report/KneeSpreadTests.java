package io.archsim.benchmarks.report;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KneeSpreadTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void of_noRuns_isEmpty() {
		assertThat(KneeSpread.of(List.of())).isEmpty();
	}

	@Test
	void of_oneRunWithAKnee_isEmpty() {
		assertThat(KneeSpread.of(List.of(run(1000.0)))).isEmpty();
	}

	@Test
	void of_aKneeAboveTheLadder_isNoKnee() {
		assertThat(KneeSpread.of(List.of(above(), run(1000.0)))).isEmpty();
	}

	@Test
	void of_twoKnees_isTheirRangeAndHalfItOverItsMiddle() {
		JsonNode spread = KneeSpread.of(List.of(run(3000.0), above(), run(1000.0))).orElseThrow();

		assertThat(spread.get("runs").intValue()).isEqualTo(2);
		assertThat(spread.get("minRps").doubleValue()).isEqualTo(1000.0);
		assertThat(spread.get("maxRps").doubleValue()).isEqualTo(3000.0);
		assertThat(spread.get("plusMinus").doubleValue()).isCloseTo(0.5, within(1e-12));
	}

	@Test
	void of_twoKnees_saysSoInTheTablesWords() {
		JsonNode spread = KneeSpread.of(List.of(run(3000.0), above(), run(1000.0))).orElseThrow();

		assertThat(spread.get("text").stringValue()).isEqualTo("1,000 to 3,000 req/s");
		assertThat(spread.get("plusMinusText").stringValue()).isEqualTo("±50%");
	}

	// another machine's knee is not run-to-run noise
	@Test
	void of_aRunOnAnotherMachine_isLeftOut() {
		JsonNode spread = KneeSpread.of(List.of(run(9000.0).put("machine", "b"), run(1000.0), run(1200.0)))
			.orElseThrow();

		assertThat(spread.get("maxRps").doubleValue()).isEqualTo(1200.0);
	}

	@Test
	void of_aRunOnAnotherOsBuild_isLeftOut() {
		assertThat(KneeSpread.of(List.of(run(900.0).put("os", "old"), run(2000.0).put("os", "new")))).isEmpty();
	}

	// runs before 31 Oct 2026 have no power mode recorded
	@Test
	void of_aRunUnderAnotherPowerMode_isLeftOut() {
		List<JsonNode> runs = List.of(run(4800.0), run(7000.0).put("powerMode", "Best performance"),
				run(7000.0).put("powerMode", "Best performance"));

		JsonNode spread = KneeSpread.of(runs).orElseThrow();

		assertThat(spread.get("runs").intValue()).isEqualTo(2);
		assertThat(spread.get("minRps").doubleValue()).isEqualTo(7000.0);
	}

	// Kafka's broker healthcheck held its knee down until 31 Oct
	@Test
	void of_aRunBeforeAHarnessFix_isLeftOut() {
		List<JsonNode> runs = List.of(run(2100.0).put("harnessCaveat", true), run(2700.0), run(2800.0));

		JsonNode spread = KneeSpread.of(runs).orElseThrow();

		assertThat(spread.get("runs").intValue()).isEqualTo(2);
		assertThat(spread.get("minRps").doubleValue()).isEqualTo(2700.0);
	}

	@Test
	void of_disturbedRuns_areLeftOut() {
		JsonNode spread = KneeSpread.of(RepeatabilityTests.History.withTwoDisturbed()).orElseThrow();

		assertThat(spread.get("runs").intValue()).isEqualTo(2);
		assertThat(spread.get("disturbed").intValue()).isEqualTo(2);
		assertThat(spread.get("minRps").doubleValue()).isEqualTo(4160.0);
	}

	private static ObjectNode run(@Nullable Double knee) {
		ObjectNode run = NODES.objectNode().put("dir", "latest").put("protocol", "v4");
		run.putNull("calibratedAt");
		run.put("machine", "a").put("os", "x");
		ObjectNode measured = run.putObject("measuredKnee");
		if (knee == null) {
			measured.putNull("kneeRps").put("bound", "above");
		}
		else {
			measured.put("kneeRps", knee).put("bound", "interpolated");
		}
		return run;
	}

	private static ObjectNode above() {
		return run(null);
	}

}
