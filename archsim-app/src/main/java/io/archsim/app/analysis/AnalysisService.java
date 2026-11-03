package io.archsim.app.analysis;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.validation.DocumentValidator;

/**
 * Checks a design without saving or simulating it. A design is validated in Java by
 * archsim-model; one of its scenarios, which is resolved first (auto sizes, pinned
 * prices, overrides), and an analysis are the Python engine's until stage 3 (ADR-0036).
 */
@Service
class AnalysisService {

	private final DocumentValidator validator;

	private final EngineService engine;

	private final JsonMapper json;

	AnalysisService(ComponentRegistry registry, PriceSnapshots prices, EngineService engine, JsonMapper json) {
		this.validator = new DocumentValidator(registry, prices);
		this.engine = engine;
		this.json = json;
	}

	/**
	 * Whether a design, or one of its scenarios, can be simulated, and why not. A design
	 * that cannot even be read is invalid with the reasons, not refused.
	 * @param request the design and the scenario, if any
	 * @return {@code {valid, issues}}
	 */
	JsonText validate(AnalysisRequest request) {
		Optional<String> scenario = request.scenario();
		if (scenario.isPresent()) {
			return this.engine.validateScenario(request.document(), scenario.get());
		}
		return new JsonText(this.json.writeValueAsString(validateDesign(request.document())));
	}

	/**
	 * The auto sizes, cost estimate and design review of a design or one of its
	 * scenarios, as a run would use them, without simulating.
	 * @param request the design and the scenario, if any
	 * @return the analysis, as the engine wrote it
	 * @throws ApiException {@code 422} with the engine's issues when it cannot read the
	 * design
	 */
	JsonText analyze(AnalysisRequest request) {
		try {
			return this.engine.analyze(request.document(), request.scenario().orElse(null));
		}
		catch (EngineRefusalException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "The document cannot be analyzed", ex.issues());
		}
	}

	private ObjectNode validateDesign(ObjectNode document) {
		ArchitectureDocument design;
		try {
			design = DesignDocuments.load(document);
		}
		catch (DocumentInvalidException ex) {
			ObjectNode invalid = JsonNodeFactory.instance.objectNode().put("valid", false);
			ex.issues().stream().map(Issue::toJson).forEach(invalid.putArray("issues")::add);
			return invalid;
		}
		return this.validator.validate(design).toJson();
	}

}
