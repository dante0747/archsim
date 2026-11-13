package io.archsim.benchmarks.range;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.values.PythonStatistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Each input's variation pooled within experiments, the fallback for inputs not seen
 * varying, and the common correlation between inputs.
 */
class VariationFitTests {

	/** Experiment a's service time and flush move together; b has no flush. */
	private static final String ROWS = """
			[{"experiment": "a", "source": "a1", "scales": {"serviceTimeMs": 0.20, "commitFlushMs": 1.0}},
			 {"experiment": "a", "source": "a2", "scales": {"serviceTimeMs": 0.22, "commitFlushMs": 1.2}},
			 {"experiment": "a", "source": "a3", "scales": {"serviceTimeMs": 0.18, "commitFlushMs": 0.8}},
			 {"experiment": "b", "source": "b1", "scales": {"serviceTimeMs": 0.10}},
			 {"experiment": "b", "source": "b2", "scales": {"serviceTimeMs": 0.12}}]""";

	// the level differs between experiments; only the variation within each counts
	@Test
	void of_twoExperiments_poolsTheVariationWithinEach() {
		ObjectNode fit = VariationFit.of(rows(ROWS));

		List<Double> logsOfA = List.of(Math.log(0.20), Math.log(0.22), Math.log(0.18));
		List<Double> logsOfB = List.of(Math.log(0.10), Math.log(0.12));
		double squares = squares(logsOfA) + squares(logsOfB);
		assertThat(fit.at("/inputs/serviceTimeMs/sigma").doubleValue()).isCloseTo(Math.sqrt(squares / 3), within(1e-4));
		assertThat(fit.at("/inputs/serviceTimeMs/calibrations").intValue()).isEqualTo(5);
		assertThat(fit.at("/inputs/serviceTimeMs/experiments").intValue()).isEqualTo(2);
		assertThat(fit.at("/inputs/commitFlushMs/experiments").intValue()).isEqualTo(1);
	}

	@Test
	void of_twoInputs_fallsBackOnTheMedianVariation() {
		ObjectNode fit = VariationFit.of(rows(ROWS));

		double median = (fit.at("/inputs/serviceTimeMs/sigma").doubleValue()
				+ fit.at("/inputs/commitFlushMs/sigma").doubleValue()) / 2;
		assertThat(fit.get("fallbackSigma").doubleValue()).isCloseTo(median, within(1e-4));
	}

	// in experiment a both inputs moved together
	@Test
	void of_inputsThatMoveTogether_correlate() {
		assertThat(VariationFit.of(rows(ROWS)).get("correlation").doubleValue()).isGreaterThan(0.5);
	}

	@Test
	void of_noCalibrations_isNoInput() {
		assertThat(VariationFit.of(List.of()).get("inputs").isEmpty()).isTrue();
	}

	// a fixed default was not seen varying: it takes the fallback, not 0
	@Test
	void of_anInputThatNeverMoved_takesTheFallback() {
		List<JsonNode> rows = rows(ROWS);
		rows.forEach((row) -> ((ObjectNode) row.get("scales")).put("fetchOverheadMs", 0.93));

		ObjectNode fit = VariationFit.of(rows);

		assertThat(fit.get("inputs").has("fetchOverheadMs")).isFalse();
		assertThat(KneeRanges.inputSigma(fit, "fetchOverheadMs")).isEqualTo(fit.get("fallbackSigma").doubleValue())
			.isPositive();
	}

	private static double squares(List<Double> logs) {
		double mean = PythonStatistics.fmean(logs);
		return logs.stream().mapToDouble((log) -> (log - mean) * (log - mean)).sum();
	}

	private static List<JsonNode> rows(String json) {
		return JsonText.read(json).valueStream().toList();
	}

}
