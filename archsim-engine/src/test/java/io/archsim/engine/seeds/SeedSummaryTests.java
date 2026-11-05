package io.archsim.engine.seeds;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.engine.confidence.ConfidenceLevel;
import io.archsim.engine.slo.RunFigures;
import io.archsim.model.document.Objectives;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Summarizing runs that differ only in their seed: how their verdicts split, their
 * ranges, and the summary in words.
 */
class SeedSummaryTests {

	private static final Objectives P95 = new Objectives(300.0, null, null, null);

	@Test
	void of_runsThatDisagree_areBorderlineWithTheirConfidenceCappedAtMedium() {
		SeedSummary summary = SeedSummary.of(List.of(run(42, 98.0), run(43, 131.0), run(44, 412.0)), P95);

		assertThat(summary.verdict()).isEqualTo(SeedVerdict.BORDERLINE);
		assertThat(summary.confidenceCap()).isEqualTo(ConfidenceLevel.MEDIUM);
		assertThat(summary.text())
			.isEqualTo("Meets the objectives in 2 of 3 runs · p95 98–412 ms (median 131) · seeds 42–44");
	}

	@Test
	void of_runsThatAgree_holdAndCapNothing() {
		SeedSummary summary = SeedSummary.of(List.of(run(42, 98.0), run(43, 131.0)), P95);

		assertThat(summary.verdict()).isEqualTo(SeedVerdict.HOLDS);
		assertThat(summary.borderline()).isFalse();
		assertThat(summary.confidenceCap()).isNull();
	}

	@Test
	void of_aRunWithoutFigures_countsTowardsTheRunsNotTheFinished() {
		SeedSummary summary = SeedSummary.of(List.of(run(42, 98.0), new SeedRun(43, null)), P95);

		assertThat(summary.runs()).isEqualTo(2);
		assertThat(summary.finished()).isEqualTo(1);
		assertThat(summary.perRun()).extracting(SeedRunResult::seed).containsExactly(42L);
		assertThat(summary.seeds()).isEqualTo(new SeedSpan(42L, 43L));
	}

	@Test
	void of_noRuns_saysNothing() {
		SeedSummary summary = SeedSummary.of(List.of(), P95);

		assertThat(summary.objectives()).isZero();
		assertThat(summary.seeds()).isEqualTo(new SeedSpan(null, null));
		assertThat(summary.text()).isEmpty();
	}

	@Test
	void toJson_aSummary_writesItsFiguresInTheEnginesOrder() {
		SeedSummary summary = SeedSummary.of(List.of(run(42, 98.0)), P95);

		assertThat(summary.toJson().propertyNames()).containsExactly("runs", "finished", "seeds", "objectives", "met",
				"missed", "undecided", "verdict", "borderline", "confidenceCap", "latencyMs", "successRate",
				"throughputRps", "perRun", "text");
		assertThat(summary.toJson().path("verdict").stringValue()).isEqualTo("holds");
	}

	private static SeedRun run(long seed, double p95) {
		return new SeedRun(seed, new RunFigures(p95 / 2, p95, p95 * 1.5, 1.0, 400.0));
	}

}
