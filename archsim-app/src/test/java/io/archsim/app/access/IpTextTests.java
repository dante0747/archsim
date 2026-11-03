package io.archsim.app.access;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Addresses read and written as Python's {@code ipaddress} reads and writes them. */
class IpTextTests {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|',
			value = { "::1|::1", "0:0:0:0:0:0:0:1|::1", "2001:db8:0:0:1:0:0:1|2001:db8::1:0:0:1", "1::|1::",
					"2001:db8:1:2:3:4:5:6|2001:db8:1:2:3:4:5:6", "10.0.0.1|10.0.0.1", "::|::",
					"::ffff:10.0.0.1|::ffff:10.0.0.1", "fe80::1%eth0|fe80::1%eth0" })
	void format_address_isWrittenAsPythonWritesIt(String written, String expected) {
		Ip address = IpText.parse(written).orElseThrow();

		assertThat(IpText.format(address)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "localhost", "127.1", "10", "010.0.0.1", "[::1]", "fe80::1%", "256.0.0.1", "" })
	void parse_textPythonRejects_isNoAddress(String text) {
		assertThat(IpText.parse(text)).isEmpty();
	}

}
