package io.archsim.benchmarks.report;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page's names for what it reports ({@code texts.json}), which fall back to the id
 * when one is missing: every stack's containers and every experiment have one of their
 * own.
 */
class PageTextsTests {

	private static final JsonNode TEXTS = read();

	@ParameterizedTest
	@MethodSource("containers")
	void texts_everyStacksContainer_hasANameAndAPart(String container) {
		assertThat(TEXTS.path("containers").has(container) && TEXTS.path("parts").has(container)).isTrue();
	}

	@ParameterizedTest
	@MethodSource("experiments")
	void texts_everyExperiment_hasANameAndItsTechnologies(String experiment) {
		assertThat(TEXTS.path("names").has(experiment) && TEXTS.path("technologies").has(experiment)).isTrue();
	}

	static Stream<String> containers() {
		return Experiments.standard()
			.stacks()
			.keySet()
			.stream()
			.flatMap((stack) -> Stacks.standard().containers(stack).stream())
			.distinct();
	}

	static Stream<String> experiments() {
		return Experiments.standard().all().stream().map(Experiment::id);
	}

	private static JsonNode read() {
		try (InputStream in = Objects.requireNonNull(PageTexts.class.getResourceAsStream("texts.json"))) {
			return JsonMapper.builder().build().readTree(in);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
