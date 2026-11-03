package io.archsim.app.calibration;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.model.codec.RecordCodec;

/**
 * Calibration from telemetry: the engine's Prometheus and OpenTelemetry calibration, run
 * by the engine service, which answers a new design and what changed. The stored project
 * is not changed. Editors calibrate, in the request's workspace.
 */
@Service
class Calibrations {

	private final EngineService engine;

	private final Access access;

	private final RateLimits limits;

	private final CalibrationPolicy policy;

	Calibrations(EngineService engine, Access access, RateLimits limits, CalibrationPolicy policy) {
		this.engine = engine;
		this.access = access;
		this.limits = limits;
		this.policy = policy;
	}

	/**
	 * Sets a design's inputs from telemetry and checks the result by replaying it.
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @param request the design, the telemetry and how to read it
	 * @return the calibrated design, the changes, notes and the calibration record
	 * @throws ApiException {@code 403} for a viewer, {@code 429} after many calibrations
	 * in an hour, {@code 422} for a design, mapping or telemetry that cannot be used
	 */
	JsonText calibrate(Principal principal, RequestedWorkspace workspace, CalibrateRequest request) {
		WorkspaceAccess where = this.access.workspace(principal, workspace);
		where.require(WorkspaceRole.EDITOR);
		this.limits.enforce("user:" + principal.userId() + ":calibrate", RateLimit.CALIBRATIONS_PER_USER,
				"You have run many calibrations this hour.");
		String kind = where.workspace().kind();
		try {
			return this.engine.calibrate(RecordCodec.write(request), this.policy.forKind(kind),
					this.policy.replayLimits(kind));
		}
		catch (EngineRefusalException ex) {
			throw ex.answer("The document cannot be calibrated");
		}
	}

}
