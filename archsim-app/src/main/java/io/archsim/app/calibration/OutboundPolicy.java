package io.archsim.app.calibration;

import java.util.List;

import io.archsim.app.config.CalibrationProperties.Outbound;

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

}
