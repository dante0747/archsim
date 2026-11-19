package io.archsim.engine.estimate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.parity.CommitFlushDepartures;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instant estimate in the cases the golden files do not reach: a design on a host
 * machine or on hyperthreads, load curves, serverless functions, limited and metered
 * databases, a read-through cache, rate limits, overloaded parts and queues, TTL caches
 * with little memory, burst patterns, cycles, and every kind of distribution. Each case
 * in {@code cases.json} (recorded from the frozen engine by
 * {@code scripts/export_estimate_cases.py}, since deleted) holds the resolved design and
 * the estimate the Python engine 0.16.0 gives for it; the estimate must match it as the
 * golden files do, a database that flushes its commits read as
 * {@link CommitFlushDepartures} reads it (ADR-0089). On threads a host machine slows, the
 * Java stretches a write's query and not the flush it then waits for, where Python
 * stretched both: {@code slowed-flush.json} lists the figures that are the Java's own in
 * the two cases with such a database, and every other figure of theirs must be Python's.
 * Two cases the Python engine fails on are the Java's own: a link to a part that does not
 * exist, and a rate limit first among parts that use none of their limits.
 */
class InstantEstimatorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String CASES = "cases";

	private static final String ESTIMATE = "estimate";

	private static final ScenarioResolver RESOLVER = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	/**
	 * 100 writes a second to a database of 2 cores, each a query of 4 ms, half of it on a
	 * core, then a log flush of 6 ms.
	 */
	private static final String FLUSHING_DATABASE = """
			{"schemaVersion": "1.7", "kind": "archsim.architecture",
			 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
			                            {"id": "db", "type": "database",
			                             "config": {"technology": "postgresql", "cpuCores": 2, "cpuShare": 0.5,
			                                        "maxConnections": 100,
			                                        "writeServiceTimeMs": {"kind": "constant", "value": 4},
			                                        "commitFlushMs": {"kind": "constant", "value": 6}}}],
			                  "edges": [{"id": "clients-db", "source": "clients", "target": "db"}]},
			 "workload": {"rps": 100, "readRatio": 0}}""";

	private final InstantEstimator estimator = new InstantEstimator(StandardComponents.registry());

	@TestFactory
	Stream<DynamicTest> estimate_eachCase_isThePythonEnginesEstimate() throws IOException {
		JsonNode slowed = resource("slowed-flush.json").path(CASES);
		return resource("cases.json").path(CASES)
			.valueStream()
			.map((entry) -> DynamicTest
				.dynamicTest(name(entry),
						() -> assertThat(JsonDiff.differences(entry.path(ESTIMATE),
								asRecorded(estimate(entry), entry.path(ESTIMATE), slowed.path(name(entry)))))
							.isEmpty()));
	}

	@TestFactory
	Stream<DynamicTest> estimate_aDatabaseOnSlowedThreads_stretchesItsQueriesAndNotTheFlush() throws IOException {
		JsonNode slowed = resource("slowed-flush.json").path(CASES);
		return resource("cases.json").path(CASES)
			.valueStream()
			.filter((entry) -> slowed.has(name(entry)))
			.map((entry) -> DynamicTest.dynamicTest(name(entry), () -> {
				JsonNode own = slowed.get(name(entry));

				assertThat(JsonDiff.differences(own, figures(estimate(entry), own))).isEmpty();
			}));
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

	@Test
	void estimate_aDatabaseThatFlushesCommits_saturatesWhereItsQueriesFillItsCores() {
		InstantEstimate estimate = this.estimator.estimate(resolved(FLUSHING_DATABASE));

		// a write's query takes half of its 4 ms on a core: 2 cores × 1000 ÷ 2 ms
		assertThat(estimate.saturationRps()).isEqualTo(1000.0);
	}

	@Test
	void estimate_aDatabaseThatFlushesCommits_hasItsQueriesShareOfItsCoresBusy() {
		InstantEstimate estimate = this.estimator.estimate(resolved(FLUSHING_DATABASE));

		assertThat(estimate.parts()).singleElement()
			.isInstanceOfSatisfying(EstimatedPart.class,
					(database) -> assertThat(database.cpuUtilization()).isEqualTo(100 * 2.0 / 1000 / 2));
	}

	@Test
	void estimate_aDatabaseThatFlushesCommits_holdsAConnectionThroughTheFlush() {
		InstantEstimate estimate = this.estimator.estimate(resolved(FLUSHING_DATABASE));

		assertThat(estimate.parts()).singleElement()
			.isInstanceOfSatisfying(EstimatedPart.class,
					(database) -> assertThat(database.meanHoldMs()).isEqualTo(4.0 + 6.0));
	}

	private static ArchitectureDocument resolved(String design) {
		return RESOLVER.resolve(DesignDocuments.load(JSON.readTree(design)), null).document();
	}

	private JsonNode estimate(JsonNode entry) {
		return this.estimator.estimate(DesignDocuments.parse(SchemaDepartures.asCurrent(entry.path("resolved"))))
			.toJson();
	}

	// the Java's estimate read as the frozen engine's: its databases that flush as
	// CommitFlushDepartures reads them, then the figures listed as its own as recorded
	private static JsonNode asRecorded(JsonNode java, JsonNode python, JsonNode own) {
		JsonNode read = CommitFlushDepartures.asRecorded(java, python);
		for (String pointer : own.propertyNames()) {
			JsonPointer place = JsonPointer.compile(pointer);
			((ObjectNode) read.at(place.head())).set(place.last().getMatchingProperty(), python.at(place));
		}
		return read;
	}

	// an estimate's figures at the places listed, by place
	private static JsonNode figures(JsonNode estimate, JsonNode listed) {
		ObjectNode figures = JSON.createObjectNode();
		listed.propertyNames().forEach((pointer) -> figures.set(pointer, estimate.at(pointer)));
		return figures;
	}

	private static String name(JsonNode entry) {
		return entry.path("name").asString();
	}

	private static JsonNode resource(String name) throws IOException {
		try (InputStream in = Objects.requireNonNull(InstantEstimatorTests.class.getResourceAsStream(name), name)) {
			return JSON.readTree(in);
		}
	}

}
