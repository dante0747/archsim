package io.archsim.cli;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.cli.TextDecoding.DecodeError;
import io.archsim.engine.text.PythonJsonReader;

/**
 * JSON read from bytes as Python's {@code json.loads(bytes)} reads it, as a check reads
 * its baseline at a git ref: the encoding found as {@code json.detect_encoding} finds it,
 * from a byte order mark or the zero bytes among the first four, then the bytes decoded
 * with {@code surrogatepass}, which lets a lone surrogate through, and the text read as
 * {@code json}'s decoder reads it ({@link PythonJsonReader#decode}), without the check
 * for a byte order mark that {@code json.loads} makes of text alone. Bytes that are not
 * text in that encoding are refused in CPython's codec words.
 */
final class PythonJsonBytes {

	private static final byte[] UTF16_BIG_MARK = { (byte) 0xFE, (byte) 0xFF };

	private static final byte[] UTF16_LITTLE_MARK = { (byte) 0xFF, (byte) 0xFE };

	private static final byte[] UTF32_BIG_MARK = { 0, 0, (byte) 0xFE, (byte) 0xFF };

	private static final byte[] UTF32_LITTLE_MARK = { (byte) 0xFF, (byte) 0xFE, 0, 0 };

	private static final int UTF16_UNIT = Character.BYTES;

	private static final int UTF32_UNIT = Integer.BYTES;

	private static final int LAST_OF_FOUR = UTF32_UNIT - 1;

	/**
	 * The bytes of a surrogate in UTF-8: 0xED, then 0xA0 to 0xBF, then a continuation.
	 */
	private static final int SURROGATE_LENGTH = 3;

	private static final int SURROGATE_LEAD = 0xED;

	private static final int SURROGATE_SECOND_LOW = 0xA0;

	private static final int CONTINUATION_TOP_BITS = 0xC0;

	private static final int CONTINUATION = 0x80;

	/** The bits of a code point a continuation byte holds. */
	private static final int CONTINUATION_BITS = 6;

	private static final int SIX_BITS = 0x3F;

	private static final int FOUR_BITS = 0x0F;

	private PythonJsonBytes() {
	}

	/**
	 * The JSON bytes hold.
	 * @param raw the bytes
	 * @return their value
	 * @throws PythonFileException when they are not text in their encoding, not JSON, or
	 * nest deeper than they are read
	 */
	static JsonNode read(byte[] raw) {
		return PythonFiles.json(PythonJsonReader::decode, decode(raw));
	}

	// bytes.decode(json.detect_encoding(raw), "surrogatepass")
	private static String decode(byte[] raw) {
		return switch (detect(raw)) {
			case UTF8 -> utf8(raw);
			// utf-8-sig: positions count from after the mark
			case UTF8_MARKED -> utf8(Arrays.copyOfRange(raw, TextDecoding.UTF8_MARK.length, raw.length));
			case UTF16_MARKED -> utf16(raw, UTF16_UNIT, orderOfMark(raw, UTF16_LITTLE_MARK));
			case UTF16_BIG -> utf16(raw, 0, ByteOrder.BIG_ENDIAN);
			case UTF16_LITTLE -> utf16(raw, 0, ByteOrder.LITTLE_ENDIAN);
			case UTF32_MARKED -> utf32(raw, UTF32_UNIT, orderOfMark(raw, UTF32_LITTLE_MARK));
			case UTF32_BIG -> utf32(raw, 0, ByteOrder.BIG_ENDIAN);
			case UTF32_LITTLE -> utf32(raw, 0, ByteOrder.LITTLE_ENDIAN);
		};
	}

	// json.detect_encoding: a byte order mark, UTF-32's before UTF-16's, which it starts
	// as; else the zero bytes ASCII text has in UTF-16 or UTF-32
	private static Encoding detect(byte[] raw) {
		if (TextDecoding.startsWith(raw, UTF32_BIG_MARK) || TextDecoding.startsWith(raw, UTF32_LITTLE_MARK)) {
			return Encoding.UTF32_MARKED;
		}
		if (TextDecoding.startsWith(raw, UTF16_BIG_MARK) || TextDecoding.startsWith(raw, UTF16_LITTLE_MARK)) {
			return Encoding.UTF16_MARKED;
		}
		if (TextDecoding.startsWith(raw, TextDecoding.UTF8_MARK)) {
			return Encoding.UTF8_MARKED;
		}
		return (raw.length >= UTF32_UNIT) ? byZerosOfFour(raw) : byZerosOfTwo(raw);
	}

	private static Encoding byZerosOfFour(byte[] raw) {
		if (raw[0] == 0) {
			return (raw[1] != 0) ? Encoding.UTF16_BIG : Encoding.UTF32_BIG;
		}
		if (raw[1] == 0) {
			return (raw[2] != 0 || raw[LAST_OF_FOUR] != 0) ? Encoding.UTF16_LITTLE : Encoding.UTF32_LITTLE;
		}
		return Encoding.UTF8;
	}

