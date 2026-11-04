package io.archsim.engine.seeds;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.Median;
import io.archsim.engine.text.NumberText;

/**
 * A figure's range across the runs of one design with different seeds.
 *
 * @param min the lowest value
 * @param max the highest value
 * @param median the median value
 */
public record ValueRange(double min, double max, double median) {

	/** Between the two ends of a range, as in {@code 98–131 ms}. */
	static final String EN_DASH = "–";

	/**
	 * The range of the values that are set.
	 * @param values one value per run, {@code null} where a run has none
	 * @return their range, or none when no value is set
	 */
	public static Optional<ValueRange> of(List<@Nullable Double> values) {
		List<Double> set = values.stream().filter(Objects::nonNull).toList();
		if (set.isEmpty()) {
			return Optional.empty();
		}
		double min = set.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
		double max = set.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
		return Optional.of(new ValueRange(min, max, Median.of(set.stream().mapToDouble(Double::doubleValue))));
	}

	/**
	 * The range of a latency as it reads: {@code p95 98–131 ms (median 104)}, or
	 * {@code p95 98 ms} when both ends read the same.
	 * @param label what the latency is, such as {@code p95}
	 * @return its text
	 */
	public String text(String label) {
		String low = NumberText.milliseconds(this.min);
		String high = NumberText.milliseconds(this.max);
		if (low.equals(high)) {
			return label + " " + low + " ms";
		}
		return label + " " + low + EN_DASH + high + " ms (median " + NumberText.milliseconds(this.median) + ")";
	}

}
