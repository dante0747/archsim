package io.archsim.engine.calibration.prometheus;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.calibration.fit.Histogram;
import io.archsim.engine.calibration.mapping.LatencyUnit;
import io.archsim.engine.calibration.mapping.Reduction;
import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Prometheus range results read as the Python engine reads them, where the golden files'
 * recorded results do not reach. Expected values are the Python engine's.
 */
class PrometheusSeriesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode TWO_SERIES = JSON.readTree("""
			[{"values": [[2, "1"], [1, "5"]]}, {"values": [[1, "1"], [3, "-0"]]}]""");

	@Test
	void histogram_bucketsAveragedOverTheRange_areCumulativeInMilliseconds() {
		JsonNode result = JSON.readTree("""
				[{"metric": {"le": "0.01"}, "values": [[1, "2"], [2, "4"]]},
				 {"metric": {"le": "0.1"}, "values": [[1, "8"], [2, "10"]]},
				 {"metric": {"le": "+Inf"}, "values": [[1, "10"], [2, "12"]]}]""");

		assertThat(PrometheusSeries.histogram(result, LatencyUnit.SECONDS))
			.isEqualTo(new Histogram(List.of(10.0, 100.0), List.of(3.0, 9.0), 11.0));
	}

	@Test
	void histogram_zeroBoundOfEachSign_isOneBucketUnderTheFirst() {
		JsonNode result = JSON.readTree("""
				[{"metric": {"le": "-0"}, "values": [[1, "1"]]},
				 {"metric": {"le": "0"}, "values": [[1, "2"]]},
				 {"metric": {"le": "1"}, "values": [[1, "4"]]}]""");

		Histogram histogram = PrometheusSeries.histogram(result, LatencyUnit.MILLISECONDS);

		assertThat(histogram.bounds()).containsExactly(-0.0, 1.0);
		assertThat(histogram.cumulative()).containsExactly(3.0, 4.0);
	}

	@ParameterizedTest
	@MethodSource("resultsItCannotUse")
	void histogram_resultItCannotUse_isRefusedInThePythonEnginesWords(String result, String message) {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> PrometheusSeries.histogram(JSON.readTree(result), LatencyUnit.SECONDS))
			.withMessage(message);
	}

	/** Histogram results the Python engine refuses, each with its words. */
	static Stream<Arguments> resultsItCannotUse() {
		String malformedLe = "a histogram series has a malformed 'le' label";
		String malformedPoints = "a series has malformed [timestamp, value] points";
		String noData = "the histogram query returned no data in the range";
		return Stream.of(
				Arguments.of("[{\"metric\": {}, \"values\": []}]",
						"a histogram query must return series with an 'le' label (use sum by (le))"),
				Arguments.of("[{\"metric\": {\"le\": \"x\"}, \"values\": [[1, \"1\"]]}]", malformedLe),
				Arguments.of("[{\"metric\": {\"le\": \"nan\"}, \"values\": [[1, \"1\"]]}]", malformedLe),
				Arguments.of("[{\"metric\": {\"le\": \"1\"}, \"values\": [[1]]}]", malformedPoints),
				Arguments.of("[{\"metric\": {\"le\": \"1\"}, \"values\": {\"a\": 1}}]", malformedPoints),
				Arguments.of("[{\"metric\": {\"le\": \"1\"}, \"values\": null}]", noData),
				Arguments.of("[{\"metric\": {\"le\": \"1\"}, \"values\": [[1, \"NaN\"]]}]", noData));
	}

	@Test
	void summed_series_areAddedUpPerTimestampInTimeOrder() {
		assertThat(PrometheusSeries.summed(TWO_SERIES)).containsExactly(new Point(1, 6), new Point(2, 1),
				new Point(3, 0.0));
	}

	@ParameterizedTest
	@CsvSource({ "MAX, 6.0", "MIN, 0.0", "LAST, 0.0", "MEAN, 2.3333333333333335" })
	void reduce_summedSeries_isOneFigure(Reduction how, double figure) {
		assertThat(PrometheusSeries.reduce(TWO_SERIES, how)).isEqualTo(figure);
	}

	@Test
	void reduce_noData_isRefused() {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> PrometheusSeries.reduce(JSON.readTree("[{\"values\": []}]"), Reduction.MAX))
			.withMessage("the query returned no data in the range");
	}

}
