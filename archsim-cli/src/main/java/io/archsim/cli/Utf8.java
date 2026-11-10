package io.archsim.cli;

import java.util.Optional;

import io.archsim.cli.TextDecoding.DecodeError;

/**
 * Where bytes stop being UTF-8, found as Python's decoder finds it, with its words: a
 * byte that cannot start a character, one that cannot continue it (an overlong form, a
 * surrogate, a code point past U+10FFFF), or a character the bytes end inside.
 */
final class Utf8 {

	/** The codec's name, as Python names it. */
	static final String CODEC = "utf-8";

	/** The first byte that is not a character of its own. */
	private static final int FIRST_NON_ASCII = 0x80;

	/** The first byte that starts a character of two bytes (below it, overlong forms). */
	private static final int FIRST_TWO_BYTE_LEAD = 0xc2;

	/** The first byte that starts a character of three bytes. */
	private static final int FIRST_THREE_BYTE_LEAD = 0xe0;

	/** The first byte that starts a character of four bytes. */
	private static final int FIRST_FOUR_BYTE_LEAD = 0xf0;

	/** The first byte that starts no character: what follows would pass U+10FFFF. */
	private static final int FIRST_INVALID_LEAD = 0xf5;

	/** The bytes of a character from U+0800 to U+FFFF. */
	private static final int THREE_BYTE_LENGTH = 3;

	/** The bytes of a character from U+10000 to U+10FFFF. */
	private static final int FOUR_BYTE_LENGTH = 4;

	/**
	 * The lead byte of the surrogates' block, U+D800 to U+DFFF, which UTF-8 never holds.
	 */
	private static final int SURROGATE_LEAD = 0xed;

	/** The lead byte of U+100000 to U+10FFFF. */
	private static final int LAST_LEAD = 0xf4;

	/** The range of a byte that continues a character. */
	private static final int CONTINUATION_FIRST = 0x80;

	private static final int CONTINUATION_LAST = 0xbf;

	/**
	 * The second byte that ends the overlong forms after {@code 0xe0}, and the surrogates
	 * after {@code 0xed}.
	 */
	private static final int THREE_BYTE_BOUND = 0xa0;

	/**
	 * The second byte that ends the overlong forms after {@code 0xf0}, and U+10FFFF after
	 * {@code 0xf4}.
	 */
	private static final int FOUR_BYTE_BOUND = 0x90;

	private Utf8() {
	}

	/**
	 * Where the bytes stop being UTF-8.
	 * @param input the bytes
	 * @return the first error, positions counted from the first byte; empty for UTF-8
	 */
	static Optional<DecodeError> firstError(byte[] input) {
		return firstError(input, 0);
	}

	/**
	 * Where bytes stop being UTF-8, from a place on, in CPython's words.
	 * @param input the bytes
	 * @param from where to start reading them
	 * @return the first error from there, positions counted from the bytes' first; empty
	 * for UTF-8
	 */
	static Optional<DecodeError> firstError(byte[] input, int from) {
		int at = from;
		while (at < input.length) {
			int length = length(Byte.toUnsignedInt(input[at]));
			if (length == 0) {
				return Optional.of(DecodeError.at(input, at, at + 1, "invalid start byte"));
			}
			Optional<DecodeError> error = continuation(input, at, length);
			if (error.isPresent()) {
				return error;
			}
			at += length;
		}
		return Optional.empty();
	}

	// the bytes of the character a lead byte starts; 0 when it starts none
	private static int length(int lead) {
		if (lead < FIRST_NON_ASCII) {
			return 1;
		}
		if (lead < FIRST_TWO_BYTE_LEAD) {
			return 0;
		}
		if (lead < FIRST_THREE_BYTE_LEAD) {
			return 2;
		}
		if (lead < FIRST_FOUR_BYTE_LEAD) {
			return THREE_BYTE_LENGTH;
		}
		return (lead < FIRST_INVALID_LEAD) ? FOUR_BYTE_LENGTH : 0;
	}

	// each byte after the lead, in order: one that cannot continue it ends the error
	// before it; the bytes ending first leave the character unfinished
	private static Optional<DecodeError> continuation(byte[] input, int at, int length) {
		int lead = Byte.toUnsignedInt(input[at]);
		for (int index = 1; index < length; index++) {
			if (at + index >= input.length) {
				return Optional.of(DecodeError.at(input, at, input.length, "unexpected end of data"));
			}
			if (!continues(lead, index, Byte.toUnsignedInt(input[at + index]))) {
				return Optional.of(DecodeError.at(input, at, at + index, "invalid continuation byte"));
			}
		}
		return Optional.empty();
	}

	private static boolean continues(int lead, int index, int value) {
		if (value < CONTINUATION_FIRST || value > CONTINUATION_LAST) {
			return false;
		}
		if (index > 1) {
			return true;
		}
		return switch (lead) {
			case FIRST_THREE_BYTE_LEAD -> value >= THREE_BYTE_BOUND;
			case SURROGATE_LEAD -> value < THREE_BYTE_BOUND;
			case FIRST_FOUR_BYTE_LEAD -> value >= FOUR_BYTE_BOUND;
			case LAST_LEAD -> value < FOUR_BYTE_BOUND;
			default -> true;
		};
	}

}
