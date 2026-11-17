package io.archsim.engine.slo;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.Objectives;

/**
 * One objective a run is judged by, against the run's figure. Its name and which way is
 * better repeat the objective's, as a check's JSON carries them.
 *
 * @param key which objective
 * @param label what it is called
 * @param target the design's target
 * @param actual the run's figure, or {@code null} when the run has none
 * @param pass whether the figure meets the target; {@code null} without a figure
 * @param better which way the figure is better
 */
public record SloCheck(Objective key, String label, double target, @Nullable Double actual, @Nullable Boolean pass,
		Better better) {

	/**
	 * The objectives the design sets, each checked against the run's figure.
	 * @param objectives the design's objectives
	 * @param figures the run's figures
	 * @return a check for each objective whose target is on and that judges the run, in
	 * {@link Objective}'s order
	 */
	public static List<SloCheck> each(Objectives objectives, RunFigures figures) {
		List<SloCheck> checks = new ArrayList<>();
		for (Objective objective : Objective.values()) {
			Double target = objective.target(objectives);
			if (target != null && objective.judges(figures)) {
				checks.add(check(objective, target, objective.actual(figures)));
			}
		}
		return List.copyOf(checks);
	}

	private static SloCheck check(Objective objective, double target, @Nullable Double actual) {
		Boolean pass = (actual != null) ? objective.better().meets(actual, target) : null;
		return new SloCheck(objective, objective.label(), target, actual, pass, objective.better());
	}

	/**
	 * A missed objective as a verdict writes it, with its figure and target:
	 * {@code Availability 50.00% (target >= 99.90%)}.
	 * @return its text
	 */
	String missedText() {
		return this.label + " " + this.key.text(this.actual) + " (target " + this.better.sign() + " "
				+ this.key.text(this.target) + ")";
	}

}
