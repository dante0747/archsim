package io.archsim.engine.seeds;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * The seeds a group of runs of one design used.
 *
 * @param values the seeds, in the order the runs were made
 */
public record Seeds(List<Long> values) {

	/**
	 * Copies the seeds, so the record does not change.
	 */
	public Seeds {
		values = List.copyOf(values);
	}

	/**
	 * How the seeds read: {@code seed 42}, {@code seeds 42–46} when they are contiguous,
	 * else each of them: {@code seeds 1, 5, 9}.
	 * @return their text, empty when there are none
	 */
	public String describe() {
		if (this.values.isEmpty()) {
			return "";
		}
		long low = this.values.stream().mapToLong(Long::longValue).min().orElseThrow();
		long high = this.values.stream().mapToLong(Long::longValue).max().orElseThrow();
		if (low == high) {
			return "seed " + low;
		}
		if (isContiguous(low, high)) {
			return "seeds " + low + ValueRange.EN_DASH + high;
		}
		return "seeds " + this.values.stream().map(String::valueOf).collect(Collectors.joining(", "));
	}

	/**
	 * Whether the seeds, sorted, are every number from the lowest to the highest once.
	 */
	private boolean isContiguous(long low, long high) {
		if (this.values.size() != high - low + 1) {
			return false;
		}
		List<Long> sorted = this.values.stream().sorted().toList();
		return sorted.equals(LongStream.rangeClosed(low, high).boxed().toList());
	}

}
