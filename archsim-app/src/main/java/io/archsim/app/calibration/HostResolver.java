package io.archsim.app.calibration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Looks a host name up: the one lookup of a Prometheus query, whose answer is both
 * checked and connected to. A seam, so the tests can answer as a name under an attacker's
 * control would.
 */
@FunctionalInterface
interface HostResolver {

	/** The system's resolver, as {@code InetAddress} looks names up. */
	HostResolver SYSTEM = (host) -> List.of(InetAddress.getAllByName(host));

	/**
	 * The addresses of a host.
	 * @param host a name, or an address written as one
	 * @return its addresses, the one to connect to first
	 * @throws UnknownHostException when it has none
	 */
	List<InetAddress> resolve(String host) throws UnknownHostException;

}
