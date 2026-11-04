package io.archsim.engine.queueing;

import java.util.stream.LongStream;

import io.archsim.engine.numeric.ExactSum;

/**
 * Zipf key popularity: the key of rank {@code k} is requested in proportion to
 * {@code 1 / k^skew}.
 */
public final class Zipf {

	/**
	 * The keys of ranks {@code a} to {@code b} weigh about as much as the integral of
	 * {@code x^-skew} from {@code a - HALF_RANK} to {@code b + HALF_RANK}.
	 */
	public static final double HALF_RANK = 0.5;

	/** Up to this many keys, the harmonic number is summed exactly. */
	private static final long EXACTLY_SUMMED_KEYS = 10_000;

	/** Where the integral that approximates the rest of the harmonic number starts. */
	private static final double TAIL_START = EXACTLY_SUMMED_KEYS + HALF_RANK;

	/** How close to 1 a skew is taken as 1, whose integral is a logarithm. */
	private static final double SKEW_OF_ONE = 1e-9;

	private Zipf() {
	}

	/**
	 * The share of requests that go to the {@code top} most popular of {@code keys} keys:
	 * exact up to 10,000 keys, and beyond that with the rest of the harmonic number
	 * approximated by its integral.
	 * @param keys how many keys there are
	 * @param skew the Zipf exponent
	 * @param top how many of the most popular keys
	 * @return their share of the requests, from 0 to 1
	 */
	public static double share(long keys, double skew, long top) {
		if (keys <= 0) {
			return 0.0;
		}
		double total = (keys <= EXACTLY_SUMMED_KEYS) ? harmonic(keys, skew)
				: harmonic(EXACTLY_SUMMED_KEYS, skew) + tail(keys, skew);
		double topShare = harmonic(Math.min(top, keys), skew);
		return (total > 0) ? topShare / total : 0.0;
	}

	/**
	 * The integral of {@code x^-skew}, the popularity of the keys it spans.
	 * @param from where it starts
	 * @param to where it ends
	 * @param skew the Zipf exponent
	 * @return the integral from {@code from} to {@code to}
	 */
	public static double popularity(double from, double to, double skew) {
		if (Math.abs(skew - 1.0) < SKEW_OF_ONE) {
			return Math.log(to / from);
		}
		return (Math.pow(to, 1 - skew) - Math.pow(from, 1 - skew)) / (1 - skew);
	}

	/**
	 * The generalised harmonic number.
	 * @param count how many terms
	 * @param skew the exponent
	 * @return the sum of {@code 1 / k^skew} for {@code k} up to {@code count}
	 */
	private static double harmonic(long count, double skew) {
		return ExactSum.of(LongStream.rangeClosed(1, count).mapToDouble((rank) -> Math.pow((double) rank, -skew)));
	}

	/**
	 * The rest of the harmonic number beyond 10,000 keys, approximated by an integral
	 * from 10,000.5: to {@code keys + 0.5}, except for a skew of one, whose logarithm
	 * stops at {@code keys}.
	 * @param keys how many keys there are
	 * @param skew the exponent
	 * @return the integral
	 */
	private static double tail(long keys, double skew) {
		if (Math.abs(skew - 1.0) < SKEW_OF_ONE) {
			// the Python engine's quirk, kept so shares match: it misses the last half
			// rank
			return Math.log(keys / TAIL_START);
		}
		return popularity(TAIL_START, keys + HALF_RANK, skew);
	}

}
