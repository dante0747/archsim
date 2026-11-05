package io.archsim.engine.sweep;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.Objectives;
import io.archsim.model.text.PythonFormat;

/**
 * A load sweep's breaking point, read from its curve: the knee is the first step where
 * the design stops keeping up (successful throughput below 95% of the offered load) or
 * its tail latency jumps (a p95 at least 4 times the lightest step's and twice the
 * previous step's, and 5 ms above the lightest); the useful capacity is the highest load
 * that still meets every objective, contiguously from the lightest step, usually below
 * raw saturation. The same rules as the web app's breaking-point finder.
 *
 * @param rows the completed steps, from the lightest load
 * @param knee the step where the design stops keeping up, if any
 * @param kneeReason why the knee is there, if there is one
 * @param maxWithinSlo the heaviest step that meets the objectives, with every lighter
 * one; none when the lightest misses them
 * @param saturatedAt the part the knee's step (or else the heaviest step) found the
 * bottleneck, if any
 * @param verdict the breaking point in words
 */
public record SweepAnalysis(List<SweepRow> rows, @Nullable SweepRow knee, @Nullable KneeReason kneeReason,
		@Nullable SweepRow maxWithinSlo, @Nullable String saturatedAt, String verdict) {

	/** Below this share of the offered load, a step does not keep up. */
	private static final double KEEPS_UP = 0.95;

	/** A knee's p95 is at least this many times the lightest step's. */
	private static final int TIMES_THE_LIGHTEST = 4;

	/** A knee's p95 is at least this many times the previous step's. */
	private static final int TIMES_THE_PREVIOUS = 2;

	/** A knee's p95 is at least this many milliseconds above the lightest step's. */
	private static final int MS_ABOVE_THE_LIGHTEST = 5;

	/** Added before a load is floored, so that halves round up. */
	private static final double HALF = 0.5;

	/**
	 * Copies the rows.
	 */
	public SweepAnalysis {
		rows = List.copyOf(rows);
	}

	/**
	 * Reads a sweep's curve.
	 * @param steps the steps, in any order; those not completed are left out
	 * @param objectives the design's objectives
	 * @return the breaking point
	 */
	public static SweepAnalysis of(List<SweepRow> steps, Objectives objectives) {
		List<SweepRow> rows = steps.stream()
			.filter((row) -> row.throughput() != null)
			.sorted(Comparator.comparingDouble(SweepRow::rps))
			.toList();
		if (rows.isEmpty()) {
			return new SweepAnalysis(rows, null, null, null, null, "No completed steps yet.");
		}
		Knee knee = Knee.of(rows);
		int withinSlo = withinSlo(rows, objectives);
		SweepRow maxWithinSlo = (withinSlo > 0) ? rows.get(withinSlo - 1) : null;
		String saturatedAt = ((knee != null) ? knee.row() : rows.getLast()).bottleneck();
		String verdict;
		if (maxWithinSlo == null) {
			verdict = missesAtTheLightest(rows.getFirst());
		}
		else if (withinSlo == rows.size() && knee == null) {
			verdict = holdsWithoutSaturating(maxWithinSlo);
		}
		else {
			verdict = holdsUntil(maxWithinSlo, knee, saturatedAt);
		}
		return new SweepAnalysis(rows, (knee != null) ? knee.row() : null, (knee != null) ? knee.reason() : null,
				maxWithinSlo, saturatedAt, verdict);
	}

	/** How many steps, from the lightest, meet the objectives one after another. */
	private static int withinSlo(List<SweepRow> rows, Objectives objectives) {
		int within = 0;
		while (within < rows.size() && Boolean.TRUE.equals(rows.get(within).meets(objectives))) {
			within++;
		}
		return within;
	}

	private static String missesAtTheLightest(SweepRow lightest) {
		return "Misses the objectives already at " + rps(lightest) + " req/s, the lightest step.";
	}

	private static String holdsWithoutSaturating(SweepRow heaviest) {
		return "Holds " + rps(heaviest) + " req/s within the objectives with no sign of saturation. "
				+ "Try a higher range to find the limit.";
	}

	/**
	 * The verdict of a sweep that meets the objectives up to a step, and then misses them
	 * or saturates.
	 * @param maxWithinSlo the heaviest step that meets them, with every lighter one
	 * @param knee the knee, if any
	 * @param saturatedAt the part that saturates, if any
	 * @return the verdict
	 */
	private static String holdsUntil(SweepRow maxWithinSlo, @Nullable Knee knee, @Nullable String saturatedAt) {
		StringBuilder verdict = new StringBuilder("Holds " + rps(maxWithinSlo) + " req/s within the objectives");
		if (knee != null) {
			verdict.append("; ").append(knee.what()).append(" around ").append(rps(knee.row())).append(" req/s");
		}
		if (saturatedAt != null && !saturatedAt.isEmpty()) {
			verdict.append(" at ").append(saturatedAt);
		}
		return verdict.append('.').toString();
	}

	/** A step's load as a verdict writes it: whole, halves up, with separators. */
	private static String rps(SweepRow row) {
		return PythonFormat.format((long) Math.floor(row.rps() + HALF), ",");
	}

	/**
	 * Where the design stops keeping up, and why.
	 *
	 * @param row the knee's step
	 * @param reason why it is the knee
	 */
	private record Knee(SweepRow row, KneeReason reason) {

		/** The first step that does not keep up or whose latency jumps, if any. */
		static @Nullable Knee of(List<SweepRow> rows) {
			Double lightest = rows.stream().map(SweepRow::p95).filter(Objects::nonNull).findFirst().orElse(null);
			for (int i = 0; i < rows.size(); i++) {
				SweepRow row = rows.get(i);
				if (fallsBehind(row)) {
					return new Knee(row, KneeReason.THROUGHPUT);
				}
				Double previous = (i > 0) ? rows.get(i - 1).p95() : null;
				if (climbsSharply(row.p95(), lightest, previous)) {
					return new Knee(row, KneeReason.LATENCY);
				}
			}
			return null;
		}

		/** Whether the step's successful throughput is below 95% of its offered load. */
		private static boolean fallsBehind(SweepRow row) {
			Double measured = row.offered();
			double offered = (measured != null && measured != 0) ? measured : row.rps();
			double throughput = Objects.requireNonNullElse(row.throughput(), 0.0);
			return offered > 0 && throughput < KEEPS_UP * offered;
		}

		private static boolean climbsSharply(@Nullable Double p95, @Nullable Double lightest,
				@Nullable Double previous) {
			if (p95 == null || lightest == null || previous == null) {
				return false;
			}
			return p95 >= TIMES_THE_LIGHTEST * lightest && p95 >= TIMES_THE_PREVIOUS * previous
					&& p95 - lightest >= MS_ABOVE_THE_LIGHTEST;
		}

		/** What happens at the knee, in a verdict's words. */
		String what() {
			return (this.reason == KneeReason.LATENCY) ? "latency climbs sharply" : "saturates";
		}

	}

}
