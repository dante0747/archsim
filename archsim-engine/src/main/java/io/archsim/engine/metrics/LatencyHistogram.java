package io.archsim.engine.metrics;

import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Latencies in log-sized bins: bin {@code k} holds {@code [0.001 * 1.02^k, 0.001 *
 * 1.02^(k+1))} ms, so a percentile is read to about 1%; bin -1 holds everything below a
 * microsecond. Memory grows with the spread of the latencies, never with their number.
 */
public final class LatencyHistogram {

	/** The lower edge of bin 0, in milliseconds. */
	private static final double MIN_MS = 0.001;

	/** How much wider each bin is than the one below. */
	private static final double GROWTH = 1.02;

	private static final double LOG_GROWTH = StrictMath.log(GROWTH);

	private static final double HALF_BIN = 0.5;

	private static final double PERCENT = 100.0;

	private long[] counts = new long[0];

	private int lowestBin;

	private long count;

	private double sum;

	private double min = Double.POSITIVE_INFINITY;

	private double max;

	/**
	 * Records a latency.
	 * @param latency milliseconds
	 * @throws IllegalArgumentException for a latency that is not a finite number
	 */
	public void record(double latency) {
		if (!Double.isFinite(latency)) {
			throw new IllegalArgumentException("a latency is a finite number of milliseconds; got " + latency);
		}
		int bin = (latency < MIN_MS) ? -1 : (int) (StrictMath.log(latency / MIN_MS) / LOG_GROWTH);
		add(bin, 1);
		this.count++;
		this.sum += latency;
		this.min = Math.min(this.min, latency);
		this.max = Math.max(this.max, latency);
	}

	/**
	 * Adds another histogram's latencies to this one.
	 * @param other the other histogram
	 */
	public void merge(LatencyHistogram other) {
		for (int index = 0; index < other.counts.length; index++) {
			if (other.counts[index] != 0) {
				add(other.lowestBin + index, other.counts[index]);
			}
		}
		this.count += other.count;
		this.sum += other.sum;
		this.min = Math.min(this.min, other.min);
		this.max = Math.max(this.max, other.max);
	}

	/**
	 * The latency at a percentile: the middle of the bin it falls in, kept between the
	 * smallest and largest latency recorded.
	 * @param percentile from 0 to 100; 100 and above give the largest latency
	 * @return the latency, empty when nothing was recorded
	 */
	public OptionalDouble percentile(double percentile) {
		if (this.count == 0) {
			return OptionalDouble.empty();
		}
		if (percentile >= PERCENT) {
			return OptionalDouble.of(this.max);
		}
		double rank = percentile / PERCENT * this.count;
		long seen = 0;
		for (int index = 0; index < this.counts.length; index++) {
			seen += this.counts[index];
			if (this.counts[index] != 0 && seen >= rank) {
				return OptionalDouble.of(clamped(this.lowestBin + index));
			}
		}
		return OptionalDouble.of(this.max);
	}

	/**
	 * The mean latency.
	 * @return milliseconds, empty when nothing was recorded
	 */
	public OptionalDouble mean() {
		return (this.count != 0) ? OptionalDouble.of(this.sum / this.count) : OptionalDouble.empty();
	}

	/**
	 * The count, mean, p50, p90, p95, p99 and largest latency, each rounded to 4
	 * decimals.
	 * @return the summary
	 */
	public LatencySummary summary() {
		OptionalDouble largest = (this.count != 0) ? OptionalDouble.of(this.max) : OptionalDouble.empty();
		return LatencySummary.of(this.count, mean(), this::percentile, largest);
	}

	/**
	 * How many latencies were recorded.
	 * @return the count
	 */
	public long count() {
		return this.count;
	}

	double sum() {
		return this.sum;
	}

	double max() {
		return this.max;
	}

	/**
	 * The bins that hold latencies.
	 * @return each bin's number (-1 below a microsecond) and how many latencies it holds,
	 * lowest first
	 */
	SortedMap<Integer, Long> bins() {
		SortedMap<Integer, Long> bins = new TreeMap<>();
		for (int index = 0; index < this.counts.length; index++) {
			if (this.counts[index] != 0) {
				bins.put(this.lowestBin + index, this.counts[index]);
			}
		}
		return bins;
	}

	/**
	 * Calls {@code visitor} with each bin that holds latencies, lowest first.
	 * @param visitor what to call with the bin's value (its middle, kept between the
	 * smallest and largest latency) and its count
	 */
	void forEachBin(BinVisitor visitor) {
		for (int index = 0; index < this.counts.length; index++) {
			if (this.counts[index] != 0) {
				visitor.visit(clamped(this.lowestBin + index), this.counts[index]);
			}
		}
	}

	private double clamped(int bin) {
		return Math.min(Math.max(binValue(bin), this.min), this.max);
	}

	private static double binValue(int bin) {
		return (bin < 0) ? 0.0 : MIN_MS * StrictMath.pow(GROWTH, bin + HALF_BIN);
	}

	private void add(int bin, long added) {
		if (this.counts.length == 0) {
			this.counts = new long[1];
			this.lowestBin = bin;
		}
		else if (bin < this.lowestBin) {
			long[] grown = new long[this.counts.length + this.lowestBin - bin];
			System.arraycopy(this.counts, 0, grown, this.lowestBin - bin, this.counts.length);
			this.counts = grown;
			this.lowestBin = bin;
		}
		else if (bin >= this.lowestBin + this.counts.length) {
			this.counts = Arrays.copyOf(this.counts, bin - this.lowestBin + 1);
		}
		this.counts[bin - this.lowestBin] += added;
	}

	/**
	 * What {@link #forEachBin} calls.
	 */
	@FunctionalInterface
	interface BinVisitor {

		/**
		 * One bin.
		 * @param value the bin's latency
		 * @param count how many latencies it holds
		 */
		void visit(double value, long count);

	}

}
