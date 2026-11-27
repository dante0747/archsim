package io.archsim.app.conversion;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;
import io.archsim.app.limits.EngineRoute;
import io.archsim.app.web.ApiBody;

/**
 * Exports ({@code POST /export}) and imports ({@code POST /import}) of designs, generated
 * by the engine. Nothing is stored: the web app opens an imported design as a new,
 * unsaved one.
 */
@RestController
class ConversionController {

	private final Conversions conversions;

	ConversionController(Conversions conversions) {
		this.conversions = conversions;
	}

	/**
	 * The design as scaffolding text, with a suggested file suffix and media type.
	 * @param principal who is asking: anyone signed in
	 * @param request the design and the format
	 * @return {@code {format, text, suffix, mediaType}}
	 */
	@PostMapping("/export")
	@EngineRoute
	ResponseEntity<String> export(Principal principal, @ApiBody ExportRequest request) {
		return this.conversions.export(request).answer();
	}

	/**
	 * The design and the import report: what was mapped, what was guessed (marked
	 * assumed), what was ignored, links to confirm, and validation issues to fix before a
	 * run.
	 * @param principal who is asking: anyone signed in
	 * @param request the format, the text and the design's name
	 * @return {@code {document, report}}
	 */
	@PostMapping("/import")
	@EngineRoute
	ResponseEntity<String> importDesign(Principal principal, @ApiBody ImportRequest request) {
		return this.conversions.importDesign(request).answer();
	}

}
