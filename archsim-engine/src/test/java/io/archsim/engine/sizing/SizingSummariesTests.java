package io.archsim.engine.sizing;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.estimate.InstantEstimator;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sizing summary in the cases the golden files do not reach: specs set on the design
 * or by a scenario, managed and per-use parts, sizes no instance type covers, and
 * settings read leniently. The cases are the cost estimate's ({@code cost/cases.json},
 * recorded from the frozen engine by {@code scripts/export_cost_cases.py}, since
 * deleted), each with the summary the Python engine 0.16.0 gives.
 */
class SizingSummariesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final SizingSummaries summaries = new SizingSummaries(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private final InstantEstimator estimator = new InstantEstimator(StandardComponents.registry());

	private final ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	@TestFactory
	Stream<DynamicTest> of_eachCase_isThePythonEnginesSizingSummary() throws IOException {
		return cases().valueStream()
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertSummary(entry)));
	}

	private void assertSummary(JsonNode entry) {
		ArchitectureDocument design = DesignDocuments.parse(SchemaDepartures.asCurrent(entry.path("design")));
		ArchitectureDocument resolved = DesignDocuments.parse(SchemaDepartures.asCurrent(entry.path("resolved")));
		JsonNode scenario = entry.path("scenarioId");
		Map<String, PartSize> sizes = this.resolver.resolve(design, scenario.isString() ? scenario.asString() : null)
			.sizes();

		SizingSummary summary = this.summaries.of(design, resolved, sizes, this.estimator.estimate(resolved));

		assertThat(JsonDiff.differences(entry.path("summary"), RecordCodec.write(summary))).isEmpty();
	}

	private static JsonNode cases() throws IOException {
		try (InputStream in = Objects.requireNonNull(
				SizingSummariesTests.class.getResourceAsStream("/io/archsim/engine/cost/cases.json"), "cases.json")) {
			return JSON.readTree(in).path("cases");
		}
	}

}
