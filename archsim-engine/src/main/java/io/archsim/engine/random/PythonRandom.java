package io.archsim.engine.random;

import java.math.BigInteger;

/**
 * A random number generator that draws exactly what Python's {@code random.Random} draws
 * from the same seed: the Mersenne Twister, Python's 53-bit {@code random()} and its
 * samplers, step for step. Logarithms and exponentials are {@link StrictMath}'s, so a
 * seed gives the same values on every platform; they may differ from the C library Python
 * calls in the last place. A generator has state and is not thread-safe: a run has one
 * per stream, used by its one thread.
 */
public final class PythonRandom {

	/** {@code 4 * exp(-0.5) / sqrt(2.0)}, as Python computes it. */
	private static final double NORMAL_MAGIC = 0x1.b72cd3f331398p+0;

	/**
	 * The normal sampler accepts a try when {@code z * z / 4} is at most {@code -log(u)}.
	 */
	private static final double ACCEPTANCE_DIVISOR = 4.0;

	private static final double HALF = 0.5;

	private static final int WORD_BITS = 32;

	private static final int HIGH_BITS = 27;

	private static final int LOW_BITS = 26;

	private static final double HIGH_SCALE = 0x1p26;

	private static final double UNIT = 0x1p-53;

	private static final long WORD_MASK = 0xffffffffL;

	private final MersenneTwister twister;

	private PythonRandom(MersenneTwister twister) {
		this.twister = twister;
	}

	/**
	 * A generator seeded as {@code random.Random(seed)} seeds one: the seed's absolute
	 * value, cut into 32-bit words from the least significant.
	 * @param seed the integer seed
	 * @return the generator
	 */
	public static PythonRandom seeded(BigInteger seed) {
		BigInteger magnitude = seed.abs();
		int words = Math.max(1, (magnitude.bitLength() + WORD_BITS - 1) / WORD_BITS);
		int[] key = new int[words];
		for (int word = 0; word < words; word++) {
			key[word] = magnitude.shiftRight(word * WORD_BITS).intValue();
		}
		return new PythonRandom(new MersenneTwister(key));
	}

	/**
	 * The next float in [0, 1), as Python's {@code random()}: 53 random bits from two
	 * words.
	 * @return the float
	 */
	public double nextDouble() {
		int high = this.twister.next() >>> (WORD_BITS - HIGH_BITS);
		int low = this.twister.next() >>> (WORD_BITS - LOW_BITS);
		return (high * HIGH_SCALE + low) * UNIT;
	}

	/**
	 * The next {@code bits} random bits, as Python's {@code getrandbits(bits)}: the top
	 * bits of one word.
	 * @param bits how many, 1 to 32
	 * @return the bits, as a non-negative number
	 * @throws IllegalArgumentException for fewer than 1 or more than 32 bits
	 */
	public long nextBits(int bits) {
		if (bits < 1 || bits > WORD_BITS) {
			throw new IllegalArgumentException("nextBits takes 1 to 32 bits; got " + bits);
		}
		return (this.twister.next() & WORD_MASK) >>> (WORD_BITS - bits);
	}

	/**
	 * A random integer from 0 to {@code bound - 1}, as Python's {@code randrange(bound)}:
	 * as many bits as {@code bound} has, drawn again while the value is too big.
	 * @param bound the end, at least 1
	 * @return the integer
	 * @throws IllegalArgumentException for an empty range
	 */
	public int below(int bound) {
		if (bound < 1) {
			throw new IllegalArgumentException("empty range for randrange()");
		}
		int bits = Integer.SIZE - Integer.numberOfLeadingZeros(bound);
		long value = nextBits(bits);
		while (value >= bound) {
			value = nextBits(bits);
		}
		return (int) value;
	}

	/**
	 * A float between {@code low} and {@code high}, as Python's
	 * {@code uniform(low, high)}.
	 * @param low one end
	 * @param high the other end
	 * @return the float
	 */
	public double uniform(double low, double high) {
		return low + (high - low) * nextDouble();
	}

	/**
	 * An exponentially distributed float, as Python's {@code expovariate(rate)}.
	 * @param rate one over the mean
	 * @return the float
	 */
	public double exponential(double rate) {
		return -StrictMath.log(1.0 - nextDouble()) / rate;
	}

	/**
	 * A normally distributed float, as Python's {@code normalvariate(mean, deviation)}:
	 * Kinderman and Monahan's ratio-of-uniforms method, two draws per try.
	 * @param mean the mean
	 * @param deviation the standard deviation
	 * @return the float
	 */
	public double normal(double mean, double deviation) {
		while (true) {
			double first = nextDouble();
			double second = 1.0 - nextDouble();
			double normal = NORMAL_MAGIC * (first - HALF) / second;
			if (normal * normal / ACCEPTANCE_DIVISOR <= -StrictMath.log(second)) {
				return mean + normal * deviation;
			}
		}
	}

	/**
	 * A log-normally distributed float, as Python's
	 * {@code lognormvariate(mean, deviation)}.
	 * @param mean the mean of the underlying normal distribution
	 * @param deviation its standard deviation
	 * @return the float
	 */
	public double logNormal(double mean, double deviation) {
		return StrictMath.exp(normal(mean, deviation));
	}

}
