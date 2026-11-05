package io.archsim.engine.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.numeric.TrueDivision;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.ReportTimeseries;
import io.archsim.engine.report.RunReport;

/**
 * Whether a sampled run reached a steady state: the measured window's two halves must
 * agree on throughput and p95 latency, the run must keep up with its offered load, and no
 * part's queue, backlog, lag or replica or node count may still be growing.
 */
public final class Stationarity {

	/** The parts' series that must not still be growing. */
	private static final List<String> GROWING = List.of("queueLength", "backlog", "lag", "replicas", "nodes");

	/** The fewest buckets with a value a series needs to be judged. */
	private static final int FEWEST_BUCKETS = 4;

	/**
	 * A series grows when its second half is above its first half times this, plus
	 * {@link #GROWTH_ABOVE}.
	 */
	private static final double GROWTH = 1.2;

	/**
	 * What a growing series' second half is above its first half times {@link #GROWTH}.
	 */
	private static final double GROWTH_ABOVE = 0.5;

	/** Stands in for a zero divisor. */
	private static final double TINY = 1e-9;

	/** The decimals the drifts are rounded to. */
	private static final int DECIMALS = 4;

	private Stationarity() {
	}

	/**
	 * Judges a run.
	 * @param report the run's report
	 * @return how far its halves agree, or why they cannot be judged
	 * @throws ArithmeticException when the report's buckets have no length, as the Python
	 * engine raises {@code ZeroDivisionError}
	 */
	public static SteadyState judge(RunReport report) {
		Window window = Window.of(report);
		ReportTimeseries series = report.timeseries();
		Halves throughput = window.halves(series.throughputRps());
		Halves offered = window.halves(series.offeredRps());
		Halves p95 = window.halves(series.latencyP95Ms());
		if (throughput == null || offered == null || p95 == null) {
			return new SteadyState.Unjudged("too few time buckets to judge");
		}
		double keepsUp = (throughput.first() + throughput.second())
				/ Math.max(offered.first() + offered.second(), TINY);
		return new SteadyState.Judged(throughput.drift(), PythonRound.round(keepsUp, DECIMALS), p95.drift(),
				growing(report.nodes(), window));
	}

	private static List<String> growing(List<ReportNode> nodes, Window window) {
		List<String> growing = new ArrayList<>();
		for (ReportNode node : nodes) {
			for (String name : GROWING) {
				Halves halves = window.halves(node.series(name));
				if (halves != null && halves.second() > halves.first() * GROWTH + GROWTH_ABOVE) {
					growing.add(node.id() + "." + name);
				}
			}
		}
		return growing;
	}

	/**
	 * The measured window's buckets.
	 *
	 * @param from the first bucket after the warm-up
	 * @param to the bucket after the last one, at least two after the first
	 */
	private record Window(int from, int to) {

		static Window of(RunReport report) {
			double bucket = report.timeseries().bucketS();
			int from = (int) Math.ceil(TrueDivision.of(report.window().warmupS(), bucket));
			int to = (int) Math.floor(TrueDivision.of(report.window().durationS(), bucket));
			return new Window(from, Math.max(from + 2, to));
		}

		/**
		 * The means of a series' two halves in the window, its gaps left out.
		 * @param series the series
		 * @return the halves' means, or {@code null} when fewer than four buckets have a
		 * value
		 */
		@Nullable Halves halves(List<@Nullable Double> series) {
			int size = series.size();
			List<Double> values = series.subList(Math.clamp(this.from, 0, size), Math.clamp(this.to, 0, size))
				.stream()
				.filter(Objects::nonNull)
				.toList();
			if (values.size() < FEWEST_BUCKETS) {
				return null;
			}
			int half = values.size() / 2;
			List<Double> second = values.subList(half, values.size());
			return new Halves(sum(values.subList(0, half)) / half, sum(second) / second.size());
		}

		private static double sum(List<Double> values) {
			return FloatSum.of(values.stream().mapToDouble(Double::doubleValue));
		}

	}

	/**
	 * The means of a series' two halves.
	 *
	 * @param first the first half's
	 * @param second the second half's
	 */
	private record Halves(double first, double second) {

		/**
		 * How far the second half is from the first.
		 * @return the difference as a share of the first half's mean, to four decimals
		 */
		double drift() {
			return PythonRound.round(Math.abs(this.second - this.first) / Math.max(this.first, TINY), DECIMALS);
		}

	}

}
