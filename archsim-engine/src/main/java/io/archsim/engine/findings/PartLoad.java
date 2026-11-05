package io.archsim.engine.findings;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.NodeSummary;

/**
 * How a part's load reads: its utilization, the calls an injected outage refused (the
 * outage, not overload), the share of requests its load rejected, and whether its queue
 * kept growing.
 */
final class PartLoad {

	/** The fewest buckets a queue's trend is read from. */
	private static final int FEWEST_BUCKETS = 4;

	/** A queue grows when its last quarter averages at least this many requests... */
	private static final double GROWN_TO = 2.0;

	/** ... and more than this times its first quarter, plus one. */
	private static final double GROWTH = 1.5;

	private PartLoad() {
	}

	/**
	 * A part's utilization: the share of its slots that were busy.
	 * @param summary the part's figures
	 * @return its utilization; zero when it was not measured
	 */
	static double utilization(NodeSummary summary) {
		return Objects.requireNonNullElse(summary.utilization(), 0.0);
	}

	/**
	 * The calls a part refused because an injected outage had it down.
	 * @param summary the part's figures
	 * @return how many
	 */
	static long outageRefusals(NodeSummary summary) {
		return summary.rejectedFor(FailureReason.OUTAGE.json());
	}

	/**
	 * The requests a part's load rejected, as a share of the calls that reached it while
	 * it was up: calls an injected outage refused say nothing about whether it carries
	 * its load.
	 * @param summary the part's figures
	 * @return the share; zero when no call reached it up
	 */
	static double loadRejectionRate(NodeSummary summary) {
		long refused = outageRefusals(summary);
		long served = Objects.requireNonNullElse(summary.arrivals(), 0L) - refused;
		long rejected = Objects.requireNonNullElse(summary.rejectedTotal(), 0L) - refused;
		return (served > 0) ? (double) rejected / served : 0.0;
	}

	/**
	 * Whether a queue kept growing over the measured window: its last quarter is above
	 * its first quarter by half and one more, and at least two requests.
	 * @param queue the queue's mean length per bucket
	 * @param from the first bucket of the window
	 * @param to the bucket after its last
	 * @return whether it kept growing
	 */
	static boolean queueGrowing(List<@Nullable Double> queue, int from, int to) {
		int size = queue.size();
		List<Double> window = queue.subList(Math.clamp(from, 0, size), Math.clamp(Math.max(from, to), 0, size))
			.stream()
			.map((value) -> (value != null) ? value : 0.0)
			.toList();
		if (window.size() < FEWEST_BUCKETS) {
			return false;
		}
		int quarter = Math.max(1, window.size() / FEWEST_BUCKETS);
		double first = sum(window.subList(0, quarter)) / quarter;
		double last = sum(window.subList(window.size() - quarter, window.size())) / quarter;
		return last > Math.max(GROWN_TO, GROWTH * first + 1.0);
	}

	private static double sum(List<Double> values) {
		return FloatSum.of(values.stream().mapToDouble(Double::doubleValue));
	}

}
