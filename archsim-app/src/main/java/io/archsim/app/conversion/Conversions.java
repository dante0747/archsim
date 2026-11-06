package io.archsim.app.conversion;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.engine.export.DesignExporter;
import io.archsim.engine.export.ExportFormat;
import io.archsim.engine.imports.DesignImporter;
import io.archsim.engine.imports.ImportRefusedException;
import io.archsim.engine.imports.ImportedDesign;
import io.archsim.model.issue.DocumentInvalidException;

/**
 * Designs converted, in Java: exported as docker-compose, Kubernetes manifests or a
 * Mermaid diagram, and imported from docker-compose, Kubernetes manifests or a Terraform
 * plan. Nothing is stored.
 */
@Service
class Conversions {

	private final DesignExporter exporter;

	private final DesignImporter importer;

	private final JsonMapper json;

	Conversions(DesignExporter exporter, DesignImporter importer, JsonMapper json) {
		this.exporter = exporter;
		this.importer = importer;
		this.json = json;
	}

	/**
	 * A design as scaffolding text, with a suggested file suffix and media type.
	 * @param request the design and the format
	 * @return {@code {format, text, suffix, mediaType}}
	 * @throws ApiException {@code 422} with the issues when the design cannot be read or
	 * resolved
	 */
	JsonText export(ExportRequest request) {
		ExportFormat format = request.format();
		String text;
		try {
			text = this.exporter.export(request.document(), format);
		}
		catch (DocumentInvalidException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "The document cannot be exported", ex.issues());
		}
		ObjectNode answer = this.json.createObjectNode()
			.put("format", format.json())
			.put("text", text)
			.put("suffix", format.suffix())
			.put("mediaType", format.mediaType());
		return new JsonText(this.json.writeValueAsString(answer));
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
		ImportedDesign imported;
		try {
			imported = this.importer.importDesign(request.format(), request.text(), request.name());
		}
		catch (ImportRefusedException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Cannot import: " + ex.getMessage(), List.of());
		}
		catch (DocumentInvalidException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "The imported design is not valid", ex.issues());
		}
		return new JsonText(this.json.writeValueAsString(imported.toJson()));
	}

}
