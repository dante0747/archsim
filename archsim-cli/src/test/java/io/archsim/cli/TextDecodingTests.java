package io.archsim.cli;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the recorded imports do not show of {@link TextDecoding}: text that decodes, at
 * the edges of what each codec takes. The refusals' words are checked against Python's in
 * {@code ImportCommandTests}.
 */
class TextDecodingTests {

	@Test
	void decode_utf8OfEveryLength_isTheText() {
		String text = "a é € 😀";

		String decoded = TextDecoding.decode(text.getBytes(StandardCharsets.UTF_8));

		assertThat(decoded).isEqualTo(text);
	}

	@Test
	void decode_utf8WithAByteOrderMark_leavesTheMarkOut() {
		byte[] raw = { (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a' };

		String decoded = TextDecoding.decode(raw);

		assertThat(decoded).isEqualTo("a");
	}

	@Test
	void decode_theBoundsOfEachLeadBytesSecondByte_areText() {
		byte[] raw = { (byte) 0xc2, (byte) 0x80, (byte) 0xe0, (byte) 0xa0, (byte) 0x80, (byte) 0xed, (byte) 0x9f,
				(byte) 0xbf, (byte) 0xf0, (byte) 0x90, (byte) 0x80, (byte) 0x80, (byte) 0xf4, (byte) 0x8f, (byte) 0xbf,
				(byte) 0xbf };

		String decoded = TextDecoding.decode(raw);

		assertThat(decoded.codePoints()).containsExactly(0x80, 0x800, 0xd7ff, 0x10000, 0x10ffff);
	}

	@Test
	void decode_utf16LittleEndianWithASurrogatePair_isTheText() {
		byte[] raw = { (byte) 0xff, (byte) 0xfe, 'a', 0, 0x3d, (byte) 0xd8, 0x00, (byte) 0xde };

		String decoded = TextDecoding.decode(raw);

		assertThat(decoded).isEqualTo("a😀");
	}

	@Test
	void decode_utf16BigEndian_isTheText() {
		byte[] raw = { (byte) 0xfe, (byte) 0xff, 0, 'a', 0, 'b' };

		String decoded = TextDecoding.decode(raw);

		assertThat(decoded).isEqualTo("ab");
	}

}
