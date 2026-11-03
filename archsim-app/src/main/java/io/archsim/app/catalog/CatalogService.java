package io.archsim.app.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.pricing.AvailableSnapshot;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * What the editor builds designs from: the component catalog, the design document's JSON
 * Schema and the price sources. The catalog and the schema do not change while the server
 * runs, so they are built once.
 */
@Service
class CatalogService {

	private static final String GENERIC = "generic";

	private final ObjectNode components;

	private final ObjectNode schema;

	private final PriceSnapshots prices;

	CatalogService(ComponentRegistry registry, PriceSnapshots prices) {
		this.components = ComponentCatalog.of(registry);
		this.schema = DesignDocuments.jsonSchema();
		this.prices = prices;
	}

	/**
	 * Every component type the editor offers, with its settings, presets and
	 * technologies.
	 * @return the component catalog, a copy
	 */
	ObjectNode components() {
		return this.components.deepCopy();
	}

	/**
	 * The JSON Schema of the design document.
	 * @return the schema, a copy
	 */
	ObjectNode schema() {
		return this.schema.deepCopy();
	}

	/**
	 * The price sources a design can name: the illustrative rates first, then every price
	 * snapshot on this server.
	 * @return the sources
	 */
	List<PriceSource> priceSources() {
		List<PriceSource> sources = new ArrayList<>();
		String note = Objects.requireNonNull(PriceSnapshot.GENERIC.note(), "the illustrative rates say what they are");
		sources.add(new PriceSource.Generic(GENERIC, null, null, true, note));
		for (AvailableSnapshot snapshot : this.prices.everyDate()) {
			sources.add(new PriceSource.Snapshot(snapshot.provider(), snapshot.region(), snapshot.date(),
					snapshot.file(), snapshot.sha256(), snapshot.newest(), false));
		}
		return sources;
	}

}
