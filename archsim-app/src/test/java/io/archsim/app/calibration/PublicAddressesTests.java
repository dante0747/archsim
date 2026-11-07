package io.archsim.app.calibration;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which addresses a hosted server may call, as the Python engine's {@code is_public}
 * judges them with Python 3.12's {@code ipaddress}: its own table first, then the special
 * ranges around it, and the IPv4 address that IPv6 carries (mapped or NAT64) judged as
 * that address.
 */
class PublicAddressesTests {

	@ParameterizedTest
	@CsvSource({ "8.8.8.8, true", "2606:4700:4700::1111, true", "127.0.0.1, false", "10.1.2.3, false",
			"172.16.0.9, false", "192.168.1.1, false", "169.254.169.254, false", "100.64.0.1, false", "0.0.0.0, false",
			"224.0.0.1, false", "255.255.255.255, false", "::1, false", "fe80::1, false", "fd00::1, false",
			"::ffff:127.0.0.1, false", "::ffff:8.8.8.8, true", "64:ff9b::a00:1, false", ":: , false" })
	void isPublic_pythonEnginesTable_isItsVerdict(String address, boolean expected) {
		assertThat(PublicAddresses.isPublic(InetAddress.ofLiteral(address.strip()))).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({ "169.254.0.1, false", "127.255.255.254, false", "100.127.255.255, false", "100.128.0.0, true",
			"172.32.0.1, true", "11.0.0.1, true", "192.0.0.8, false", "192.0.0.9, true", "192.0.0.10, true",
			"192.0.0.170, false", "192.0.2.1, false", "198.18.0.1, false", "198.51.100.1, false", "203.0.113.5, false",
			"240.0.0.1, false", "239.255.255.255, false", "64:ff9b::808:808, true", "64:ff9b::a9fe:a9fe, false",
			"64:ff9b:1::1, false", "::ffff:169.254.169.254, false", "::ffff:10.0.0.1, false", "::127.0.0.1, false",
			"100::1, false", "2001::1, false", "2001:1::1, true", "2001:3::1, true", "2001:db8::1, false",
			"2001:20::1, true", "2002::1, false", "3fff::1, false", "ff02::1, false", "fe00::1, false",
			"2a00:1450:4001:80b::200e, true" })
	void isPublic_specialRanges_arePythonsVerdict(String address, boolean expected) {
		assertThat(PublicAddresses.isPublic(InetAddress.ofLiteral(address))).isEqualTo(expected);
	}

	/**
	 * Python 3.12 counts deprecated site-local addresses as global; they are never routed
	 * on the internet (RFC 3879), so Java refuses them.
	 */
	@ParameterizedTest
	@CsvSource({ "fec0::1", "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "fed0::a:b" })
	void isPublic_siteLocalAddress_isNot(String address) {
		assertThat(PublicAddresses.isPublic(InetAddress.ofLiteral(address))).isFalse();
	}

	@Test
	void isPublic_ipv4MappedInIpv6_isJudgedAsTheIpv4Address() throws UnknownHostException {
		byte[] metadata = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 169, (byte) 254, (byte) 169,
				(byte) 254 };
		byte[] google = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 8, 8, 8, 8 };

		assertThat(PublicAddresses.isPublic(Inet6Address.getByAddress(null, metadata, -1))).isFalse();
		assertThat(PublicAddresses.isPublic(Inet6Address.getByAddress(null, google, -1))).isTrue();
	}

	@ParameterizedTest
	@CsvSource({ "127.0.0.1, true", "::1, true", "[::1], true", "fe80::1%eth0, true", "::ffff:1.2.3.4, true",
			"1:2:3:4:5:6:7:8, true", "1::, true", "::, true", "127.1, false", "010.0.0.1, false", "1.2.3.256, false",
			"prom, false", "1:2:3:4:5:6:7:8:9, false", ":1::2, false", "1::2::3, false", "12345::, false",
			"1.2.3.4%x, false", "::1%, false", "0x7f.0.0.1, false", "١٢٧.0.0.1, false" })
	void isLiteral_host_isWhatPythonsIpAddressReads(String host, boolean expected) {
		assertThat(PublicAddresses.isLiteral(host)).isEqualTo(expected);
	}

}
