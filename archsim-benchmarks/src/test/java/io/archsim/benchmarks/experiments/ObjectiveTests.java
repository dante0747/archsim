package io.archsim.benchmarks.experiments;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A load step meets the objective with at least 99% of its requests succeeding and a p95
 * within 50 ms; a step without a p95 or a success rate does not.
 */
class ObjectiveTests {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|',
			value = { "{\"p95\": 50.0, \"successRate\": 0.99} | true", "{\"p95\": 50.1, \"successRate\": 1.0} | false",
					"{\"p95\": 5.0, \"successRate\": 0.98} | false", "{\"p95\": null, \"successRate\": 1.0} | false",
					"{\"p95\": 5.0, \"successRate\": null} | false", "{\"successRate\": 1.0} | false" })
	void isMetBy_aStep_isWhetherItsSuccessAndP95AreWithinTheObjective(String step, boolean met) {
		assertThat(Objective.isMetBy(JsonText.read(step))).isEqualTo(met);
	}

}
