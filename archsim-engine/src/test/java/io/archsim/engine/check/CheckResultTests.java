package io.archsim.engine.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.archsim.engine.slo.SloResult;
import io.archsim.model.document.Objectives;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A check's result keeps its own lists and thresholds, the thresholds in the order the
 * check writes them.
 */
class CheckResultTests {

	@Test
	void collections_changedAfterwards_stayAsTheyWere() {
		List<String> notes = new ArrayList<>(List.of("The baseline could not be simulated."));
		Map<String, Double> thresholds = new LinkedHashMap<>(Thresholds.DEFAULTS.byPythonName());
		List<FailOn> failOn = new ArrayList<>(List.of(FailOn.OBJECTIVES));
		List<Regression> regressions = new ArrayList<>();
		CheckResult result = new CheckResult(true, 0, failOn, false, false, measurement(), null, regressions,
				thresholds, notes, "p95 90.0 ms");

		notes.clear();
		thresholds.put("p95_increase", 0.5);
		failOn.add(FailOn.REGRESSION);
		regressions.add(new Regression(Metric.P95, Metric.P95.label(), 90.0, 120.0, 0.333333, 0.1, true, null, null));

		assertThat(result.notes()).containsExactly("The baseline could not be simulated.");
		assertThat(result.thresholds()).containsEntry("p95_increase", 0.1);
		assertThat(result.failOn()).containsExactly(FailOn.OBJECTIVES);
		assertThat(result.regressions()).isEmpty();
	}

	@Test
	void of_theDefaultThresholds_keepsThemInTheOrderTheCheckWritesThem() {
		CheckResult result = CheckResult.of(measurement(), null,
				new CheckSettings(Thresholds.DEFAULTS, List.of(FailOn.OBJECTIVES)), List.of());

		assertThat(result.thresholds().keySet()).containsExactly("p95_increase", "success_drop", "cost_increase",
				"knee_drop");
	}

	private static Measurement measurement() {
		return new Measurement("case", null, 90.0, null, 1.0, 400.0, 400.0, null,
				new Objectives(null, null, null, null), new SloResult(List.of(), null, 0, 0), "No objectives", null,
				"f", "0.16.0", new MeasuredConfidence(null, null, null), null, null, null, null);
	}

}
