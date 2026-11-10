package io.archsim.app.calibration;

import java.util.List;

import org.springframework.http.HttpStatus;

import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.web.ApiException;
import io.archsim.engine.calibration.prometheus.http.PrometheusServer;
import io.archsim.engine.calibration.prometheus.http.PrometheusUrls;

/**
 * Which servers a calibration may reach that its request names (its Prometheus), worked
 * out from the server's settings and the workspace.
 *
 * @param mode {@code open}, {@code allowlist} or {@code off}
 * @param allowedHosts the hosts it may call ({@code host} or {@code host:port}); none for
 * any host, unless the mode is {@code off}
 * @param timeoutSeconds the deadline of one query, body included
 * @param maxBytes the largest answer it accepts
 */
record OutboundPolicy(Outbound mode, List<String> allowedHosts, double timeoutSeconds, long maxBytes) {

	/**
	 * A policy, with a copy of its hosts.
	 */
	OutboundPolicy {
		allowedHosts = List.copyOf(allowedHosts);
	}

	/**
	 * The server a request names, if this policy lets a calibration call it, as the
	 * Python API allowed it: never when outbound calls are off, only over HTTP(S), only a
	 * listed host, and under an allowlist only at public addresses.
	 * @param url the server the request names
	 * @return the server, called under this policy's limits
	 * @throws ApiException {@code 422} in the Python API's words when this policy refuses
	 * it
	 * @throws io.archsim.engine.calibration.prometheus.PrometheusException when the URL's
	 * brackets or port are not valid
	 */
	PrometheusServer serverFor(String url) {
		if (this.mode == Outbound.OFF) {
			throw refused("This server does not call Prometheus servers (ARCHSIM_OUTBOUND): "
					+ "send recorded responses instead (archsim calibrate --record makes them)");
		}
		if (!PrometheusUrls.namesHttpServer(url)) {
			throw refused("The Prometheus URL must be http(s)://host[:port]");
		}
		if (!PrometheusUrls.isAllowed(url, this.allowedHosts)) {
			throw refused("This Prometheus host is not allowed here (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)");
		}
		return new PrometheusServer(url, this.timeoutSeconds, this.maxBytes, this.allowedHosts,
				this.mode == Outbound.ALLOWLIST);
	}

	private static ApiException refused(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, message, List.of());
	}

}
