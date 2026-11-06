package io.archsim.engine.check;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A check of a design against its objectives and, with a baseline, against the baseline
 * ({@code run_check}). Written by {@code RecordCodec}.
 *
 * @param pass whether the check passes
 * @param exitCode {@code 0} when it passes, {@code 1} when it fails
 * @param failOn what fails it, in the order given
 * @param objectivesMissed whether this change misses an objective the design sets
 * @param regressed whether a figure regressed past its threshold
 * @param current this change's measurement
 * @param baseline the baseline's; {@code null} without a baseline
 * @param regressions how each figure changed from the baseline; none without one
 * @param thresholds how much worse each figure may get, by its Python name
 * ({@code p95_increase}), as the check writes them
 * @param notes what else the check says, such as a baseline it could not measure
 * @param headline the check in one sentence
 */
public record CheckResult(boolean pass, int exitCode, List<FailOn> failOn, boolean objectivesMissed, boolean regressed,
		Measurement current, @Nullable Measurement baseline, List<Regression> regressions,
		Map<String, Double> thresholds, List<String> notes, String headline) {

	/**
	 * Copies the lists and the thresholds, so the record does not change.
	 */
	public CheckResult {
		failOn = List.copyOf(failOn);
		regressions = List.copyOf(regressions);
		// in their order, as asdict writes them, which Map.copyOf would not keep
		thresholds = Collections.unmodifiableMap(new LinkedHashMap<>(thresholds));
		notes = List.copyOf(notes);
	}

	/**
	 * The check of a change, measured, against its objectives and its baseline: it fails
	 * when it misses an objective and fails on objectives, or regressed and fails on
	 * regressions.
	 * @param current this change's measurement
	 * @param baseline the baseline's; {@code null} without a baseline
	 * @param settings the thresholds and what fails the check
	 * @param notes what else the check says, such as a baseline it could not measure
	 * @return the check
	 */
	public static CheckResult of(Measurement current, @Nullable Measurement baseline, CheckSettings settings,
			List<String> notes) {
		List<Regression> regressions = (baseline != null)
				? Regressions.between(current, baseline, settings.thresholds()) : List.of();
		boolean missed = Boolean.FALSE.equals(current.slo().pass());
		boolean regressed = regressions.stream().anyMatch(Regression::regressed);
		boolean failsOnObjectives = settings.failOn().contains(FailOn.OBJECTIVES) && missed;
		boolean failsOnRegression = settings.failOn().contains(FailOn.REGRESSION) && regressed;
		boolean failed = failsOnObjectives || failsOnRegression;
		return new CheckResult(!failed, failed ? 1 : 0, settings.failOn(), missed, regressed, current, baseline,
				regressions, settings.thresholds().byPythonName(), notes, CheckText.headline(current, baseline));
	}

}
