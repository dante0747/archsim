package io.archsim.app.conversion;

import org.springframework.stereotype.Service;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;

/**
 * Designs converted by the engine service: exported as docker-compose, Kubernetes
 * manifests or a Mermaid diagram, and imported from docker-compose, Kubernetes manifests
 * or a Terraform plan. Nothing is stored.
 */
@Service
class Conversions {

	private final EngineService engine;

	Conversions(EngineService engine) {
		this.engine = engine;
	}

	/**
	 * A design as scaffolding text, with a suggested file suffix and media type.
	 * @param request the design and the format
	 * @return {@code {format, text, suffix, mediaType}}
	 * @throws ApiException {@code 422} with the engine's issues when it cannot read the
	 * design
	 */
	JsonText export(ExportRequest request) {
		try {
			return this.engine.export(request.document(), request.format().json());
		}
		catch (EngineRefusalException ex) {
			throw ex.answer("The document cannot be exported");
		}
	}

	/**
	 * A design from infrastructure text, with the import report: what was mapped, what
	 * was guessed, what was ignored, links to confirm, and issues to fix before a run.
	 * @param request the format, the text and the design's name
	 * @return {@code {document, report}}
	 * @throws ApiException {@code 422} when the text is not the format, or makes a design
	 * that is not valid
	 */
	JsonText importDesign(ImportRequest request) {
		try {
			return this.engine.importDesign(request.format().json(), request.text(), request.name());
		}
		catch (EngineRefusalException ex) {
			throw ex.answer("The imported design is not valid");
		}
	}

}
