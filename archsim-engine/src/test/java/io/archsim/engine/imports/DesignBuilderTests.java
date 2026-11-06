package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design an importer builds.
 */
class DesignBuilderTests {

	private final DesignBuilder design = new DesignBuilder(StandardComponents.registry(),
			JsonNodeFactory.instance.stringNode("Shop"), "a file");

	@Test
	void uniqueId_nameWithSpacesAndPunctuation_isDashedAndTrimmed() {
		assertThat(this.design.uniqueId("--My Service!!")).isEqualTo("My-Service");
	}

	@Test
	void uniqueId_nameStartingWithAnUnderscore_startsWithAP() {
		assertThat(this.design.uniqueId("_api")).isEqualTo("p_api");
	}

	@Test
	void uniqueId_takenName_isNumbered() {
		this.design.add("api", "service", "API", JsonNodeFactory.instance.objectNode());

		assertThat(this.design.uniqueId("api")).isEqualTo("api-2");
	}

	@Test
	void link_toAPartThatIsNotThere_isNotAdded() {
		this.design.add("api", "service", "API", JsonNodeFactory.instance.objectNode());

		assertThat(this.design.link("api", "gone")).isFalse();
	}

}
