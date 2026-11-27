package io.archsim.app.calibration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.limits.EngineRoute;
import io.archsim.app.web.ApiBody;

/**
 * Calibration from telemetry ({@code POST /calibrate}). The mapping is always text or an
 * object, never a path: the API reads no files. Errors name what is wrong, never the
 * values a request or a server sent.
 */
@RestController
class CalibrationController {

	private final Calibrations calibrations;

	CalibrationController(Calibrations calibrations) {
		this.calibrations = calibrations;
	}

	/**
	 * Set a design's inputs from telemetry and check the result by replaying it. Returns
	 * the calibrated document (the stored project is not changed), the changes, notes and
	 * the calibration record. Runs in the request's workspace (editors).
	 * @param principal who is asking
	 * @param request the design, the telemetry and how to read it
	 * @param workspace the workspace the request names
	 * @return the calibration
	 */
	@PostMapping("/calibrate")
	@EngineRoute(replay = true)
	ResponseEntity<String> calibrate(Principal principal, @ApiBody CalibrateRequest request,
			RequestedWorkspace workspace) {
		return this.calibrations.calibrate(principal, workspace, request).answer();
	}

}
