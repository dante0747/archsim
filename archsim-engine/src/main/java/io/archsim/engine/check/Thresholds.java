package io.archsim.engine.check;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much worse a figure may get than the baseline's before a check counts it as a
 * regression.
 *
 * @param p95Increase relative: the p95 latency up by more than this share
 * @param successDrop absolute: the success rate down by more than this
 * @param costIncrease relative: the monthly cost up by more than this share
 * @param kneeDrop relative: the highest load within the objectives down by more than this
 * share, and by more than one step of the load grid it was measured on
 */
public record Thresholds(double p95Increase, double successDrop, double costIncrease, double kneeDrop) {

	/** The thresholds {@code archsim check} uses unless told otherwise. */
	public static final Thresholds DEFAULTS = new Thresholds(0.10, 0.001, 0.10, 0.10);

	/**
	 * The thresholds by their Python names, as a check's result writes them
	 * ({@code dataclasses.asdict}).
	 * @return each threshold by its snake_case name, in order
	 */
	Map<String, Double> byPythonName() {
		Map<String, Double> named = new LinkedHashMap<>();
		named.put("p95_increase", this.p95Increase);
		named.put("success_drop", this.successDrop);
		named.put("cost_increase", this.costIncrease);
		named.put("knee_drop", this.kneeDrop);
		return named;
	}

}
