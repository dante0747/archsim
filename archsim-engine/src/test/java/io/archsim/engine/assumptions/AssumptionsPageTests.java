package io.archsim.engine.assumptions;

import org.junit.jupiter.api.Test;

import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page of every number ArchSim uses without the user typing it. The golden files hold
 * the whole page; these say what it must show.
 */
class AssumptionsPageTests {

	private static final String PAGE = AssumptionsPage.render(StandardComponents.registry());

	@Test
	void render_documentedValue_linksTheDocumentationByItsHost() {
		assertThat(PAGE)
			.contains("documented: [kafka.apache.org](https://kafka.apache.org/documentation/#producerconfigs_acks)")
			.contains(
					"documented: [aws.amazon.com](https://docs.aws.amazon.com/lambda/latest/dg/lambda-concurrency.html)");
	}

	@Test
	void render_illustrativeValue_isMarkedIllustrative() {
		assertThat(PAGE.lines().filter((line) -> line.contains("(`gcPauseMs`) | 12 ms"))).first()
			.satisfies((line) -> assertThat(line).endsWith("| illustrative |"));
	}

	@Test
	void render_engineConstants_listsEachByItsCode() {
		assertThat(EngineConstants.all())
			.allSatisfy((constant) -> assertThat(PAGE).contains("`" + constant.code() + "`"));
	}

}
