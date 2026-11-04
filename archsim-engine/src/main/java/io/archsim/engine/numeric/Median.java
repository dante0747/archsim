package io.archsim.engine.numeric;

import java.util.stream.DoubleStream;

/** The median of some values, as Python's {@code statistics.median} takes it. */
public final class Median {

	private Median() {
	}

	/**
	 * The middle value, or the mean of the two middle values when there is an even
	 * number.
	 * @param values the values, in any order
	 * @return their median
	 * @throws IllegalArgumentException when there are no values
	 */
	public static double of(DoubleStream values) {
		double[] sorted = values.sorted().toArray();
		if (sorted.length == 0) {
			throw new IllegalArgumentException("no median of no values");
		}
		int middle = sorted.length / 2;
		return (sorted.length % 2 == 1) ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
	}

}
