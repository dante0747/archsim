package io.archsim.engine.review;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The design review in the cases the golden files do not reach: retries without backoff,
 * autoscaling ranges, queues without a downstream link, third-party errors, CPU on shared
 * cores, a walk past its step budget, titles that sort differently folded, settings read
 * leniently, and divisions by zero. Each case ({@code review/cases.json}, written by
 * {@code scripts/export_review_cases.py}) has the findings the Python engine 0.16.0
 * gives, or the error it raises.
 */
class DesignReviewTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final DesignReview review = new DesignReview(StandardComponents.registry());

	@TestFactory
	Stream<DynamicTest> review_eachCase_hasThePythonEnginesFindings() throws IOException {
		return cases().filter((entry) -> entry.has("findings"))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertFindings(entry)));
	}

	@TestFactory
	Stream<DynamicTest> review_aCaseThePythonEngineDividesByZero_isAnArithmeticError() throws IOException {
		return cases().filter((entry) -> entry.has("error"))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertError(entry)));
	}

	private void assertFindings(JsonNode entry) {
		ArrayNode findings = JSON.createArrayNode();
		this.review.review(design(entry)).forEach((finding) -> findings.add(RecordCodec.write(finding)));

		assertThat(JsonDiff.differences(entry.path("findings"), findings)).isEmpty();
	}

	private void assertError(JsonNode entry) {
		JsonNode error = entry.path("error");

		assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> this.review.review(design(entry)))
			.withMessage(error.path("message").asString());
		assertThat(error.path("type").asString()).isEqualTo("ZeroDivisionError");
	}

	private static ArchitectureDocument design(JsonNode entry) {
		return DesignDocuments.parse(entry.path("doc"));
	}

	private static Stream<JsonNode> cases() throws IOException {
		try (InputStream in = Objects.requireNonNull(
				DesignReviewTests.class.getResourceAsStream("/io/archsim/engine/review/cases.json"), "cases.json")) {
			return JSON.readTree(in).path("cases").valueStream().toList().stream();
		}
	}

}
