package io.archsim.app.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** {@code GET /components}, {@code GET /pricing} and {@code GET /schema/architecture}. */
@WebMvcTest(CatalogController.class)
class CatalogControllerTests {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private CatalogService catalog;

	@Test
	void components_always_answersTheCatalog() {
		given(this.catalog.components())
			.willReturn(JsonNodeFactory.instance.objectNode().put("tierNote", "Not yet validated."));

		assertThat(this.mvc.get().uri("/api/v1/components")).hasStatusOk()
			.bodyJson()
			.isEqualTo("{\"tierNote\": \"Not yet validated.\"}");
	}

	@Test
	void pricing_genericAndSnapshot_writesEachAsThePythonApiDoes() {
		given(this.catalog.priceSources())
			.willReturn(List.of(new PriceSource.Generic("generic", null, null, true, "Illustrative."),
					new PriceSource.Snapshot("aws", "us-east-1", "2026-10-01", "aws.json", "ab12", true, false)));

		assertThat(this.mvc.get().uri("/api/v1/pricing")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"sources": [
				  {"provider": "generic", "region": null, "date": null, "illustrative": true,
				   "note": "Illustrative."},
				  {"provider": "aws", "region": "us-east-1", "date": "2026-10-01", "file": "aws.json",
				   "sha256": "ab12", "newest": true, "illustrative": false}]}""");
	}

	@Test
	void architectureSchema_always_answersTheSchema() {
		given(this.catalog.schema())
			.willReturn(JsonNodeFactory.instance.objectNode().put("title", "ArchitectureDocument"));

		assertThat(this.mvc.get().uri("/api/v1/schema/architecture")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.title")
			.isEqualTo("ArchitectureDocument");
	}

}
