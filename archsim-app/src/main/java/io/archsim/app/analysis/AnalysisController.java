package io.archsim.app.analysis;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiBody;

/**
 * Checks a design without saving or simulating it: {@code POST /validate} and
 * {@code POST /analyze}.
 */
@RestController
class AnalysisController {

	private final AnalysisService analysis;

	AnalysisController(AnalysisService analysis) {
		this.analysis = analysis;
	}

	/**
	 * Validates a document, and optionally one of its scenarios, without saving or
	 * simulating it.
	 * @param principal who is asking: anyone signed in
	 * @param request the design and the scenario, if any
	 * @return {@code {valid, issues}}
	 */
	@PostMapping("/validate")
	ResponseEntity<String> validate(Principal principal, @ApiBody AnalysisRequest request) {
		return this.analysis.validate(request).answer();
	}

	/**
	 * Auto sizes, cost estimate and design review of a document (and optionally a
	 * scenario), computed by the engine exactly as a run would use them, without
	 * simulating.
	 * @param principal who is asking: anyone signed in
	 * @param request the design and the scenario, if any
	 * @return the analysis
	 */
	@PostMapping("/analyze")
	ResponseEntity<String> analyze(Principal principal, @ApiBody AnalysisRequest request) {
		return this.analysis.analyze(request).answer();
	}

}
