package io.archsim.engine.load;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** A workload's steady base rate. */
class BaseRateTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest(name = "{0} has a base rate of {1}")
	@CsvSource(delimiter = '|', textBlock = """
			{"kind": "constant"}|100
			{"kind": "burst", "startS": 10, "durationS": 5, "burstRps": 500}|100
			{"kind": "ramp", "startRps": 300, "endRps": 50}|50
			{"kind": "trace", "points": [[0, 10], [60, 70]]}|40
			""")
	void of_aPattern_isItsSteadyRate(String pattern, double rate) {
		Workload workload = RecordCodec.read(
				JSON.readTree("{\"rps\": 100, \"durationS\": 60, \"pattern\": %s}".formatted(pattern)), Workload.class);

		assertThat(BaseRate.of(workload)).isCloseTo(rate, within(1e-9));
	}

}
