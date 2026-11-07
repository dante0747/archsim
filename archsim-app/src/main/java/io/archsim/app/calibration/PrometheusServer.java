package io.archsim.app.calibration;

import java.util.List;

import org.springframework.http.HttpStatus;

import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.web.ApiException;

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
record PrometheusServer(String url, double timeoutSeconds, long maxBytes, List<String> allowedHosts,
		boolean requireGlobal) {

	/**
	 * A server, with a copy of its hosts.
	 */
	PrometheusServer {
		allowedHosts = List.copyOf(allowedHosts);
	}

	/**
	 * The server a request names, if a policy lets a calibration call it, as the Python
	 * API allowed it: never when outbound calls are off, only over HTTP(S), only a listed
	 * host, and under an allowlist only at public addresses.
	 * @param policy what the calibration may do
	 * @param url the server the request names
	 * @return the server, called under the policy's limits
	 * @throws ApiException {@code 422} in the Python API's words when the policy refuses
	 * it
	 * @throws io.archsim.engine.calibration.prometheus.PrometheusException when the URL's
	 * brackets or port are not valid
	 */
	static PrometheusServer allowedBy(OutboundPolicy policy, String url) {
		if (policy.mode() == Outbound.OFF) {
			throw refused("This server does not call Prometheus servers (ARCHSIM_OUTBOUND): "
					+ "send recorded responses instead (archsim calibrate --record makes them)");
		}
		PrometheusUrl parts = PrometheusUrl.split(url);
		if (!parts.isHttp() || parts.netloc().isEmpty()) {
			throw refused("The Prometheus URL must be http(s)://host[:port]");
		}
		if (!PrometheusUrl.allowed(url, policy.allowedHosts())) {
			throw refused("This Prometheus host is not allowed here (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)");
		}
		return new PrometheusServer(url, policy.timeoutSeconds(), policy.maxBytes(), policy.allowedHosts(),
				policy.mode() == Outbound.ALLOWLIST);
	}

	private static ApiException refused(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message, List.of());
	}

}
