package io.archsim.app.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A visitor's address as a limit per address counts it: an IPv4 address by itself, also
 * mapped into IPv6, and an IPv6 address by its /64, the least one subscriber is given.
 */
class VisitorTests {

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "203.0.113.9, 203.0.113.9", "::ffff:10.0.0.1, 10.0.0.1",
			"2001:db8:1:2:aaaa:bbbb:cccc:dddd, 2001:db8:1:2::/64", "2001:db8::1, 2001:db8::/64",
			"fe80::1%eth0, fe80::/64", "::1, ::/64", "not-an-address, not-an-address" })
	void countedAddress_anAddress_isWhatItsLimitCounts(String ip, String counted) {
		assertThat(new Visitor(ip, null).countedAddress()).isEqualTo(counted);
	}

	@Test
	void countedAddress_twoAddressesOfOneSlash64_countAsOne() {
		String one = new Visitor("2001:db8:1:2::1", null).countedAddress();
		String other = new Visitor("2001:db8:1:2:ffff:ffff:ffff:ffff", null).countedAddress();

		assertThat(one).isEqualTo(other);
	}

	@Test
	void countedAddress_noAddress_isUnknown() {
		assertThat(new Visitor(null, null).countedAddress()).isEqualTo("unknown");
	}

}
