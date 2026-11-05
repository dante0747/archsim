package io.archsim.app.analysis;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.validation.DocumentValidator;

/**
 * Checks a design without saving or simulating it: a design, and one of its scenarios,
 * resolved, validated and analyzed in Java (archsim-engine and archsim-model).
 */
@Service
class AnalysisService {

	private final DocumentValidator validator;

	private final ScenarioResolver resolver;

	private final DesignAnalyzer analyzer;

	private final JsonMapper json;

	AnalysisService(DocumentValidator validator, ScenarioResolver resolver, DesignAnalyzer analyzer, JsonMapper json) {
		this.validator = validator;
		this.resolver = resolver;
		this.analyzer = analyzer;
		this.json = json;
	}

	/**
	 * Whether a design, or one of its scenarios, can be simulated, and why not. The
	 * design is resolved as a run resolves it (auto sizes, pinned prices, the scenario's
	 * overrides); the resolved scenario is validated, or the design as written when there
	 * is none. A design that cannot be read or resolved is invalid with the reasons, not
	 * refused.
	 * @param request the design and the scenario, if any
	 * @return {@code {valid, issues}}
	 */
	JsonText validate(AnalysisRequest request) {
		return new JsonText(
				this.json.writeValueAsString(validation(request.document(), request.scenario().orElse(null))));
	}

	/**
	 * The auto sizes, cost estimate and design review of a design or one of its
	 * scenarios, as a run would use them, without simulating.
	 * @param request the design and the scenario, if any
	 * @return the analysis
	 * @throws ApiException {@code 422} with the issues when the design cannot be read or
	 * resolved
	 */
	JsonText analyze(AnalysisRequest request) {
		try {
			return new JsonText(this.json.writeValueAsString(
					this.analyzer.analyze(request.document(), request.scenario().orElse(null)).toJson()));
		}
		catch (DocumentInvalidException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "The document cannot be analyzed", ex.issues());
		}
	}

	private ObjectNode validation(ObjectNode document, @Nullable String scenario) {
		ArchitectureDocument design;
		ArchitectureDocument resolved;
		try {
			design = DesignDocuments.load(document);
			// Resolved even without a scenario, as the Python API did: a design
			// that cannot be resolved (auto-sized, priced) cannot run, so it is
			// invalid, with the reasons.
			resolved = this.resolver.resolve(design, scenario).document();
		}
		catch (DocumentInvalidException ex) {
			ObjectNode invalid = JsonNodeFactory.instance.objectNode().put("valid", false);
			ex.issues().stream().map(Issue::toJson).forEach(invalid.putArray("issues")::add);
			return invalid;
		}
		return this.validator.validate((scenario != null) ? resolved : design).toJson();
	}

}
