package io.archsim.engine.simulation;

import java.util.List;

/**
 * Whether a sampled run reached a steady state, so that its figures stand for the
 * design's ({@link Stationarity}).
 */
public sealed interface SteadyState permits SteadyState.Judged, SteadyState.Unjudged {

	/**
	 * Whether the run reached a steady state.
	 * @return whether its measured window's two halves agree
	 */
	boolean reached();

	/**
	 * How far the measured window's two halves agree.
	 *
	 * @param throughputDrift how far the second half's successful throughput is from the
	 * first's, as a share of the first's, to four decimals
	 * @param keepsUp the successful throughput over the offered load, to four decimals
	 * @param p95Drift how far the second half's p95 latency is from the first's, as a
	 * share of the first's, to four decimals
	 * @param growing each part's series still growing, as {@code part.series}
	 */
	record Judged(double throughputDrift, double keepsUp, double p95Drift,
			List<String> growing) implements SteadyState {

		/** The most the throughput may drift in a steady state. */
		private static final double MAX_THROUGHPUT_DRIFT = 0.03;

		/**
		 * The least share of the offered load a steady state serves: nothing piles up.
		 */
		private static final double MIN_KEEPS_UP = 0.97;

		/** The most the p95 latency may drift in a steady state. */
		private static final double MAX_P95_DRIFT = 0.15;

		/**
		 * Copies the growing series.
		 * @param throughputDrift the throughput's drift
		 * @param keepsUp the share of the offered load served
		 * @param p95Drift the p95 latency's drift
		 * @param growing the series still growing
		 */
		public Judged {
			growing = List.copyOf(growing);
		}

		/**
		 * Whether the halves agree: throughput within 3% and at least 97% of the offered
		 * load, p95 within 15%, and nothing still growing.
		 * @return whether the run reached a steady state
		 */
		@Override
		public boolean reached() {
			return this.throughputDrift <= MAX_THROUGHPUT_DRIFT && this.keepsUp >= MIN_KEEPS_UP
					&& this.p95Drift <= MAX_P95_DRIFT && this.growing.isEmpty();
		}

	}

	/**
	 * A run too short to judge.
	 *
	 * @param reason why it cannot be judged
	 */
	record Unjudged(String reason) implements SteadyState {

		@Override
		public boolean reached() {
			return false;
		}

	}

}
