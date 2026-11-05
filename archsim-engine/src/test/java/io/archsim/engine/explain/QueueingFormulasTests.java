package io.archsim.engine.explain;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.report.ReportNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queueing formulas behind a part's figures: its arrival rate, and for a part that
 * serves requests in slots, their capacity, utilization, Little's law, the M/M/c wait and
 * the CPU's capacity.
 */
class QueueingFormulasTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String POOL = """
			"arrivals": 12345, "arrivalRps": 1234.5, "meanHoldMs": 10.0, "utilization": 0.7716,
			"meanInSystem": 12.9, "latencyMs": {"mean": 10.5}, "meanServiceMs": 4.2, "meanQueueWaitMs": 0.48,
			"cpuCapacityRps": 2400.0""";

	@Test
	void ofSlots_aPartWithSlots_hasEveryFormulaInTheReportsOrder() {
		List<Formula> formulas = QueueingFormulas.ofSlots(node("\"slots\": 16, " + POOL), 10.0);

		assertThat(formulas).extracting(Formula::id)
			.containsExactly("arrival_rate", "slot_capacity", "utilization", "littles_law", "mmc_wait", "cpu_capacity");
		assertThat(formulas.getFirst().substituted()).isEqualTo("12,345 ÷ 10 s");
		assertThat(formulas.getFirst().result()).isEqualTo(1234.5);
	}

	@Test
	void of_aPartThatDoesNotServeInSlots_hasOnlyItsArrivalRate() {
		assertThat(QueueingFormulas.of(node("\"slots\": 16, " + POOL), 10.0)).extracting(Formula::id)
			.containsExactly("arrival_rate");
	}

	@Test
	void ofSlots_twoAndAHalfSlots_waitsForTwoAsPythonRoundsHalvesToEven() {
		List<Formula> formulas = QueueingFormulas.ofSlots(node("\"slots\": 2.5, " + POOL.replace("1234.5", "100.0")),
				10.0);

		assertThat(formulas).filteredOn((formula) -> formula.id().equals("mmc_wait"))
			.singleElement()
			.extracting(Formula::substituted)
			.asString()
			.startsWith("C(2, 1) = 33.3% chance to wait");
	}

	@Test
	void ofSlots_moreArrivalsThanTheSlotsServe_predictsNoSteadyWait() {
		Formula wait = QueueingFormulas.ofSlots(node("\"slots\": 4, " + POOL), 10.0)
			.stream()
			.filter((formula) -> formula.id().equals("mmc_wait"))
			.findFirst()
			.orElseThrow();

		assertThat(wait.result()).isNull();
		assertThat(wait.substituted()).isEqualTo("1,234 ≥ 4 × 100");
	}

	private static ReportNode node(String summary) {
		return ReportNode.read(JSON.readTree("{\"id\": \"api\", \"label\": \"API\", \"summary\": {" + summary + "}}"));
	}

}
