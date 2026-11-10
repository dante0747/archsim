package io.archsim.engine.calibration.prometheus.http;

import java.util.List;

/**
 * A Prometheus server a calibration calls, and how.
 *
 * @param url its address, {@code http(s)://host[:port][/path]}
 * @param timeoutSeconds the one deadline of a query: connecting, the headers and the
 * whole body
 * @param maxBytes the largest answer accepted
 * @param allowedHosts the hosts that may be called ({@code host} or {@code host:port});
 * none for any host
 * @param requireGlobal whether every address the host resolves to must be public, unless
 * the host is an address the allowlist names
 */
public record PrometheusServer(String url, double timeoutSeconds, long maxBytes, List<String> allowedHosts,
		boolean requireGlobal) {

	/**
	 * A query's deadline unless one is set, the Python engine's
	 * {@code DEFAULT_TIMEOUT_S}.
	 */
	public static final double DEFAULT_TIMEOUT_SECONDS = 30.0;

	/** The largest answer accepted unless a cap is set, the Python engine's 50 MiB. */
	public static final long DEFAULT_MAX_BYTES = 52_428_800L;

	/**
	 * A server, with a copy of its hosts.
	 */
	public PrometheusServer {
		allowedHosts = List.copyOf(allowedHosts);
	}

}
