package io.archsim.app.catalog;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * The component catalog, the design document's JSON Schema and the price sources: what
 * the editor renders its palette and inspector from, and what a design may name.
 */
@RestController
class CatalogController {

	private final CatalogService catalog;

	CatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	/**
	 * The component catalog: ports, settings, defaults, presets and technologies of each
	 * component type, and the link and workload settings.
	 * @return the catalog
	 */
	@GetMapping("/components")
	ObjectNode components() {
		return this.catalog.components();
	}

	/**
	 * The price sources a design can name in its {@code pricing}.
	 * @return the sources, under {@code sources}
	 */
	@GetMapping("/pricing")
	PriceSources pricing() {
		return new PriceSources(this.catalog.priceSources());
	}

	/**
	 * The JSON Schema of the design document.
	 * @return the schema
	 */
	@GetMapping("/schema/architecture")
	ObjectNode architectureSchema() {
		return this.catalog.schema();
	}

	/**
	 * The answer of {@code GET /pricing}.
	 *
	 * @param sources the illustrative rates, then every price snapshot
	 */
	record PriceSources(List<PriceSource> sources) {

	}

}
