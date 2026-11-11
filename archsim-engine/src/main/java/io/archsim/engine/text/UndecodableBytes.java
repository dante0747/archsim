package io.archsim.engine.text;

import java.util.Objects;

/**
 * Where bytes stop being text: the bytes a codec cannot decode, and why, as CPython's
 * {@code UnicodeDecodeError} said it.
 *
 * @param start where the bytes a codec cannot decode start
 * @param end where they end, exclusive
 * @param first the first of them, unsigned
 * @param reason why the codec cannot decode them, in Python's words
 */
public record UndecodableBytes(int start, int end, int first, String reason) {

	/** The largest value of a byte, unsigned. */
	private static final int BYTE_MAX = 0xff;

	/**
	 * Bytes a codec cannot decode.
	 * @param start where they start
	 * @param end where they end, exclusive, past {@code start}
	 * @param first the first of them, unsigned
	 * @param reason why the codec cannot decode them
	 */
	public UndecodableBytes {
		if (start < 0 || end <= start) {
			throw new IllegalArgumentException("bytes from %d to %d".formatted(start, end));
		}
		if (first < 0 || first > BYTE_MAX) {
			throw new IllegalArgumentException("a byte of " + first);
		}
		Objects.requireNonNull(reason, "reason");
	}

	/**
	 * The bytes a codec cannot decode.
	 * @param bytes the bytes it read
	 * @param start where the bytes it cannot decode start
	 * @param end where they end, exclusive
	 * @param reason why it cannot, in Python's words
	 * @return the error
	 */
	public static UndecodableBytes at(byte[] bytes, int start, int end, String reason) {
		return new UndecodableBytes(start, end, Byte.toUnsignedInt(bytes[start]), reason);
	}

	/**
	 * The error in Python's words: {@code 'utf-8' codec can't decode byte 0xff in
	 * position 3: invalid start byte}.
	 * @param codec the codec's name, as Python names it
	 * @return the words
	 */
	public String describe(String codec) {
		if (this.end - this.start == 1) {
			return "'%s' codec can't decode byte 0x%02x in position %d: %s".formatted(codec, this.first, this.start,
					this.reason);
		}
		return "'%s' codec can't decode bytes in position %d-%d: %s".formatted(codec, this.start, this.end - 1,
				this.reason);
	}

}
