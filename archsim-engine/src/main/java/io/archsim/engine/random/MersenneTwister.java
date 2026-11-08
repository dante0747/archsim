package io.archsim.engine.random;

/**
 * MT19937, the 32-bit Mersenne Twister of Matsumoto and Nishimura, seeded by an array of
 * words as {@code init_by_array} seeds it. Java's {@code int} arithmetic wraps modulo
 * 2^32, so the words are the reference's unsigned ones.
 */
final class MersenneTwister {

	private static final int SIZE = 624;

	/** The offset of the word a twist mixes in: MT19937's M. */
	private static final int MIDDLE_OFFSET = 397;

	private static final int MATRIX = 0x9908b0df;

	private static final int UPPER = 0x80000000;

	private static final int LOWER = 0x7fffffff;

	private static final int INITIAL_SEED = 19650218;

	private static final int SEED_MULTIPLIER = 1812433253;

	private static final int KEY_MULTIPLIER = 1664525;

	private static final int MIX_MULTIPLIER = 1566083941;

	private static final int SEED_SHIFT = 30;

	private static final int TEMPER_SHIFT_U = 11;

	private static final int TEMPER_SHIFT_S = 7;

	private static final int TEMPER_MASK_B = 0x9d2c5680;

	private static final int TEMPER_SHIFT_T = 15;

	private static final int TEMPER_MASK_C = 0xefc60000;

	private static final int TEMPER_SHIFT_L = 18;

	private final int[] state = new int[SIZE];

	private int index;

	/**
	 * A generator seeded with a key.
	 * @param key the seed's 32-bit words, least significant first; at least one
	 */
	MersenneTwister(int[] key) {
		seed(INITIAL_SEED);
		mix(key);
	}

	/**
	 * The next 32 random bits, as {@code genrand_uint32}.
	 * @return the bits, as an {@code int} whose bits are the unsigned word's
	 */
	int next() {
		if (this.index >= SIZE) {
			twist();
		}
		int word = this.state[this.index++];
		word ^= word >>> TEMPER_SHIFT_U;
		word ^= (word << TEMPER_SHIFT_S) & TEMPER_MASK_B;
		word ^= (word << TEMPER_SHIFT_T) & TEMPER_MASK_C;
		word ^= word >>> TEMPER_SHIFT_L;
		return word;
	}

	private void seed(int value) {
		int[] words = this.state;
		words[0] = value;
		for (int position = 1; position < SIZE; position++) {
			int previous = words[position - 1];
			words[position] = SEED_MULTIPLIER * (previous ^ (previous >>> SEED_SHIFT)) + position;
		}
		this.index = SIZE;
	}

	private void mix(int[] key) {
		int[] words = this.state;
		int position = 1;
		int keyIndex = 0;
		for (int round = Math.max(SIZE, key.length); round > 0; round--) {
			int previous = words[position - 1];
			words[position] = (words[position] ^ ((previous ^ (previous >>> SEED_SHIFT)) * KEY_MULTIPLIER))
					+ key[keyIndex] + keyIndex;
			position++;
			if (position >= SIZE) {
				words[0] = words[SIZE - 1];
				position = 1;
			}
			keyIndex = (keyIndex + 1 < key.length) ? keyIndex + 1 : 0;
		}
		for (int round = SIZE - 1; round > 0; round--) {
			int previous = words[position - 1];
			words[position] = (words[position] ^ ((previous ^ (previous >>> SEED_SHIFT)) * MIX_MULTIPLIER)) - position;
			position++;
			if (position >= SIZE) {
				words[0] = words[SIZE - 1];
				position = 1;
			}
		}
		words[0] = UPPER;
	}

	private void twist() {
		int[] words = this.state;
		for (int position = 0; position < SIZE; position++) {
			int bits = (words[position] & UPPER) | (words[(position + 1) % SIZE] & LOWER);
			int odd = ((bits & 1) != 0) ? MATRIX : 0;
			words[position] = words[(position + MIDDLE_OFFSET) % SIZE] ^ (bits >>> 1) ^ odd;
		}
		this.index = 0;
	}

}
