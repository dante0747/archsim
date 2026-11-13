package io.archsim.benchmarks.range;

import java.util.List;
import java.util.function.DoubleBinaryOperator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.inputs.ModelDistributions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How much the knee moves with each varied input: its log slope from the knee with the
 * input one step up. The knee searches are stubbed: a knee set by the service time's mean
 * and the commit flush's median.
 */
class SensitivityTests {

	private static final JsonNode INPUTS = JsonText.read(RangeInputsTests.INPUTS);

	@Test
	void jobs_aDesignsInputs_areTheInputsThenEachVariedOneUp() {
		List<JsonNode> jobs = Sensitivity.jobs(INPUTS);

		assertThat(jobs).hasSize(1 + RangeInputs.keys(INPUTS).size());
		assertThat(jobs.getFirst()).isEqualTo(INPUTS);
		assertThat(jobs.get(1).at("/serviceTimeMs/value/median").doubleValue())
			.isCloseTo(0.17 * StrictMath.exp(Sensitivity.STEP), within(1e-4));
	}

	// it halves when the CPU per request doubles
	@Test
	void of_aKneeSetByTheServicesCpuAlone_isMinusOneForItAndZeroForTheRest() {
		ObjectNode sensitivity = sensitivity((serviceMs, flushMs) -> 400.0 / serviceMs);

		assertThat(sensitivity.get("serviceTimeMs").doubleValue()).isCloseTo(-1.0, within(0.06));
		assertThat(sensitivity.properties()).filteredOn((input) -> !input.getKey().equals("serviceTimeMs"))
			.allSatisfy((input) -> assertThat(input.getValue().doubleValue()).isZero());
	}

	@Test
	void of_aKneeSetByTheFlushAndTheServiceTogether_isTheFlushsShareForIt() {
		ObjectNode sensitivity = sensitivity((serviceMs, flushMs) -> 1000.0 / (flushMs + 4 * serviceMs));

		double serviceMs = ModelDistributions.mean(INPUTS.at("/serviceTimeMs/value"));
		assertThat(sensitivity.get("commitFlushMs").doubleValue()).isCloseTo(-0.8 / (0.8 + 4 * serviceMs),
				within(0.06));
	}

	@Test
	void of_aKneeThatIsNone_isZero() {
		ObjectNode sensitivity = of(List.of(0.0, 500.0, 500.0, 500.0, 500.0, 500.0));

		assertThat(sensitivity.properties()).allSatisfy((input) -> assertThat(input.getValue().doubleValue()).isZero());
	}

	private static ObjectNode of(List<Double> knees) {
		return Sensitivity.fromKnees(INPUTS, knees);
	}

	// each search's knee as the inputs give it, whatever knee it starts from, as
	// knee_near's searches do to their resolution
	private static ObjectNode sensitivity(DoubleBinaryOperator kneeOf) {
		List<Double> knees = Sensitivity.jobs(INPUTS)
			.stream()
			.map((inputs) -> kneeOf.applyAsDouble(ModelDistributions.mean(inputs.at("/serviceTimeMs/value")),
					inputs.at("/commitFlushMs/value/median").doubleValue()))
			.toList();
		return Sensitivity.fromKnees(INPUTS, knees);
	}

}
