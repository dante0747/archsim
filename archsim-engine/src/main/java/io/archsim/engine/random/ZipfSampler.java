package io.archsim.engine.random;

/**
 * Draws key ranks 0 to {@code keys - 1}, rank {@code k} with a probability proportional
 * to {@code 1 / (k + 1)^skew}: one draw, found in the cumulative weights by bisection.
 */
public final class ZipfSampler {

	private final double[] cumulative;

	private final double total;

	/**
	 * A sampler of key ranks.
	 * @param keys how many keys, at least 1
	 * @param skew the Zipf exponent
	 * @throws IllegalArgumentException for fewer than one key
	 */
	public ZipfSampler(int keys, double skew) {
		if (keys < 1) {
			throw new IllegalArgumentException("a Zipf sampler needs at least one key; got " + keys);
		}
		this.cumulative = new double[keys];
		double sum = 0;
		for (int rank = 0; rank < keys; rank++) {
			sum += StrictMath.pow(rank + 1, -skew);
			this.cumulative[rank] = sum;
		}
		this.total = sum;
	}

	/**
	 * The next key rank.
	 * @param random the stream to draw from
	 * @return the rank, from 0
	 */
	public int sample(PythonRandom random) {
		return bisectLeft(random.nextDouble() * this.total);
	}

	private int bisectLeft(double target) {
		int low = 0;
		int high = this.cumulative.length;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (this.cumulative[middle] < target) {
				low = middle + 1;
			}
			else {
				high = middle;
			}
		}
		return low;
	}

}
