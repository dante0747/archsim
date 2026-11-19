package io.archsim.engine.scenario;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sibling speed a run's resolution infers for a part's vCPUs (ADR-0091) is written
 * into the part, as auto-sizing records the instance type it chose, so the run's input
 * says what ran and running it again gives the same.
 */
class ScenarioResolverSiblingSpeedTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String GENERIC = "{\"provider\": \"generic\"}";

	private static final String AWS = """
			{"provider": "aws", "region": "us-east-1", "snapshotDate": "2026-10-28"}""";

	private final ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	@Test
	void resolve_aPartOnTheDefault_recordsTheSpeedItsVcpusCountAt() {
		JsonNode config = resolvedConfig(design("1.11", "", GENERIC, "[]"), null);

		assertThat(config.path("siblingSpeed").doubleValue()).isEqualTo(0.6);
	}

	@Test
	void resolve_aPartOnAGravitonType_recordsWholeCores() {
		JsonNode config = resolvedConfig(design("1.11", ", \"instanceType\": \"m7g.xlarge\"", AWS, "[]"), null);

		assertThat(config.path("siblingSpeed").doubleValue()).isEqualTo(1.0);
	}

	@Test
	void resolve_aPartThatSetsItsSpeed_keepsIt() {
		JsonNode config = resolvedConfig(design("1.11", ", \"siblingSpeed\": 0.5", GENERIC, "[]"), null);

		assertThat(config.path("siblingSpeed").doubleValue()).isEqualTo(0.5);
	}

	@Test
	void resolve_aDesignThatSetsASiblingSpeedBelowOne_recordsItInItsParts() {
		ObjectNode json = DesignDocuments.toJson(design("1.11", "", GENERIC, "[]"));
		((ObjectNode) json.get("architecture")).put("siblingSpeed", 0.5);

		JsonNode config = resolvedConfig(DesignDocuments.load(json), null);

		assertThat(config.path("siblingSpeed").doubleValue()).isEqualTo(0.5);
	}

	@Test
	void resolve_aDesignSavedBeforeOnePointEleven_writesNoSpeedIntoItsParts() {
		JsonNode config = resolvedConfig(design("1.10", "", GENERIC, "[]"), null);

		assertThat(config.has("siblingSpeed")).isFalse();
	}

	@Test
	void resolve_aResolvedDesign_isResolvedAsItIs() {
		ArchitectureDocument resolved = this.resolver.resolve(design("1.11", "", AWS, "[]"), null).document();

		ArchitectureDocument again = this.resolver.resolve(resolved, null).document();

		assertThat(DesignDocuments.toJson(again).path("architecture"))
			.isEqualTo(DesignDocuments.toJson(resolved).path("architecture"));
	}

	@Test
	void resolve_aScenarioThatResizesAPart_countsItsVcpusAsTheTypeItThenRunsOn() {
		// m6i's vCPUs are pairs; resized to 8 vCPU the part leaves its named type,
		// and the cheapest type of 8 vCPU and 16 GiB is a Graviton one, whose vCPUs
		// are whole cores
		String overrides = """
				[{"id": "bigger", "name": "Bigger", "overrides": [
				  {"target": {"kind": "node", "id": "api"}, "path": "cpuCores", "value": 8}]}]""";
		ArchitectureDocument design = design("1.11", ", \"instanceType\": \"m6i.xlarge\"", AWS, overrides);

		assertThat(resolvedConfig(design, null).path("siblingSpeed").doubleValue()).isEqualTo(0.6);
		assertThat(resolvedConfig(design, "bigger").path("siblingSpeed").doubleValue()).isEqualTo(1.0);
	}

	@Test
	void resolve_aNamedTypeTooSmallForItsNeedOnPairs_countsTheVcpusOfTheTypeSizingChose() {
		// 2.4 cores' work a replica: 4 vCPU on whole cores, 8 on pairs at 0.6, so the
		// named m6i.xlarge, whose 4 vCPU are pairs, gives way to a Graviton type of 4,
		// whose vCPUs are whole cores
		JsonNode config = resolvedConfig(autoSized(", \"instanceType\": \"m6i.xlarge\"", "[]"), null);

		assertThat(config.path("siblingSpeed").doubleValue()).isEqualTo(1.0);
	}

	@Test
	void resolve_aScenarioThatResizesAPartWhoseNamedTypeSizingReplaced_putsTheNamedTypeBack() {
		String overrides = """
				[{"id": "smaller", "name": "Smaller", "overrides": [
				  {"target": {"kind": "node", "id": "api"}, "path": "cpuCores", "value": 2}]}]""";

		JsonNode config = resolvedConfig(autoSized(", \"instanceType\": \"m6i.xlarge\"", overrides), "smaller");

		assertThat(config.path("instanceType").asString()).isEqualTo("m6i.xlarge");
	}

	private JsonNode resolvedConfig(ArchitectureDocument design, @Nullable String scenarioId) {
		return this.resolver.resolve(design, scenarioId).document().node("api").orElseThrow().config();
	}

	/**
	 * Clients calling a service that auto-sizing sizes: 10 ms of CPU a request at 480
	 * req/s, 2.4 cores' work a replica.
	 * @param settings more of the service's settings, each after a comma
	 * @param scenarios the design's scenarios, as JSON
	 */
	private static ArchitectureDocument autoSized(String settings, String scenarios) {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "vcpus"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"workersPerReplica": 16,
				     "serviceTimeMs": {"kind": "constant", "value": 10}%s}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 480}, "pricing": %s, "scenarios": %s}""".formatted(settings, AWS, scenarios)));
	}

	/**
	 * Clients calling a service of 4 vCPU and 16 GiB.
	 * @param version the design's schema version
	 * @param settings more of the service's settings, each after a comma
	 * @param pricing the design's pricing, as JSON
	 * @param scenarios the design's scenarios, as JSON
	 */
	private static ArchitectureDocument design(String version, String settings, String pricing, String scenarios) {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "%s", "kind": "archsim.architecture", "metadata": {"name": "vcpus"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"cpuCores": 4, "memoryGib": 16%s}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 100}, "pricing": %s, "scenarios": %s}""".formatted(version, settings, pricing,
				scenarios)));
	}

}
