package io.archsim.cli;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import io.archsim.engine.imports.ImportRefusedException;

/**
 * Text from a file's bytes, as the Python CLI's {@code decode_text} read it: UTF-16 after
 * its byte order mark (what Windows PowerShell 5.1 writes when the output of
 * {@code terraform show -json} is redirected to a file), else UTF-8 with or without one.
 * Bytes that are neither are refused in Python's words, its codec's message included.
 */
final class TextDecoding {

	/** The byte order mark of UTF-8, which UTF-8 text may start with. */
	static final byte[] UTF8_MARK = { (byte) 0xef, (byte) 0xbb, (byte) 0xbf };

	private TextDecoding() {
	}

	/**
	 * The text the bytes hold.
	 * @param raw a file's bytes
	 * @return its text, without a byte order mark
	 * @throws ImportRefusedException when the bytes are not UTF-8 or UTF-16 text
	 */
	static String decode(byte[] raw) {
		Optional<Utf16> marked = Utf16.marked(raw);
		if (marked.isPresent()) {
			Utf16 utf16 = marked.get();
			utf16.firstError(raw).ifPresent((error) -> refuse(error, utf16.codec()));
			return new String(raw, Utf16.MARK_LENGTH, raw.length - Utf16.MARK_LENGTH, utf16.charset());
		}
		int start = startsWith(raw, UTF8_MARK) ? UTF8_MARK.length : 0;
		// positions count from after the mark, as Python's utf-8-sig codec counts them
		Utf8.firstError(Arrays.copyOfRange(raw, start, raw.length)).ifPresent((error) -> refuse(error, Utf8.CODEC));
		return new String(raw, start, raw.length - start, StandardCharsets.UTF_8);
	}

	private static void refuse(DecodeError error, String codec) {
		throw new ImportRefusedException("the file is not UTF-8 or UTF-16 text (%s)".formatted(error.describe(codec)));
	}

	/**
	 * Whether bytes start with others.
	 * @param raw the bytes
	 * @param prefix what they may start with
	 * @return {@code true} when they do
	 */
	static boolean startsWith(byte[] raw, byte[] prefix) {
		return raw.length >= prefix.length && Arrays.equals(raw, 0, prefix.length, prefix, 0, prefix.length);
	}

	/**
	 * Where bytes stop being text.
	 *
	 * @param start where the bytes a codec cannot decode start
	 * @param end where they end, exclusive
	 * @param first the first of them, unsigned
	 * @param reason why the codec cannot decode them, in Python's words
	 */
	record DecodeError(int start, int end, int first, String reason) {

		/**
		 * The bytes a codec cannot decode.
		 * @param bytes the bytes it read
		 * @param start where the bytes it cannot decode start
		 * @param end where they end, exclusive
		 * @param reason why it cannot, in Python's words
		 * @return the error
		 */
		static DecodeError at(byte[] bytes, int start, int end, String reason) {
			return new DecodeError(start, end, Byte.toUnsignedInt(bytes[start]), reason);
		}

		/**
		 * The error in Python's words: {@code 'utf-8' codec can't decode byte 0xff in
		 * position 3: invalid start byte}.
		 * @param codec the codec's name, as Python names it
		 * @return the words
		 */
		String describe(String codec) {
			if (this.end - this.start == 1) {
				return "'%s' codec can't decode byte 0x%02x in position %d: %s".formatted(codec, this.first, this.start,
						this.reason);
			}
			return "'%s' codec can't decode bytes in position %d-%d: %s".formatted(codec, this.start, this.end - 1,
					this.reason);
		}

	}

}
