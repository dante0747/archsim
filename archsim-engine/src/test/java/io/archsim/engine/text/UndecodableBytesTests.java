package io.archsim.engine.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Bytes a codec cannot decode, described as CPython's {@code UnicodeDecodeError}
 * described them: one byte by its value, several by their positions.
 */
class UndecodableBytesTests {

	@Test
	void describe_oneByte_namesItsValueAndPosition() {
		UndecodableBytes bytes = UndecodableBytes.at(new byte[] { 0x61, (byte) 0xfe }, 1, 2, "invalid start byte");

		assertThat(bytes.describe("utf-8"))
			.isEqualTo("'utf-8' codec can't decode byte 0xfe in position 1: invalid start byte");
	}

	@Test
	void describe_severalBytes_namesTheirFirstAndLastPositions() {
		UndecodableBytes bytes = UndecodableBytes.at(new byte[] { 0x61, (byte) 0xe2, (byte) 0x82 }, 1, 3,
				"unexpected end of data");

		assertThat(bytes.describe("utf-8"))
			.isEqualTo("'utf-8' codec can't decode bytes in position 1-2: unexpected end of data");
	}

	@ParameterizedTest(name = "from {0} to {1}, byte {2}")
	@CsvSource(delimiter = '|', textBlock = """
			5  | 5 | 0
			-1 | 1 | 0
			0  | 1 | 256
			0  | 1 | -1
			""")
	void undecodableBytes_positionsOrByteOutOfRange_isRefused(int start, int end, int first) {
		assertThatIllegalArgumentException().isThrownBy(() -> new UndecodableBytes(start, end, first, "refused"));
	}

}
