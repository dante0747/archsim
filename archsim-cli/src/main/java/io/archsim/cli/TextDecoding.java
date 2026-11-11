package io.archsim.cli;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import io.archsim.engine.imports.ImportRefusedException;
import io.archsim.engine.text.UndecodableBytes;
import io.archsim.engine.text.Utf8;

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

	private static void refuse(UndecodableBytes error, String codec) {
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

}
