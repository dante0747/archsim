package io.archsim.app.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.model.DesignDocuments;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/** What the editor builds designs from, from the design model. */
class CatalogServiceTests {

	private final CatalogService catalog = new CatalogService(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void components_standardTypes_isTheModelsCatalog() {
		assertThat(this.catalog.components()).isEqualTo(ComponentCatalog.of(StandardComponents.registry()));
	}

	@Test
	void schema_always_isTheDesignDocumentsSchema() {
		assertThat(this.catalog.schema()).isEqualTo(DesignDocuments.jsonSchema());
	}

	@Test
	void priceSources_shippedSnapshots_startWithTheIllustrativeRates() {
		List<PriceSource> sources = this.catalog.priceSources();

		assertThat(sources.getFirst()).isInstanceOf(PriceSource.Generic.class);
		assertThat(sources).hasSize(1 + PriceSnapshots.shipped().everyDate().size());
	}

}
