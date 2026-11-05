package io.archsim.engine.slo;

import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.Objectives;

/**
 * Whether a run is good enough: each objective the design sets, checked against the run's
 * figures (latency at most its target, availability and throughput at least), and the
 * verdict over them.
 *
 * @param checks each objective the design sets, in {@link Objective}'s order
 * @param pass whether the run meets every objective; {@code false} as soon as one is
 * missed; {@code null} when the design sets none, or a figure is missing and none is
 * missed
 * @param met how many objectives the run meets
 * @param total how many objectives the design sets
 */
public record SloResult(List<SloCheck> checks, @Nullable Boolean pass, int met, int total) {

	/**
	 * Copies the checks.
	 */
	public SloResult {
		checks = List.copyOf(checks);
	}

	/**
	 * Judges a run against the design's objectives.
	 * @param objectives the design's objectives
	 * @param figures the run's figures
	 * @return the checks and the verdict
	 */
	public static SloResult evaluate(Objectives objectives, RunFigures figures) {
		List<SloCheck> checks = SloCheck.each(objectives, figures);
		List<SloCheck> decided = checks.stream().filter((check) -> check.pass() != null).toList();
		int met = (int) decided.stream().filter((check) -> Boolean.TRUE.equals(check.pass())).count();
		return new SloResult(checks, verdict(checks.size(), decided.size(), met), met, checks.size());
	}

	private static @Nullable Boolean verdict(int checks, int decided, int met) {
		if (checks == 0) {
			return null;
		}
		if (decided < checks) {
			return (met < decided) ? Boolean.FALSE : null;
		}
		return met == decided;
	}

	/**
	 * The verdict in words: {@code Meets all 2 objectives}, or each objective missed with
	 * its figure and target, as in
	 * {@code Misses 1 of 2 objectives: Availability 50.00% (target >= 99.90%)}.
	 * @return its text
	 */
	public String text() {
		if (this.pass == null) {
			return (this.total == 0) ? "No objectives set" : "Not enough data to decide";
		}
		if (this.pass) {
			return "Meets all " + this.total + " objectives";
		}
		List<SloCheck> missed = this.checks.stream().filter((check) -> Boolean.FALSE.equals(check.pass())).toList();
		return "Misses " + missed.size() + " of " + this.total + " objectives: "
				+ missed.stream().map(SloCheck::missedText).collect(Collectors.joining("; "));
	}

}
