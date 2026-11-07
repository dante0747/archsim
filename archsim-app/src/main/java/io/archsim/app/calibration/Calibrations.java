package io.archsim.app.calibration;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.limits.WorkspaceLimits.RunLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.engine.simulation.SimulationLimits;

/**
 * Calibration from telemetry: the engine's Prometheus and OpenTelemetry calibrations, in
 * Java, which answer a new design and what changed. The stored project is not changed.
 * Editors calibrate, in the request's workspace, under its outbound policy and replay
 * limits.
 */
@Service
class Calibrations {

	private final Access access;

	private final RateLimits limits;

	private final CalibrationPolicy policy;

	private final Calibrator calibrator;

	Calibrations(Access access, RateLimits limits, CalibrationPolicy policy, Calibrator calibrator) {
		this.access = access;
		this.limits = limits;
		this.policy = policy;
		this.calibrator = calibrator;
	}

	/**
	 * Sets a design's inputs from telemetry and checks the result by replaying it.
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @param request the design, the telemetry and how to read it
	 * @return the calibrated design, the changes, notes, the calibration record and its
	 * summary
	 * @throws ApiException {@code 403} for a viewer, {@code 429} after many calibrations
	 * in an hour, {@code 422} for a design, mapping or telemetry that cannot be used
	 */
	JsonText calibrate(Principal principal, RequestedWorkspace workspace, CalibrateRequest request) {
		WorkspaceAccess where = this.access.workspace(principal, workspace);
		where.require(WorkspaceRole.EDITOR);
		this.limits.enforce("user:" + principal.userId() + ":calibrate", RateLimit.CALIBRATIONS_PER_USER,
				"You have run many calibrations this hour.");
		String kind = where.workspace().kind();
		RunLimits replay = this.policy.replayLimits(kind);
		return this.calibrator.calibrate(request, this.policy.forKind(kind),
				new SimulationLimits(replay.maxRequests(), replay.maxWallSeconds()));
	}

}
