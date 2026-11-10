package io.archsim.engine.calibration.prometheus.http;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

/**
 * Which addresses a hosted server may call, as the Python engine's {@code is_public}
 * judges them with Python 3.12's {@code ipaddress}: global, and neither multicast,
 * reserved nor unspecified, an IPv4 address inside IPv6 (mapped, or NAT64) judged as the
 * IPv4 address it carries; and, unlike Python, not site-local. That rules out loopback,
 * private, link-local (cloud metadata), CGNAT, unique-local and the other special ranges.
 * Also whether a host is written as an address, as {@code ipaddress.ip_address} reads
 * one.
 */
final class PublicAddresses {

	private static final int IPV4_BITS = 32;

	private static final int IPV4_BYTES = 4;

	private static final int IPV6_BITS = 128;

	private static final BigInteger LOW_32_BITS = BigInteger.ONE.shiftLeft(IPV4_BITS).subtract(BigInteger.ONE);

	/** The prefix of an IPv4 address mapped into IPv6, {@code ::ffff:0:0/96}. */
	private static final BigInteger MAPPED = BigInteger.valueOf(0xFFFF);

	private static final Network NAT64 = Network.of("64:ff9b::", 96);

	/** IPv4's {@code _private_networks}, which are not global. */
	private static final List<Network> IPV4_PRIVATE = List.of(Network.of("0.0.0.0", 8), Network.of("10.0.0.0", 8),
			Network.of("127.0.0.0", 8), Network.of("169.254.0.0", 16), Network.of("172.16.0.0", 12),
			Network.of("192.0.0.0", 24), Network.of("192.0.0.170", 31), Network.of("192.0.2.0", 24),
			Network.of("192.168.0.0", 16), Network.of("198.18.0.0", 15), Network.of("198.51.100.0", 24),
			Network.of("203.0.113.0", 24), Network.of("240.0.0.0", 4), Network.of("255.255.255.255", 32));

	/** The global addresses inside IPv4's private networks. */
	private static final List<Network> IPV4_GLOBAL_EXCEPTIONS = List.of(Network.of("192.0.0.9", 32),
			Network.of("192.0.0.10", 32));

	/** CGNAT: neither private nor global. */
	private static final Network IPV4_SHARED = Network.of("100.64.0.0", 10);

	private static final Network IPV4_MULTICAST = Network.of("224.0.0.0", 4);

	private static final Network IPV4_RESERVED = Network.of("240.0.0.0", 4);

	/** IPv6's {@code _private_networks}, which are not global. */
	private static final List<Network> IPV6_PRIVATE = List.of(Network.of("::1", 128), Network.of("::", 128),
			Network.of("::ffff:0:0", 96), Network.of("64:ff9b:1::", 48), Network.of("100::", 64),
			Network.of("2001::", 23), Network.of("2001:db8::", 32), Network.of("2002::", 16), Network.of("3fff::", 20),
			Network.of("fc00::", 7), Network.of("fe80::", 10));

	/** The global addresses inside IPv6's private networks. */
	private static final List<Network> IPV6_GLOBAL_EXCEPTIONS = List.of(Network.of("2001:1::1", 128),
			Network.of("2001:1::2", 128), Network.of("2001:3::", 32), Network.of("2001:4:112::", 48),
			Network.of("2001:20::", 28), Network.of("2001:30::", 28));

	private static final Network IPV6_MULTICAST = Network.of("ff00::", 8);

	/**
	 * Site-local addresses, which Python's {@code ipaddress} counts as global: deprecated
	 * (RFC 3879) and never routed on the internet, so a hosted server never calls them.
	 */
	private static final Network IPV6_SITE_LOCAL = Network.of("fec0::", 10);

	private static final List<Network> IPV6_RESERVED = List.of(Network.of("::", 8), Network.of("100::", 8),
			Network.of("200::", 7), Network.of("400::", 6), Network.of("800::", 5), Network.of("1000::", 4),
			Network.of("4000::", 3), Network.of("6000::", 3), Network.of("8000::", 3), Network.of("a000::", 3),
			Network.of("c000::", 3), Network.of("e000::", 4), Network.of("f000::", 5), Network.of("f800::", 6),
			Network.of("fe00::", 9));

	private PublicAddresses() {
	}

	/**
	 * Whether an address is on the public internet.
	 * @param address the address
	 * @return whether a hosted server may call it
	 */
	static boolean isPublic(InetAddress address) {
		BigInteger value = new BigInteger(1, address.getAddress());
		return (address.getAddress().length == IPV4_BYTES) ? isPublicV4(value) : isPublicV6(value);
	}

	/**
	 * Whether a host is an address written out, as the Python engine's
	 * {@code _ip_literal} reads it: {@code ipaddress.ip_address} of the host without its
	 * brackets.
	 * @param host the URL's host
	 * @return whether it is an IPv4 or IPv6 address
	 */
	static boolean isLiteral(String host) {
		return IpLiterals.parse(withoutBrackets(host)) != null;
	}

	private static boolean isPublicV4(BigInteger address) {
		boolean isPrivate = in(address, IPV4_BITS, IPV4_PRIVATE) && !in(address, IPV4_BITS, IPV4_GLOBAL_EXCEPTIONS);
		boolean global = !IPV4_SHARED.contains(address, IPV4_BITS) && !isPrivate;
		return global && !IPV4_MULTICAST.contains(address, IPV4_BITS) && !IPV4_RESERVED.contains(address, IPV4_BITS)
				&& address.signum() != 0;
	}

	private static boolean isPublicV6(BigInteger address) {
		if (address.shiftRight(IPV4_BITS).equals(MAPPED) || NAT64.contains(address, IPV6_BITS)) {
			return isPublicV4(address.and(LOW_32_BITS));
		}
		boolean isPrivate = in(address, IPV6_BITS, IPV6_PRIVATE) && !in(address, IPV6_BITS, IPV6_GLOBAL_EXCEPTIONS);
		boolean special = IPV6_MULTICAST.contains(address, IPV6_BITS) || in(address, IPV6_BITS, IPV6_RESERVED)
				|| IPV6_SITE_LOCAL.contains(address, IPV6_BITS);
		return !isPrivate && !special && address.signum() != 0;
	}

	private static boolean in(BigInteger address, int bits, List<Network> networks) {
		return networks.stream().anyMatch((network) -> network.contains(address, bits));
	}

	/**
	 * Python's {@code host.strip("[]")}.
	 * @param host the host
	 * @return the host without the brackets it starts and ends with
	 */
	private static String withoutBrackets(String host) {
		int start = 0;
		int end = host.length();
		while (start < end && "[]".indexOf(host.charAt(start)) >= 0) {
			start++;
		}
		while (end > start && "[]".indexOf(host.charAt(end - 1)) >= 0) {
			end--;
		}
		return host.substring(start, end);
	}

	/**
	 * A network: the addresses that share its first bits.
	 *
	 * @param base its first address
	 * @param prefix how many bits they share
	 */
	private record Network(BigInteger base, int prefix) {

		static Network of(String base, int prefix) {
			return new Network(Objects.requireNonNull(IpLiterals.parse(base)), prefix);
		}

		boolean contains(BigInteger address, int bits) {
			return address.shiftRight(bits - this.prefix).equals(this.base.shiftRight(bits - this.prefix));
		}

	}

}
