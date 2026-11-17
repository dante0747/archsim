package io.archsim.engine.assumptions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.Tier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the page writes values, their basis and its tables, where the standard catalog has
 * no such value: each as the Python engine writes it.
 */
class PageTextTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void value_uniformDistribution_writesMinToMaxWithItsUnit() {
		ObjectNode uniform = NODES.objectNode().put("kind", "uniform").put("min", 1).put("max", 5.5);

		assertThat(PageText.value(uniform, "ms")).isEqualTo("uniform 1–5.5 ms");
	}

	@Test
	void value_emptyUnit_writesTheNumberAlone() {
		assertThat(PageText.value(NODES.numberNode(1500), "")).isEqualTo("1,500");
	}

	@Test
	void value_list_isWrittenInCodeAsPythonWritesIt() {
		assertThat(PageText.value(NODES.arrayNode().add("a").add(1), null)).isEqualTo("`['a', 1]`");
	}

	@Test
	void value_distributionOfAnotherKind_isWrittenInCodeAsPythonWritesIt() {
		ObjectNode weibull = NODES.objectNode().put("kind", "weibull").put("shape", 1.5);

		assertThat(PageText.value(weibull, "ms")).isEqualTo("`{'kind': 'weibull', 'shape': 1.5}`");
	}

	@Test
	void table_cellWithAPipe_escapesIt() {
		assertThat(PageText.table(List.of("Setting"), List.of(List.of("a|b")))).containsExactly("| Setting |",
				"| --- |", "| a\\|b |");
	}

	@Test
	void basis_hostWithWwwAndDocs_dropsBothPrefixes() {
		assertThat(PageText.basis("https://www.docs.example.com/limits"))
			.isEqualTo("documented: [example.com](https://www.docs.example.com/limits)");
	}

	@Test
	void measured_aFileInAFolder_linksItNamedByItsFolder() {
		assertThat(PageText.measured("benchmarks/results/kafka/measured.json"))
			.isEqualTo("measured: [benchmarks/results/kafka](../benchmarks/results/kafka/measured.json)");
	}

	@Test
	void measured_aFileAtTheRoot_linksItNamedByItself() {
		assertThat(PageText.measured("results.json")).isEqualTo("measured: [results.json](../results.json)");
	}

	@Test
	void basis_linkWithoutAHost_namesNoHost() {
		assertThat(PageText.basis("urn:example")).isEqualTo("documented: [](urn:example)");
	}

	@Test
	void technologies_noneOfTheTier_isADash() {
		Map<String, TechnologyProfile> technologies = Map.of("generic", TechnologyProfile.core());

		assertThat(PageText.technologies(technologies, Tier.EXPERIMENTAL)).isEqualTo("-");
	}

}
