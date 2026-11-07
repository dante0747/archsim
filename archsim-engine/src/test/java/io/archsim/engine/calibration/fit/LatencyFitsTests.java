package io.archsim.engine.calibration.fit;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Empirical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Latencies fitted as the Python engine fits them, where the golden files' recorded fits
 * do not reach. Expected values are the Python engine's.
 */
class LatencyFitsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Three observations of eleven at or below 10 ms, nine at or below 100 ms. */
	private static final Histogram SPILLING = new Histogram(List.of(10.0, 100.0), List.of(3.0, 9.0), 11.0);

	@Test
	void fitHistogram_observationsAboveTheHighestBound_areNotedAndTakenAsThatBound() {
		Fit fit = LatencyFits.fitHistogram(SPILLING, FitKind.LOGNORMAL);

		assertThat(fit.distribution())
			.isEqualTo(JSON.readTree("{\"kind\": \"lognormal\", \"median\": 47.5, \"p95\": 100.0}"));
		assertThat(fit.error()).isEqualTo(0.2834);
		assertThat(fit.observed()).containsExactly(Map.entry(0.5, 47.5), Map.entry(0.75, 88.75), Map.entry(0.9, 100.0),
				Map.entry(0.95, 100.0), Map.entry(0.99, 100.0));
		assertThat(fit.count()).isEqualTo(11.0);
		assertThat(fit.notes()).containsExactly("18.2% of observations were above the highest bucket bound (100 ms); "
				+ "a histogram does not say how far above, so quantiles that fall there are taken as 100 ms");
	}

	@Test
	void fitHistogram_empirical_isTheBucketsTableWithoutAnErrorAndWithTheirWidths() {
		Fit fit = LatencyFits.fitHistogram(SPILLING, FitKind.EMPIRICAL);

		assertThat(fit.distribution()).isEqualTo(JSON.readTree("""
				{"kind": "empirical",
				 "quantiles": [[0.0, 0.0], [0.272727, 10.0], [0.818182, 100.0], [1.0, 200.0]]}"""));
		assertThat(fit.error()).isNull();
		assertThat(fit.notes()).containsExactly(
				"18.2% of observations were above the highest bucket bound (100 ms); a histogram does not say how far "
						+ "above, so the table spreads them between 100 and 200 ms",
				"an empirical table from histogram buckets passes through every bucket bound, so no fit error can be "
						+ "measured; inside a bucket it is only as precise as the bucket (p50 between 10 and 100 ms)");
	}

	/**
	 * Shares closer than the table's six decimals: the Python engine wrote
	 * {@code [1.0, 2.0]} and {@code [1.0, 3.0]}, a table the design's validation refuses;
	 * the Java keeps the last point of each run of equal shares.
	 */
	@Test
	void fitHistogram_empiricalSharesThatRoundAlike_keepTheLastOfEachSoTheTableIsValid() {
		Histogram close = new Histogram(List.of(1.0, 2.0, 3.0), List.of(5.0, 9.9999999, 10.0), 10.0);

		Fit fit = LatencyFits.fitHistogram(close, FitKind.EMPIRICAL);

		assertThat(fit.distribution())
			.isEqualTo(JSON.readTree("{\"kind\": \"empirical\", \"quantiles\": [[0.0, 0.0], [0.5, 1.0], [1.0, 3.0]]}"));
		assertThat(RecordCodec.read(fit.distribution(), Distribution.class)).isInstanceOfSatisfying(Empirical.class,
				(empirical) -> assertThat(empirical.quantiles()).hasSize(3));
	}

	@Test
	void fitHistogram_noObservations_isRefused() {
		Histogram empty = new Histogram(List.of(1.0), List.of(0.0), 0.0);

		assertThatIllegalArgumentException().isThrownBy(() -> LatencyFits.fitHistogram(empty, FitKind.LOGNORMAL))
			.withMessage("the histogram has no observations");
	}

	@Test
	void fitSamples_lognormal_matchesTheMedianAndP95AndMeasuresTheError() {
		Fit fit = LatencyFits.fitSamples(List.of(3.0, 1.0, 2.0, 10.0), FitKind.LOGNORMAL);

		assertThat(fit.distribution())
			.isEqualTo(JSON.readTree("{\"kind\": \"lognormal\", \"median\": 2.5, \"p95\": 8.95}"));
		assertThat(fit.error()).isEqualTo(0.5507);
		assertThat(fit.observed().get(0.9)).isEqualTo(7.900000000000001);
		assertThat(fit.count()).isEqualTo(4.0);
		assertThat(fit.notes()).isEmpty();
	}

	@Test
	void fitSamples_none_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> LatencyFits.fitSamples(List.of(), FitKind.LOGNORMAL))
			.withMessage("no samples");
	}

	@Test
	void sampleQuantile_betweenTwoSamples_isInterpolatedLinearly() {
		assertThat(LatencyFits.sampleQuantile(List.of(1.0, 2.0, 4.0), 0.75)).isEqualTo(3.0);
	}

}
