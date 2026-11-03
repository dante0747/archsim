package io.archsim.app.engine;

import java.util.List;

import io.archsim.app.config.CalibrationProperties.Outbound;

/**
 * Which servers a call may reach that a request names (a calibration's Prometheus), as
 * the engine service is told with each such call: it holds no settings of its own.
 *
 * @param mode {@code open}, {@code allowlist} or {@code off}
 * @param allowedHosts the hosts it may call ({@code host} or {@code host:port}); none for
 * any host, unless the mode is {@code off}
 * @param timeoutSeconds the deadline of one query, body included
 * @param maxBytes the largest answer it accepts
 */
public record OutboundPolicy(Outbound mode, List<String> allowedHosts, double timeoutSeconds, long maxBytes) {

	/**
	 * A policy, with a copy of its hosts.
	 */
	public OutboundPolicy {
		allowedHosts = List.copyOf(allowedHosts);
	}

}
