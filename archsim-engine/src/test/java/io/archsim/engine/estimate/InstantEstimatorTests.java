package io.archsim.engine.estimate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instant estimate in the cases the golden files do not reach: a design on a host
 * machine or on hyperthreads, load curves, serverless functions, limited and metered
 * databases, a read-through cache, rate limits, overloaded parts and queues, TTL caches
 * with little memory, burst patterns, cycles, and every kind of distribution. Each case
 * in {@code cases.json} (recorded from the frozen engine by
 * {@code scripts/export_estimate_cases.py}, since deleted) holds the resolved design and
 * the estimate the Python engine 0.16.0 gives for it; the estimate must match it as the
 * golden files do. Two cases the Python engine fails on are the Java's own: a link to a
 * part that does not exist, and a rate limit first among parts that use none of their
 * limits.
 */
class InstantEstimatorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ScenarioResolver RESOLVER = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private final InstantEstimator estimator = new InstantEstimator(StandardComponents.registry());

	@TestFactory
	Stream<DynamicTest> estimate_eachCase_isThePythonEnginesEstimate() throws IOException {
		return cases().valueStream()
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(),
					() -> assertThat(JsonDiff.differences(entry.path("estimate"),
							this.estimator.estimate(DesignDocuments.parse(entry.path("resolved"))).toJson()))
						.isEmpty()));
	}

	@Test
	void estimate_aLinkToAPartThatDoesNotExist_leavesItsCallerAndTheLatencyUnestimated() {
		InstantEstimate estimate = this.estimator.estimate(resolved("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture",
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				                            {"id": "api", "type": "service"}],
				                  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				                            {"id": "api-ghost", "source": "api", "target": "ghost"}]},
				 "workload": {"rps": 100}}"""));

		assertThat(estimate.parts()).singleElement()
			.isInstanceOfSatisfying(UnestimatedPart.class, (api) -> assertThat(api.note())
				.isEqualTo("It waits on a part that is overloaded or has no estimate."));
		assertThat(estimate.latencyMs()).isNull();
		assertThat(estimate.notes()).contains("Latency not estimated: the load on some parts depends on the run.");
	}

	@Test
	void estimate_aRateLimitFirstWhenNoPartUsesAnyOfItsLimits_namesNoSaturationPoint() {
		ArchitectureDocument design = resolved("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture",
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				                            {"id": "api", "type": "service",
				                             "config": {"serviceTimeMs": {"kind": "constant", "value": 10}}},
				                            {"id": "pay", "type": "external_api",
				                             "config": {"rateLimitRps": 1000,
				                                        "latencyMs": {"kind": "constant", "value": 10}}}],
				                  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				                            {"id": "api-pay", "source": "api", "target": "pay"}]},
				 "workload": {"rps": 0.001}}""");

		InstantEstimate estimate = this.estimator.estimate(design);

		assertThat(estimate.saturationRps()).isNull();
		assertThat(estimate.summary()).isEqualTo("p95 ≈ 22 ms; busiest api 0%; pay uses 0% of its rate limit");
	}

	private static ArchitectureDocument resolved(String design) {
		return RESOLVER.resolve(DesignDocuments.load(JSON.readTree(design)), null).document();
	}

	private static JsonNode cases() throws IOException {
		try (InputStream in = Objects.requireNonNull(InstantEstimatorTests.class.getResourceAsStream("cases.json"),
				"cases.json")) {
			return JSON.readTree(in).path("cases");
		}
	}

}
