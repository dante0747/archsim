package io.archsim.model.distribution;

import java.util.List;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.MinItems;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/**
 * A measured distribution: a quantile table, linear between its points. The probabilities
 * rise strictly from 0 to 1 (both ends included) and the values never fall, as read off a
 * latency histogram's buckets.
 *
 * @param quantiles the table's points, by rising probability
 */
@Tag("empirical")
@Description("""
		A measured distribution: a quantile table, linear between its points.

		``quantiles`` holds ``[p, ms]`` pairs: ``p`` rises strictly from 0 to 1 (both ends included)
		and the values never fall, e.g. read off a latency histogram's buckets. Sampling draws ``u``
		from the seeded RNG and interpolates, so runs stay reproducible.""")
public record Empirical(@MinItems(2) @MaxItems(200) List<Quantile> quantiles) implements Distribution {

	/** The probability of the median, which the description shows. */
	private static final double MEDIAN = 0.5;

	/** The probability of the 95th percentile, which the description shows. */
	private static final double P95 = 0.95;

	public Empirical {
		quantiles = List.copyOf(quantiles);
		// each comparison below is written so that NaN fails it
		if (quantiles.getFirst().p() != 0.0 || quantiles.getLast().p() != 1.0) {
			throw new IllegalArgumentException("empirical quantiles must start at p = 0 and end at p = 1");
		}
		for (int i = 1; i < quantiles.size(); i++) {
			if (!(quantiles.get(i).p() > quantiles.get(i - 1).p())) {
				throw new IllegalArgumentException("empirical quantile probabilities must rise strictly");
			}
		}
		for (int i = 1; i < quantiles.size(); i++) {
			if (!(quantiles.get(i).ms() >= quantiles.get(i - 1).ms())) {
				throw new IllegalArgumentException("empirical quantile values must not fall");
			}
		}
		if (!(quantiles.getFirst().ms() >= 0 && quantiles.getLast().ms() <= MAX_SAMPLE_MS)) {
			throw new IllegalArgumentException(
					"empirical values must be between 0 and %s ms".formatted(PythonFormat.format(MAX_SAMPLE_MS, "g")));
		}
	}

	/**
	 * The value at a probability, linear between the table's points.
	 * @param probability the probability, from 0 to 1
	 * @return the value, in milliseconds
	 */
	public double quantile(double probability) {
		int segment = Math.min(Math.max(bisectRight(probability) - 1, 0), this.quantiles.size() - 2);
		Quantile lower = this.quantiles.get(segment);
		Quantile upper = this.quantiles.get(segment + 1);
		return lower.ms() + (upper.ms() - lower.ms()) * (probability - lower.p()) / (upper.p() - lower.p());
	}

	/**
	 * Python's {@code bisect_right} on the probabilities: how many are at most
	 * {@code probability}.
	 * @param probability the probability looked for
	 * @return the number of points whose probability is at most it
	 */
	private int bisectRight(double probability) {
		int low = 0;
		int high = this.quantiles.size();
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (probability < this.quantiles.get(middle).p()) {
				high = middle;
			}
			else {
				low = middle + 1;
			}
		}
		return low;
	}

	@Override
	public double expected() {
		double sum = 0;
		for (int i = 1; i < this.quantiles.size(); i++) {
			Quantile lower = this.quantiles.get(i - 1);
			Quantile upper = this.quantiles.get(i);
			sum += (upper.p() - lower.p()) * (lower.ms() + upper.ms()) / 2;
		}
		return sum;
	}

	@Override
	public String describe() {
		return "empirical, p50 %s ms / p95 %s ms (%d points)".formatted(PythonFormat.format(quantile(MEDIAN), ".4g"),
				PythonFormat.format(quantile(P95), ".4g"), this.quantiles.size());
	}
}
