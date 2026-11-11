package io.archsim.cli;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import io.archsim.engine.text.UndecodableBytes;

/**
 * UTF-16 after its byte order mark, and where bytes stop being it, found as Python's
 * {@code utf-16} codec finds it, with its words: a low surrogate on its own, a high one
 * without its pair, or an odd byte at the end.
 */
enum Utf16 {

	/** Little-endian, after {@code FF FE}. */
	LITTLE_ENDIAN("utf-16-le", StandardCharsets.UTF_16LE, ByteOrder.LITTLE_ENDIAN),

	/** Big-endian, after {@code FE FF}. */
	BIG_ENDIAN("utf-16-be", StandardCharsets.UTF_16BE, ByteOrder.BIG_ENDIAN);

	/** The bytes of a code unit. */
	static final int UNIT_LENGTH = 2;

	/** The bytes of a byte order mark: one code unit. */
	static final int MARK_LENGTH = UNIT_LENGTH;

	/** The byte order mark, U+FEFF, read in its own order. */
	private static final char BYTE_ORDER_MARK = 0xFEFF;

	/** The bytes of a surrogate pair. */
	private static final int PAIR_LENGTH = 2 * UNIT_LENGTH;

	private final String codec;

	private final Charset charset;

	private final ByteOrder order;

	Utf16(String codec, Charset charset, ByteOrder order) {
		this.codec = codec;
		this.charset = charset;
		this.order = order;
	}

	/**
	 * The UTF-16 a file's byte order mark says it is.
	 * @param raw the file's bytes
	 * @return the byte order; empty without a UTF-16 byte order mark
	 */
	static Optional<Utf16> marked(byte[] raw) {
		return Arrays.stream(values())
			.filter((utf16) -> raw.length >= MARK_LENGTH && utf16.unit(raw, 0) == BYTE_ORDER_MARK)
			.findFirst();
	}

	/**
	 * The codec's name, as Python names it.
	 * @return the name, such as {@code utf-16-le}
	 */
	String codec() {
		return this.codec;
	}

	/**
	 * The charset that decodes it.
	 * @return the charset
	 */
	Charset charset() {
		return this.charset;
	}

	/**
	 * Where the bytes after the byte order mark stop being UTF-16.
	 * @param raw the file's bytes, the mark included
	 * @return the first error, positions counted from the file's first byte; empty for
	 * UTF-16
	 */
	Optional<UndecodableBytes> firstError(byte[] raw) {
		int at = MARK_LENGTH;
		while (at + 1 < raw.length) {
			char unit = unit(raw, at);
			if (Character.isLowSurrogate(unit)) {
				return Optional.of(UndecodableBytes.at(raw, at, at + UNIT_LENGTH, "illegal encoding"));
			}
			if (!Character.isHighSurrogate(unit)) {
				at += UNIT_LENGTH;
				continue;
			}
			if (at + PAIR_LENGTH > raw.length) {
				return Optional.of(UndecodableBytes.at(raw, at, raw.length, "unexpected end of data"));
			}
			if (!Character.isLowSurrogate(unit(raw, at + UNIT_LENGTH))) {
				return Optional.of(UndecodableBytes.at(raw, at, at + UNIT_LENGTH, "illegal UTF-16 surrogate"));
			}
			at += PAIR_LENGTH;
		}
		return (at < raw.length) ? Optional.of(UndecodableBytes.at(raw, at, raw.length, "truncated data"))
				: Optional.empty();
	}

	// the code unit as it is, a lone surrogate included (a String would replace it)
	private char unit(byte[] raw, int at) {
		return ByteBuffer.wrap(raw, at, UNIT_LENGTH).order(this.order).getChar();
	}

}
