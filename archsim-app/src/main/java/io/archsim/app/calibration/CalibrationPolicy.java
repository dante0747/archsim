package io.archsim.app.calibration;

import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.config.CalibrationProperties;
import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.engine.OutboundPolicy;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.limits.WorkspaceLimits.RunLimits;

/**
 * What a calibration in a workspace may do, sent to the engine service with it: which
 * Prometheus servers it may call, and how far its replay may run.
 */
@Component
class CalibrationPolicy {

	private final WorkspaceLimits limits;

	private final ArchsimProperties properties;

	private final CalibrationProperties calibration;

	CalibrationPolicy(WorkspaceLimits limits, ArchsimProperties properties, CalibrationProperties calibration) {
		this.limits = limits;
		this.properties = properties;
		this.calibration = calibration;
	}

	/**
	 * Which Prometheus servers a calibration in a workspace of a kind may call.
	 * @param kind the workspace's kind
	 * @return the policy
	 */
	OutboundPolicy forKind(String kind) {
		return new OutboundPolicy(outbound(kind), this.calibration.prometheusAllowedHosts().hosts(),
				this.calibration.prometheusTimeoutS(), this.calibration.prometheusMaxBytes());
	}

	/**
	 * How far the replay of a calibration in a workspace of a kind may run.
	 * @param kind the workspace's kind
	 * @return the most requests and wall-clock seconds of the replay
	 */
	RunLimits replayLimits(String kind) {
		return this.limits.replayLimits(kind, this.calibration.calibrateMaxWallSeconds());
	}

	/**
	 * How freely a calibration in a workspace of a kind may call a Prometheus server.
	 * Never from a sandbox, and never under an allowlist that lists no host.
	 * @param kind the workspace's kind
	 * @return the mode
	 */
	Outbound outbound(String kind) {
		if (WorkspaceLimits.isSandbox(kind)) {
			return Outbound.OFF;
		}
		Outbound mode = configuredOutbound();
		boolean noHosts = this.calibration.prometheusAllowedHosts().hosts().isEmpty();
		return (mode == Outbound.ALLOWLIST && noHosts) ? Outbound.OFF : mode;
	}

	/**
	 * The server's mode: the setting, else any host without sign-in, else the listed
	 * hosts.
	 * @return the mode
	 */
	private Outbound configuredOutbound() {
		Outbound setting = this.calibration.outbound();
		if (setting != null) {
			return setting;
		}
		return this.properties.hosted() ? Outbound.ALLOWLIST : Outbound.OPEN;
	}

}
