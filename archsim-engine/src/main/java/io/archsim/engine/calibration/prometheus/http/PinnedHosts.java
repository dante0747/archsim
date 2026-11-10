package io.archsim.engine.calibration.prometheus.http;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.apache.hc.core5.http.HttpHost;

import io.archsim.engine.calibration.prometheus.PrometheusException;

/**
 * Where a query goes: its host looked up once, by the name in ASCII as Python's
 * {@code idna} codec writes it, every address checked when the server is hosted, and the
 * first one pinned in the request's target, which names the host for the {@code Host}
 * header and TLS: the request takes its authority from the target, never from its path.
 */
final class PinnedHosts {

	private static final int HTTP_PORT = 80;

	private static final int HTTPS_PORT = 443;

	private final PrometheusServer server;

	private final HostResolver resolver;

	/**
	 * The hosts of a server's queries.
	 * @param server the server, its allowlist and whether its addresses must be public
	 * @param resolver how names are looked up
	 */
	PinnedHosts(PrometheusServer server, HostResolver resolver) {
		this.server = server;
		this.resolver = resolver;
	}

	/**
	 * A query's target.
	 * @param scheme {@code http} or {@code https}
	 * @param host the host, as the URL names it
	 * @param port the port connected to
	 * @return the target: the address, the name in ASCII, and a port only when it is not
	 * the scheme's own, as Python names it in the {@code Host} header
	 * @throws PrometheusException when the host has no address, or one a hosted server
	 * does not call
	 */
	HttpHost pin(String scheme, String host, int port) {
		String name = ascii(host);
		InetAddress address = address(host, name);
		int schemes = "https".equals(scheme) ? HTTPS_PORT : HTTP_PORT;
		return new HttpHost(scheme, address, name, (port == schemes) ? -1 : port);
	}

	/**
	 * A host's name in ASCII, as Python's {@code idna} codec writes it.
	 * @param host the host
	 * @return its name in ASCII
	 * @throws PrometheusException when it has none, as Python's {@code UnicodeError}
	 */
	private String ascii(String host) {
		try {
			return IDN.toASCII(host);
		}
		catch (IllegalArgumentException ex) {
			throw unknownHost();
		}
	}

	/**
	 * The one address a query connects to: the first the host has, after every one is
	 * checked when they must be public.
	 * @param host the host, as the URL names it
	 * @param name its name in ASCII, as it is looked up
	 * @return the address
	 * @throws PrometheusException when the host has no address, or one a hosted server
	 * does not call
	 */
	private InetAddress address(String host, String name) {
		List<InetAddress> addresses;
		try {
			addresses = this.resolver.resolve(name);
		}
		catch (UnknownHostException ex) {
			throw unknownHost();
		}
		if (addresses.isEmpty()) {
			throw unknownHost();
		}
		boolean literal = PublicAddresses.isLiteral(host);
		boolean trusted = literal && !this.server.allowedHosts().isEmpty();
		if (this.server.requireGlobal() && !trusted && !addresses.stream().allMatch(PublicAddresses::isPublic)) {
			throw new PrometheusException(host + (literal ? " is" : " resolves to")
					+ " a loopback, private, link-local or otherwise non-public address, "
					+ "which this server does not call");
		}
		return addresses.getFirst();
	}

	private PrometheusException unknownHost() {
		return new PrometheusException("cannot query " + this.server.url() + ": unknown host");
	}

}
