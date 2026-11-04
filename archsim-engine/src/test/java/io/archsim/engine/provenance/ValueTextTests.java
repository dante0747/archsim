package io.archsim.engine.provenance;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** How an input's value reads in its provenance: Python 3.12's texts. */
class ValueTextTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest(name = "describe({0}, {1}) is {2}")
	@CsvSource(delimiter = '|', nullValues = "-", textBlock = """
			5|-|5
			5|ms|5 ms
			12345.678|req/s|12345.7 req/s
			true|-|on
			"redis"|ms|redis
			null|-|None
			[1, 2]|-|[1, 2]
			{"kind": "trace", "points": [[0, 10]]}|-|pattern trace
			{"kind": "burst", "burstRps": 50}|-|pattern burst
			{"kind": "exponential", "mean": 12.5}|ms|{'kind': 'exponential', 'mean': 12.5}
			""")
	void describe_aValue_readsAsPythonWritesIt(String json, String unit, String text) {
		assertThat(ValueText.describe(JSON.readTree(json), unit)).isEqualTo(text);
	}

}
