package io.archsim.engine.sweep;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.archsim.model.document.Objectives;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A load sweep's breaking point: where the design stops keeping up or its latency jumps,
 * and the heaviest load that still meets the objectives.
 */
class SweepAnalysisTests {

	private static final Objectives P95 = new Objectives(300.0, null, null, null, null);

	@Test
	void of_stepsInAnyOrderSomeNotCompleted_readsTheCompletedFromTheLightest() {
		SweepAnalysis analysis = SweepAnalysis.of(
				List.of(row(300.0, 300.0, 20.0, null), row(200.0, null, null, null), row(100.0, 100.0, 10.0, null)),
				P95);

		assertThat(analysis.rows()).extracting(SweepRow::rps).containsExactly(100.0, 300.0);
	}

	@Test
	void of_noCompletedStep_saysSo() {
		SweepAnalysis analysis = SweepAnalysis.of(List.of(row(100.0, null, null, null)), P95);

		assertThat(analysis.rows()).isEmpty();
		assertThat(analysis.verdict()).isEqualTo("No completed steps yet.");
	}

	@Test
	void of_aStepThatFallsBehind_isTheKneeWhereItsBottleneckSaturates() {
		SweepAnalysis analysis = SweepAnalysis.of(List.of(row(100.0, 100.0, 10.0, null),
				row(1500.0, 1400.0, 40.0, "API"), row(2000.0, 1500.0, 900.0, "API")), P95);

		assertThat(analysis.kneeReason()).isEqualTo(KneeReason.THROUGHPUT);
		assertThat(analysis.verdict())
			.isEqualTo("Holds 1,500 req/s within the objectives; saturates around 1,500 req/s at API.");
	}

	@Test
	void of_aLatencyThatJumps_isTheKnee() {
		SweepAnalysis analysis = SweepAnalysis.of(
				List.of(row(100.0, 100.0, 10.0, null), row(200.0, 200.0, 12.0, null), row(300.0, 300.0, 48.0, "DB")),
				P95);

		assertThat(analysis.knee()).extracting(SweepRow::rps).isEqualTo(300.0);
		assertThat(analysis.kneeReason()).isEqualTo(KneeReason.LATENCY);
		assertThat(analysis.verdict())
			.isEqualTo("Holds 300 req/s within the objectives; latency climbs sharply around 300 req/s at DB.");
	}

	@Test
	void of_everyStepWithinTheObjectivesAndNoKnee_suggestsAHigherRange() {
		SweepAnalysis analysis = SweepAnalysis.of(List.of(row(100.0, 100.0, 10.0, null), row(200.0, 200.0, 11.0, null)),
				P95);

		assertThat(analysis.maxWithinSlo()).extracting(SweepRow::rps).isEqualTo(200.0);
		assertThat(analysis.verdict()).isEqualTo("Holds 200 req/s within the objectives with no sign of saturation. "
				+ "Try a higher range to find the limit.");
	}

	@Test
	void of_theLightestStepMissingTheObjectives_saysSo() {
		SweepAnalysis analysis = SweepAnalysis.of(List.of(row(1234.5, 1234.0, 320.0, "API")), P95);

		assertThat(analysis.maxWithinSlo()).isNull();
		assertThat(analysis.verdict()).isEqualTo("Misses the objectives already at 1,235 req/s, the lightest step.");
	}

	private static SweepRow row(double rps, @Nullable Double throughput, @Nullable Double p95,
			@Nullable String bottleneck) {
		return new SweepRow(rps, rps, throughput, null, p95, null, p95, 1.0, bottleneck, "abc");
	}

}
