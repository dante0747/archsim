package io.archsim.app.access;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Networks as Python's {@code ip_network(entry, strict=False)} reads them. */
class NetworkTests {

	@ParameterizedTest(name = "{0} has {1}: {2}")
	@CsvSource({ "10.0.0.0/8, 10.1.2.3, true", "10.0.0.0/8, 11.0.0.1, false", "192.168.1.0/25, 192.168.1.127, true",
			"192.168.1.0/25, 192.168.1.128, false", "2001:db8::/32, 2001:db8::5, true", "10.0.0.1, 10.0.0.1, true",
			"10.0.0.0/8, ::1, false", "10.0.0.0/255.0.0.0, 10.9.9.9, true", "10.0.0.0/0.255.255.255, 10.9.9.9, true",
			"10.0.0.0/08, 10.9.9.9, true", "10.0.0.0/8, ::ffff:10.0.0.1, false" })
	void contains_address_isTrueOnlyInsideTheNetwork(String network, String address, boolean inside) {
		Network parsed = Network.parse(network).orElseThrow();

		assertThat(parsed.contains(IpText.parse(address).orElseThrow().address())).isEqualTo(inside);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(
			strings = { "10.0.0.0/+8", "10.0.0.0/-0", "10.0.0.0/33", "10.0.0.0/255.0.255.0", "web", "fe80::%eth0/64" })
	void parse_textPythonRejects_isNoNetwork(String text) {
		assertThat(Network.parse(text)).isEmpty();
	}

}
