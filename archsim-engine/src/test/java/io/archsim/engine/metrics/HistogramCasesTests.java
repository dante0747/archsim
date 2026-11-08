package io.archsim.engine.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.DoubleFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Latency histograms against the frozen engine's {@code metrics} module, on its recorded
 * latencies ({@link MetricsCases}): bins, counts, means and every rounded figure bit for
 * bit; a percentile before it is rounded to a relative 1e-14, since it goes through
 * {@code log} and {@code pow}.
 */
class HistogramCasesTests {

	@TestFactory
	Stream<DynamicTest> percentileSeries_recordedLatenciesPerBucket_isPythons() {
		return MetricsCases.timelines((run, timeline) -> {
			JsonNode expected = run.get("bucketed");

			BucketedHistograms histograms = bucketed(timeline, expected.get("ops"));

			assertThat(List.of(MetricsCases.nan(histograms.percentileSeries(50)),
					MetricsCases.nan(histograms.percentileSeries(95))))
				.containsExactly(MetricsCases.nanHexes(expected.get("p50")),
						MetricsCases.nanHexes(expected.get("p95")));
		});
	}

	@TestFactory
	Stream<DynamicTest> window_recordedLatenciesPerBucket_mergesAsPython() {
		return MetricsCases.timelines((run, timeline) -> {
			JsonNode expected = run.get("bucketed");

			BucketedHistograms histograms = bucketed(timeline, expected.get("ops"));

			assertThat(histograms.window().summary()).isEqualTo(MetricsCases.summary(expected.get("window")));
		});
	}

	@Test
	void record_recordedLatencies_binAsPython() {
		JsonNode expected = MetricsCases.CASES.get("histogram");

		LatencyHistogram histogram = histogram(expected.get("values"));

		Map<Integer, Long> bins = new LinkedHashMap<>();
		MetricsCases.list(expected.get("bins")).forEach((bin) -> bins.put(bin.get(0).asInt(), bin.get(1).asLong()));
		assertThat(histogram.bins()).containsExactlyInAnyOrderEntriesOf(bins);
	}

	@Test
	void percentile_eachPercentile_isPythons() {
		JsonNode expected = MetricsCases.CASES.get("histogram");
		LatencyHistogram histogram = histogram(expected.get("values"));

		List<Double> percentiles = percentiles(expected.get("percentiles"), histogram::percentile);

		assertThat(percentiles).usingElementComparator(MetricsCases.LIBM)
			.containsExactlyElementsOf(expectedPercentiles(expected));
	}

	@Test
	void mean_recordedLatencies_isPythons() {
		JsonNode expected = MetricsCases.CASES.get("histogram");

		LatencyHistogram histogram = histogram(expected.get("values"));

		assertThat(histogram.mean()).hasValue(MetricsCases.hex(expected.get("mean")));
	}

	@Test
	void summary_recordedAndMerged_isPythons() {
		JsonNode expected = MetricsCases.CASES.get("histogram");
		LatencyHistogram histogram = histogram(expected.get("values"));
		LatencyHistogram merged = new LatencyHistogram();
		merged.merge(histogram);
		merged.merge(histogram(expected.get("extra")));

		List<LatencySummary> summaries = List.of(histogram.summary(), merged.summary(),
				new LatencyHistogram().summary());

		assertThat(summaries).containsExactly(MetricsCases.summary(expected.get("summary")),
				MetricsCases.summary(expected.get("merged")), MetricsCases.summary(expected.get("empty")));
	}

	@Test
	void count_successesAndFailures_isPythons() {
		JsonNode expected = MetricsCases.CASES.get("withFailures");

		LatencyWithFailures latencies = withFailures(expected.get("ops"));

		assertThat(latencies.count()).isEqualTo(expected.get("count").asLong());
	}

	@Test
	void percentile_successesAndFailures_isPythons() {
		JsonNode expected = MetricsCases.CASES.get("withFailures");
		LatencyWithFailures latencies = withFailures(expected.get("ops"));

		List<Double> percentiles = percentiles(expected.get("percentiles"), latencies::percentile);

		assertThat(percentiles).usingElementComparator(MetricsCases.LIBM)
			.containsExactlyElementsOf(expectedPercentiles(expected));
	}

	@Test
	void summary_successesAndFailures_isPythons() {
		JsonNode expected = MetricsCases.CASES.get("withFailures");

		LatencyWithFailures latencies = withFailures(expected.get("ops"));

		assertThat(latencies.summary()).isEqualTo(MetricsCases.summary(expected.get("summary")));
	}

	@Test
	void summary_onlyFailures_isPythons() {
		LatencyWithFailures latencies = new LatencyWithFailures();
		latencies.failure(1000.0, 1000.0);
		latencies.failure(3000.0, 2000.0);

		LatencySummary summary = latencies.summary();

		assertThat(summary).isEqualTo(MetricsCases.summary(MetricsCases.CASES.get("withFailures").get("onlyFailures")));
	}

	@Test
	void summary_nothingRecorded_isEmpty() {
		LatencySummary summary = new LatencyWithFailures().summary();

		assertThat(summary).isEqualTo(MetricsCases.summary(MetricsCases.CASES.get("withFailures").get("empty")));
	}

	private static LatencyWithFailures withFailures(JsonNode ops) {
		LatencyWithFailures latencies = new LatencyWithFailures();
		for (JsonNode op : ops) {
			if ("success".equals(op.get(0).stringValue())) {
				latencies.success(MetricsCases.hex(op.get(1)));
			}
			else {
				latencies.failure(MetricsCases.hex(op.get(1)), MetricsCases.hex(op.get(2)));
			}
		}
		return latencies;
	}

	private static List<Double> percentiles(JsonNode calls, DoubleFunction<OptionalDouble> percentile) {
		return MetricsCases.list(calls)
			.stream()
			.map((call) -> percentile.apply(call.get(0).asDouble()).orElseThrow())
			.toList();
	}

	private static List<Double> expectedPercentiles(JsonNode expected) {
		return MetricsCases.list(expected.get("percentiles"))
			.stream()
			.map((call) -> MetricsCases.hex(call.get(1)))
			.toList();
	}

	private static BucketedHistograms bucketed(Timeline timeline, JsonNode ops) {
		BucketedHistograms histograms = new BucketedHistograms(timeline);
		MetricsCases.list(ops)
			.forEach((op) -> histograms.record(MetricsCases.hex(op.get(0)), MetricsCases.hex(op.get(1))));
		return histograms;
	}

	private static LatencyHistogram histogram(JsonNode values) {
		LatencyHistogram histogram = new LatencyHistogram();
		MetricsCases.list(values).forEach((value) -> histogram.record(MetricsCases.hex(value)));
		return histogram;
	}

}
