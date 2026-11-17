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

import io.archsim.engine.parity.CacheDepartures;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design review in the cases the golden files do not reach: retries without backoff,
 * autoscaling ranges, queues without a downstream link, third-party errors, CPU on shared
 * cores, a walk past its step budget, titles that sort differently folded, settings read
 * leniently, and divisions by zero. Each case ({@code review/cases.json}, recorded from
 * the frozen engine by {@code scripts/export_review_cases.py}, since deleted) has the
 * findings the Python engine 0.16.0 gives, or the error it raises. Where it divides by
 * zero, the Java review skips the check that would, and gives the Python engine's review
 * of the design without the setting whose removal skips only that check.
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
	Stream<DynamicTest> review_aCaseThePythonEngineDividesByZero_skipsTheCheckThatWould() throws IOException {
		return cases().filter((entry) -> entry.has("skipped"))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertSkipped(entry)));
	}

	// the advice to a part that already reads on a miss departs (CacheDepartures)
	private void assertFindings(JsonNode entry) {
		JsonNode expected = entry.path("findings");
		assertThat(JsonDiff.differences(expected, CacheDepartures.asRecorded(findings(entry), expected))).isEmpty();
	}

	private void assertSkipped(JsonNode entry) {
		JsonNode expected = entry.path("skipped").path("findings");
		assertThat(JsonDiff.differences(expected, CacheDepartures.asRecorded(findings(entry), expected))).isEmpty();
	}

	private ArrayNode findings(JsonNode entry) {
		ArrayNode findings = JSON.createArrayNode();
		this.review.review(design(entry)).forEach((finding) -> findings.add(RecordCodec.write(finding)));
		return findings;
	}

	private static ArchitectureDocument design(JsonNode entry) {
		return DesignDocuments.parse(SchemaDepartures.asCurrent(entry.path("doc")));
	}

	private static Stream<JsonNode> cases() throws IOException {
		try (InputStream in = Objects.requireNonNull(
				DesignReviewTests.class.getResourceAsStream("/io/archsim/engine/review/cases.json"), "cases.json")) {
			return JSON.readTree(in).path("cases").valueStream().toList().stream();
		}
	}

}