	private static Encoding byZerosOfTwo(byte[] raw) {
		if (raw.length != UTF16_UNIT) {
			return Encoding.UTF8;
		}
		if (raw[0] == 0) {
			return Encoding.UTF16_BIG;
		}
		return (raw[1] == 0) ? Encoding.UTF16_LITTLE : Encoding.UTF8;
	}

	private static ByteOrder orderOfMark(byte[] raw, byte[] littleMark) {
		return TextDecoding.startsWith(raw, littleMark) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
	}

	private static String utf8(byte[] raw) {
		StringBuilder text = new StringBuilder(raw.length);
		int from = 0;
		Optional<DecodeError> error = Utf8.firstError(raw, from);
		while (error.isPresent()) {
			DecodeError found = error.get();
			if (!isSurrogate(raw, found.start())) {
				throw new PythonFileException(found.describe(Utf8.CODEC));
			}
			text.append(new String(raw, from, found.start() - from, StandardCharsets.UTF_8))
				.append(surrogate(raw, found.start()));
			from = found.start() + SURROGATE_LENGTH;
			error = Utf8.firstError(raw, from);
		}
		return text.append(new String(raw, from, raw.length - from, StandardCharsets.UTF_8)).toString();
	}

	private static String utf16(byte[] raw, int start, ByteOrder order) {
		ByteBuffer units = ByteBuffer.wrap(raw).order(order);
		StringBuilder text = new StringBuilder(raw.length / UTF16_UNIT);
		int at = start;
		for (; at + UTF16_UNIT <= raw.length; at += UTF16_UNIT) {
			text.append(units.getChar(at));
		}
		refuseTruncated(raw, at, codec("utf-16", order));
		return text.toString();
	}

	private static String utf32(byte[] raw, int start, ByteOrder order) {
		ByteBuffer units = ByteBuffer.wrap(raw).order(order);
		StringBuilder text = new StringBuilder(raw.length / UTF32_UNIT);
		int at = start;
		for (; at + UTF32_UNIT <= raw.length; at += UTF32_UNIT) {
			long point = Integer.toUnsignedLong(units.getInt(at));
			if (point > Character.MAX_CODE_POINT) {
				throw new PythonFileException(
						DecodeError.at(raw, at, at + UTF32_UNIT, "code point not in range(0x110000)")
							.describe(codec("utf-32", order)));
			}
			text.appendCodePoint((int) point);
		}
		refuseTruncated(raw, at, codec("utf-32", order));
		return text.toString();
	}

	// the codec Python names in its errors: utf-16-le, utf-32-be
	private static String codec(String encoding, ByteOrder order) {
		return encoding + ((order == ByteOrder.LITTLE_ENDIAN) ? "-le" : "-be");
	}

	private static void refuseTruncated(byte[] raw, int at, String codec) {
		if (at < raw.length) {
			throw new PythonFileException(DecodeError.at(raw, at, raw.length, "truncated data").describe(codec));
		}
	}

	// a surrogate surrogatepass lets through: 0xED 0xA0-0xBF 0x80-0xBF
	private static boolean isSurrogate(byte[] raw, int at) {
		if (at + SURROGATE_LENGTH > raw.length || Byte.toUnsignedInt(raw[at]) != SURROGATE_LEAD) {
			return false;
		}
		int second = Byte.toUnsignedInt(raw[at + 1]);
		return second >= SURROGATE_SECOND_LOW && isContinuation(second)
				&& isContinuation(Byte.toUnsignedInt(raw[at + 2]));
	}

	private static boolean isContinuation(int value) {
		return (value & CONTINUATION_TOP_BITS) == CONTINUATION;
	}

	private static char surrogate(byte[] raw, int at) {
		int high = raw[at] & FOUR_BITS;
		int middle = raw[at + 1] & SIX_BITS;
		int low = raw[at + 2] & SIX_BITS;
		return (char) ((high << (2 * CONTINUATION_BITS)) + (middle << CONTINUATION_BITS) + low);
	}

	/**
	 * The encodings {@code json.detect_encoding} finds.
	 */
	private enum Encoding {

		/** UTF-8 without a byte order mark: the default. */
		UTF8,

		/** UTF-8 after its byte order mark ({@code utf-8-sig}). */
		UTF8_MARKED,

		/** UTF-16 after its byte order mark, which gives the byte order. */
		UTF16_MARKED,

		/** UTF-16, most significant byte first. */
		UTF16_BIG,

		/** UTF-16, least significant byte first. */
		UTF16_LITTLE,

		/** UTF-32 after its byte order mark, which gives the byte order. */
		UTF32_MARKED,

		/** UTF-32, most significant byte first. */
		UTF32_BIG,

		/** UTF-32, least significant byte first. */
		UTF32_LITTLE

	}

}
