package io.archsim.engine.calibration;

import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;

/**
 * A time's UTF-8 bytes as Python 3.12's C {@code datetime.fromisoformat} reads them: past
 * the end, the terminating zero.
 */
final class IsoBytes {

	/** The fewest characters of any ISO 8601 date. */
	static final int SHORTEST = 7;

	/** Where a surrogate stands for the separator, the only place Python allows one. */
	private static final int[] SURROGATE_SEPARATORS = { 7, 8, 10 };

	private static final int DECIMAL = 10;

	private final byte[] value;

	private IsoBytes(byte[] value) {
		this.value = value;
	}

	/**
	 * A text's bytes, with a surrogate where the separator can be read as {@code T}, as
	 * Python's {@code _sanitize_isoformat_str} does.
	 * @param text the text
	 * @return its bytes; {@code null} when it is too short or has another surrogate
	 */
	static @Nullable IsoBytes of(String text) {
		int[] codePoints = text.codePoints().toArray();
		if (codePoints.length < SHORTEST) {
			return null;
		}
		for (int position : SURROGATE_SEPARATORS) {
			if (position >= codePoints.length) {
				break;
			}
			if (isSurrogate(codePoints[position])) {
				codePoints[position] = 'T';
				break;
			}
		}
		for (int codePoint : codePoints) {
			if (isSurrogate(codePoint)) {
				return null;
			}
		}
		return new IsoBytes(new String(codePoints, 0, codePoints.length).getBytes(StandardCharsets.UTF_8));
	}

	private static boolean isSurrogate(int codePoint) {
		return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
	}

	/**
	 * Whether a byte is a decimal digit.
	 * @param at the byte
	 * @return whether it is {@code 0} to {@code 9}
	 */
	static boolean isDigit(int at) {
		return at >= '0' && at <= '9';
	}

	int length() {
		return this.value.length;
	}

	/**
	 * A byte, unsigned.
	 * @param index its index
	 * @return the byte; {@code 0} past the end
	 */
	int at(int index) {
		return (index < this.value.length) ? Byte.toUnsignedInt(this.value[index]) : 0;
	}

	/**
	 * A number of decimal digits, as the C code's {@code parse_digits} reads them.
	 * @param start where they start
	 * @param count how many
	 * @return their value; {@code -1} when one is not a digit
	 */
	int digits(int start, int count) {
		int number = 0;
		for (int index = start; index < start + count; index++) {
			if (!isDigit(at(index))) {
				return -1;
			}
			number = number * DECIMAL + at(index) - '0';
		}
		return number;
	}

	/**
	 * Whether a date's fields are separated as its first two are.
	 * @param index where a dash would be
	 * @param dashed whether the first two are separated by a dash
	 * @return whether there is a dash there when there should be
	 */
	boolean dashAt(int index, boolean dashed) {
		return !dashed || at(index) == '-';
	}

}
