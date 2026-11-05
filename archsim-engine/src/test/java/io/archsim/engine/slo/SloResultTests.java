package io.archsim.engine.slo;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.archsim.model.document.Objectives;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Judging a run against the design's objectives: a latency at most its target,
 * availability and throughput at least theirs, and the verdict in the web app's words.
 */
class SloResultTests {

	private static final Objectives P95_AND_AVAILABILITY = new Objectives(300.0, null, 0.999, null);

	@Test
	void evaluate_figuresOnTheirTargets_meetEveryObjective() {
		SloResult result = SloResult.evaluate(P95_AND_AVAILABILITY, figures(300.0, 0.999, 100.0));

		assertThat(result.pass()).isTrue();
		assertThat(result.met()).isEqualTo(2);
		assertThat(result.text()).isEqualTo("Meets all 2 objectives");
	}

	@Test
	void evaluate_noTargets_decidesNothing() {
		SloResult result = SloResult.evaluate(new Objectives(null, null, null, null), figures(90.0, 1.0, 100.0));

		assertThat(result.checks()).isEmpty();
		assertThat(result.pass()).isNull();
		assertThat(result.text()).isEqualTo("No objectives set");
	}

	@Test
	void evaluate_aFigureMissingAndNoneMissed_cannotDecide() {
		SloResult result = SloResult.evaluate(P95_AND_AVAILABILITY, figures(null, 1.0, 100.0));

		assertThat(result.pass()).isNull();
		assertThat(result.text()).isEqualTo("Not enough data to decide");
	}

	@Test
	void evaluate_aFigureMissingAndOneMissed_misses() {
		SloResult result = SloResult.evaluate(P95_AND_AVAILABILITY, figures(null, 0.5, 100.0));

		assertThat(result.pass()).isFalse();
		assertThat(result.text()).isEqualTo("Misses 1 of 2 objectives: Availability 50.00% (target >= 99.90%)");
	}

	@Test
	void text_eachKindOfFigureMissed_readsAsTheWebAppWritesIt() {
		Objectives objectives = new Objectives(300.0, null, 0.999, 1300.0);

		String text = SloResult.evaluate(objectives, figures(1234.56, 0.5, 1234.5)).text();

		assertThat(text).isEqualTo("Misses 3 of 3 objectives: Latency p95 1,234.6 ms (target <= 300.0 ms); "
				+ "Availability 50.00% (target >= 99.90%); Throughput 1,234 req/s (target >= 1,300 req/s)");
	}

	@Test
	void text_aMissedObjectiveWithoutAFigure_readsNotAvailable() {
		SloCheck missed = new SloCheck(Objective.P99, "Latency p99", 500.0, null, false, Better.LOWER);

		assertThat(new SloResult(List.of(missed), false, 0, 1).text())
			.isEqualTo("Misses 1 of 1 objectives: Latency p99 n/a (target <= 500.0 ms)");
	}

	private static RunFigures figures(@Nullable Double p95, double successRate, double throughputRps) {
		return new RunFigures(null, p95, null, successRate, throughputRps);
	}

}
