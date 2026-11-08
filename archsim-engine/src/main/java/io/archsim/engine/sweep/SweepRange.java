package io.archsim.engine.sweep;

import java.util.ArrayList;
import java.util.List;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.text.HalfUp;
import io.archsim.model.document.Workload;

/**
 * The range of constant loads a sweep steps through to find where a design breaks.
 *
 * @param fromRps one end of the range, in requests per second
 * @param toRps the other end; either may be the lower
 */
public record SweepRange(double fromRps, double toRps) {

	/**
	 * The schema's highest offered load ({@code workload.rps}): a step above it cannot
	 * run.
	 */
	public static final double MAX_RPS = 100_000.0;

	/** The most steps a sweep takes. */
	public static final int MAX_STEPS = 10;

	/** A breaking-point sweep's steps run at least this long, in seconds. */
	static final double SHORTEST_SWEEP_STEP_S = 2.0;

	/** The load a sweep is centred on when the design has none. */
	private static final double DEFAULT_RPS = 100.0;

	/** A suggested range starts at this share of the load ... */
	private static final double LOW_SHARE = 0.5;

	/** ... and ends at this multiple of it ... */
	private static final double HIGH_MULTIPLE = 3;

	/** ... on round numbers about this share of the load apart. */
	private static final double STEP_SHARE = 0.25;

	/** A round step is one of these times a power of ten, or the next power of ten. */
	private static final List<Double> ROUND_MULTIPLES = List.of(1.0, 2.0, 2.5, 5.0);

	/** Round steps are multiples of powers of this. */
	private static final double DECIMAL_BASE = 10;

	/** The lowest load a step runs. */
	private static final double LOWEST_RPS = 0.1;

	/**
	 * A step of no duration counts as this long, so the cap is a division by something.
	 */
	private static final double SHORTEST_STEP_S = 1e-9;

	/** Loads from here on are rounded to multiples of {@link #COARSE_ROUNDING}. */
	private static final double COARSE_FROM = 100;

	private static final double COARSE_ROUNDING = 5;

	/** Loads from here on, below {@link #COARSE_FROM}, are rounded to whole numbers. */
	private static final double WHOLE_FROM = 10;

	/** Tenths in one: smaller loads are rounded to tenths. */
	private static final double TENTHS = 10;

	/**
	 * A default range around a design's load: half to three times it, on round numbers,
	 * and no higher than {@code cap}.
	 * @param currentRps the design's load, or zero when it has none
	 * @param cap the highest load a step may run ({@link #rpsCap})
	 * @return the range
	 */
	public static SweepRange suggested(double currentRps, double cap) {
		double base = (currentRps > 0) ? currentRps : DEFAULT_RPS;
		double step = roundStep(base * STEP_SHARE);
		double low = Math.max(step, HalfUp.javaScriptRound(base * LOW_SHARE / step) * step);
		double high = Math.max(low + step, HalfUp.javaScriptRound(base * HIGH_MULTIPLE / step) * step);
		return new SweepRange(Math.min(low, cap), Math.min(high, cap));
	}

	/**
	 * The range a breaking-point sweep steps through by default: around a design's load,
	 * within what a step of at least two seconds can run.
	 * @param workload the resolved design's workload
	 * @param limits the limits a run is held to
	 * @param stepSeconds how long each step runs
	 * @return the range
	 */
	public static SweepRange around(Workload workload, SimulationLimits limits, double stepSeconds) {
		return suggested(workload.rps(), rpsCap(limits, Math.max(SHORTEST_SWEEP_STEP_S, stepSeconds)));
	}

	/**
	 * The highest constant load a step can run: the schema's maximum, and no more
	 * requests than a run may make.
	 * @param limits the limits a run is held to
	 * @param durationSeconds how long a step runs
	 * @return the highest load, in requests per second
	 */
	public static double rpsCap(SimulationLimits limits, double durationSeconds) {
		return Math.min(MAX_RPS, limits.maxRequests() / Math.max(durationSeconds, SHORTEST_STEP_S));
	}

	/**
	 * The loads of the sweep's steps: evenly spaced from the lower end to the higher,
	 * rounded (to fives from 100, to whole numbers from 10, else to tenths), each once.
	 * @param steps how many steps; fewer than 2 take 2, more than {@link #MAX_STEPS} take
	 * that many
	 * @return the loads, from the lowest
	 */
	public List<Double> levels(int steps) {
		int count = Math.max(2, Math.min(MAX_STEPS, steps));
		double low = Math.max(LOWEST_RPS, Math.min(this.fromRps, this.toRps));
		double high = Math.max(this.fromRps, this.toRps);
		List<Double> levels = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double level = rounded(low + (high - low) * i / (count - 1));
			if (!levels.contains(level)) {
				levels.add(level);
			}
		}
		return levels;
	}

	private static double rounded(double level) {
		if (level >= COARSE_FROM) {
			return HalfUp.javaScriptRound(level / COARSE_ROUNDING) * COARSE_ROUNDING;
		}
		if (level >= WHOLE_FROM) {
			return HalfUp.javaScriptRound(level);
		}
		return HalfUp.javaScriptRound(level * TENTHS) / TENTHS;
	}

	/** A round step near {@code raw}: 1, 2, 2.5 or 5 times a power of ten. */
	private static double roundStep(double raw) {
		if (raw <= 0) {
			return 1.0;
		}
		double magnitude = StrictMath.pow(DECIMAL_BASE, Math.floor(StrictMath.log10(raw)));
		double ratio = raw / magnitude;
		double multiple = ROUND_MULTIPLES.stream().filter((round) -> ratio <= round).findFirst().orElse(DECIMAL_BASE);
		return multiple * magnitude;
	}

}
