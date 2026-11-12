package io.archsim.engine.cost;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.InstantEstimator;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.sizing.BaseBusy;
import io.archsim.engine.sizing.PartSize;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The cost estimate in the cases the golden files do not reach: managed and per-request
 * parts on AWS, Kubernetes clusters and implied nodes, surplus CPU credits, prices set by
 * a person or a preset, a snapshot this machine does not have, a design without a price,
 * sizes no type covers, specs set by a scenario, and settings read leniently. Each case
 * in {@code cases.json} (recorded from the frozen engine by
 * {@code scripts/export_cost_cases.py}, since deleted) holds the design, the design as
 * simulated, and the cost and sizing summary the Python engine 0.16.0 gives, worked out
 * as its {@code analyze_document} does; the Java must match them as the golden files do.
 * {@code SizingSummariesTests} checks the summaries. A cost after a run is checked with
 * the report functions; here only what it refuses.
 */
class CostEstimatorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String DESIGN = """
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
			 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"}, {"id": "api", "type": "service"}],
			                  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
			 "workload": {"rps": 100}}""";

	private static final String REPORT_WITHOUT_PARTS = """
			{"summary": {"latencyMs": {}, "failureBreakdown": []}, "nodes": [], "findings": [],
			 "provenance": {"resultDigest": "f"}, "timeseries": {"bucketS": 1.0},
			 "window": {"warmupS": 0.0, "durationS": 10.0, "measuredS": 10.0}}""";

	private final CostEstimator costs = new CostEstimator(StandardComponents.registry(), PriceSnapshots.shipped());

	private final InstantEstimator estimator = new InstantEstimator(StandardComponents.registry());

	private final ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	@TestFactory
	Stream<DynamicTest> estimate_eachCase_isThePythonEnginesCost() throws IOException {
		return cases().valueStream().map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> {
			Inputs inputs = inputs(entry);
			CostEstimate cost = this.costs.estimate(inputs.resolved(), provenance(inputs.resolved()),
					BaseBusy.of(inputs.sizes()), inputs.estimate());

			assertThat(JsonDiff.differences(entry.path("cost"), RecordCodec.write(cost))).isEmpty();
		}));
	}

	@Test
	void estimateAfterRun_aReportWithoutParts_isRefused() {
		ArchitectureDocument design = DesignDocuments.parse(JSON.readTree(DESIGN));
		RunReport report = RunReport.read(JSON.readTree(REPORT_WITHOUT_PARTS));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> this.costs.estimateAfterRun(design, Map.of(), Map.of(), report))
			.withMessage("a run's report lists the parts of the design it ran");
	}

	/**
	 * A case's inputs: the design as simulated from the case, the sizes and the instant
	 * estimate worked out here (their own parity is checked elsewhere).
	 * @param entry the case
	 * @return its inputs
	 */
	private Inputs inputs(JsonNode entry) {
		ArchitectureDocument design = DesignDocuments.parse(entry.path("design"));
		ArchitectureDocument resolved = DesignDocuments.parse(entry.path("resolved"));
		JsonNode scenario = entry.path("scenarioId");
		Map<String, PartSize> sizes = this.resolver.resolve(design, scenario.isString() ? scenario.asString() : null)
			.sizes();
		return new Inputs(resolved, sizes, this.estimator.estimate(resolved));
	}

	private static Map<String, ProvenanceKind> provenance(ArchitectureDocument resolved) {
		Map<String, ProvenanceKind> kinds = new LinkedHashMap<>();
		resolved.provenance().forEach((path, record) -> kinds.put(path, record.kind()));
		return kinds;
	}

	private static JsonNode cases() throws IOException {
		try (InputStream in = Objects.requireNonNull(CostEstimatorTests.class.getResourceAsStream("cases.json"),
				"cases.json")) {
			return JSON.readTree(in).path("cases");
		}
	}

	/**
	 * What a case is worked out from.
	 *
	 * @param resolved the design as simulated
	 * @param sizes each part's auto size
	 * @param estimate the instant estimate of the design as simulated
	 */
	private record Inputs(ArchitectureDocument resolved, Map<String, PartSize> sizes, InstantEstimate estimate) {
	}

}
