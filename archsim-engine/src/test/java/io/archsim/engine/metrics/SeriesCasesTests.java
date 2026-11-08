package io.archsim.engine.metrics;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timelines, counters, gauges, running means and rounding against the frozen engine's
 * {@code metrics} module, on its recorded operations ({@link MetricsCases}): bit for bit.
 */
class SeriesCasesTests {

	@Test
	void bucketWidth_eachArrivalPeriodAndRun_isPythons() {
		List<JsonNode> calls = MetricsCases.list(MetricsCases.CASES.get("helpers").get("chooseBucketMs"));

		List<Integer> widths = calls.stream().map(SeriesCasesTests::bucketWidth).toList();

		assertThat(widths).containsExactlyElementsOf(calls.stream().map((call) -> call.get(2).asInt()).toList());
	}

	@Test
	void of_eachValue_roundsAsPython() {
		List<JsonNode> calls = MetricsCases.list(MetricsCases.CASES.get("helpers").get("roundSeries"));

		List<Double> rounded = calls.stream()
			.map((call) -> MetricsCases.orNaN(Rounding.of(MetricsCases.nanHex(call.get(0)))))
			.toList();

		assertThat(rounded)
			.containsExactlyElementsOf(calls.stream().map((call) -> MetricsCases.nanHex(call.get(1))).toList());
	}

	@TestFactory
	Stream<DynamicTest> timeline_eachRun_cutsAsPython() {
		return MetricsCases.timelines((run, timeline) -> assertThat(new TimelineFigures(timeline))
			.isEqualTo(TimelineFigures.of(run.get("timeline"))));
	}

	@TestFactory
	Stream<DynamicTest> index_timesAroundTheEdges_isPythons() {
		return MetricsCases.timelines((run, timeline) -> {
			List<JsonNode> probes = MetricsCases.list(run.get("timeline").get("index"));

			List<Integer> buckets = probes.stream()
				.map((probe) -> timeline.index(MetricsCases.hex(probe.get(0))))
				.toList();

			assertThat(buckets)
				.containsExactlyElementsOf(probes.stream().map((probe) -> probe.get(1).asInt()).toList());
		});
	}

	@TestFactory
	Stream<DynamicTest> inWindow_timesAroundTheEdges_isPythons() {
		return MetricsCases.timelines((run, timeline) -> {
			List<JsonNode> probes = MetricsCases.list(run.get("timeline").get("inWindow"));

			List<Boolean> inside = probes.stream()
				.map((probe) -> timeline.inWindow(MetricsCases.hex(probe.get(0))))
				.toList();

			assertThat(inside)
				.containsExactlyElementsOf(probes.stream().map((probe) -> probe.get(1).asBoolean()).toList());
		});
	}

	@TestFactory
	Stream<DynamicTest> increment_recordedIncrements_countAsPython() {
		return MetricsCases.timelines((run, timeline) -> {
			JsonNode expected = run.get("counter");
			Counter counter = new Counter(timeline);

			MetricsCases.list(expected.get("ops"))
				.forEach((op) -> counter.increment(MetricsCases.hex(op.get(0)), op.get(1).asLong()));

			assertThat(new CounterFigures(counter)).isEqualTo(CounterFigures.of(expected));
		});
	}

	@TestFactory
	Stream<DynamicTest> add_recordedChanges_integrateAsPython() {
		return MetricsCases.timelines((run, timeline) -> {
			JsonNode expected = run.get("gauge");
			Gauge gauge = new Gauge(timeline);

			MetricsCases.list(expected.get("ops"))
				.forEach((op) -> gauge.add(MetricsCases.hex(op.get(0)), op.get(1).asLong()));
			gauge.finish(MetricsCases.hex(expected.get("finalizeAt")));

			assertThat(new GaugeFigures(gauge)).isEqualTo(GaugeFigures.of(expected));
		});
	}

	@TestFactory
	Stream<DynamicTest> record_recordedValues_averageAsPython() {
		return MetricsCases.timelines((run, timeline) -> {
			JsonNode expected = run.get("means");
			MeanAccumulator means = new MeanAccumulator(timeline);

			MetricsCases.list(expected.get("ops"))
				.forEach((op) -> means.record(MetricsCases.hex(op.get(0)), MetricsCases.hex(op.get(1))));

			assertThat(new MeanFigures(means)).isEqualTo(MeanFigures.of(expected));
		});
	}

	private static int bucketWidth(JsonNode call) {
		double arrivalMs = MetricsCases.hex(call.get(0));
		return Timeline.bucketWidth(arrivalMs, call.get(1).isNull() ? arrivalMs : MetricsCases.hex(call.get(1)));
	}

	private static List<Long> boxed(long[] values) {
		return Arrays.stream(values).boxed().toList();
	}

	private static List<Double> boxed(double[] values) {
		return Arrays.stream(values).boxed().toList();
	}

	/**
	 * How a timeline cuts a run.
	 */
	private record TimelineFigures(int bucketMs, int buckets, double windowMs) {

		TimelineFigures(Timeline timeline) {
			this(timeline.bucketMs(), timeline.buckets(), timeline.windowMs());
		}

		static TimelineFigures of(JsonNode expected) {
			return new TimelineFigures(expected.get("bucketMs").asInt(), expected.get("n").asInt(),
					MetricsCases.hex(expected.get("windowMs")));
		}

	}

	/**
	 * What a counter reports.
	 */
	private record CounterFigures(List<Long> buckets, long total, long windowCount, List<Double> rates,
			double windowRate) {

		CounterFigures(Counter counter) {
			this(boxed(counter.buckets()), counter.total(), counter.windowCount(), boxed(counter.rateSeries()),
					counter.windowRate());
		}

		static CounterFigures of(JsonNode expected) {
			return new CounterFigures(MetricsCases.longs(expected.get("buckets")), expected.get("total").asLong(),
					expected.get("window").asLong(), MetricsCases.hexes(expected.get("rateSeries")),
					MetricsCases.hex(expected.get("windowRate")));
		}

	}

	/**
	 * What a gauge reports.
	 */
	private record GaugeFigures(long value, List<Double> area, List<Double> means, List<Long> maxes, double windowArea,
			long windowPeak, double windowMean) {

		GaugeFigures(Gauge gauge) {
			this(gauge.value(), boxed(gauge.area()), boxed(gauge.meanSeries()), boxed(gauge.maxSeries()),
					gauge.windowArea(), gauge.windowPeak(), gauge.windowMean());
		}

		static GaugeFigures of(JsonNode expected) {
			return new GaugeFigures(expected.get("value").asLong(), MetricsCases.hexes(expected.get("area")),
					MetricsCases.hexes(expected.get("meanSeries")), MetricsCases.longs(expected.get("maxes")),
					MetricsCases.hex(expected.get("windowArea")), expected.get("peakWindow").asLong(),
					MetricsCases.hex(expected.get("windowMean")));
		}

	}

	/**
	 * What a running mean reports.
	 */
	private record MeanFigures(List<Double> series, double windowMean) {

		MeanFigures(MeanAccumulator means) {
			this(MetricsCases.nan(means.series()), means.windowMean().orElse(Double.NaN));
		}

		static MeanFigures of(JsonNode expected) {
			return new MeanFigures(MetricsCases.nanHexes(expected.get("series")),
					MetricsCases.nanHex(expected.get("windowMean")));
		}

	}

}
