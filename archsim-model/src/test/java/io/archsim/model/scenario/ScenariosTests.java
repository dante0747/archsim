package io.archsim.model.scenario;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a scenario that resizes a part does to the instance type auto-sizing recorded for
 * it: the part gets the type that fits its new size.
 */
class ScenariosTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SMALLER = """
			[{"id": "smaller", "name": "Smaller", "overrides": [
			  {"target": {"kind": "node", "id": "api"}, "path": "cpuCores", "value": 2}]}]""";

	@Test
	void dropStaleInstanceTypes_aResizedPartWhoseNamedTypeWasReplaced_getsItsNamedTypeBack() {
		ArchitectureDocument design = design(", \"instanceType\": \"m6i.xlarge\"", SMALLER);
		ObjectNode resolved = resolved(design, "c6g.xlarge");

		Scenarios.dropStaleInstanceTypes(resolved, design, Scenarios.find(design, "smaller"));

		assertThat(resolved.at("/architecture/nodes/1/config/instanceType").asString()).isEqualTo("m6i.xlarge");
	}

	@Test
	void dropStaleInstanceTypes_aResizedPartThatNamesNoType_losesTheTypeAutoSizingChose() {
		ArchitectureDocument design = design("", SMALLER);
		ObjectNode resolved = resolved(design, "c6g.xlarge");

		Scenarios.dropStaleInstanceTypes(resolved, design, Scenarios.find(design, "smaller"));

		assertThat(resolved.at("/architecture/nodes/1/config").has("instanceType")).isFalse();
	}

	// the design with the scenario's override applied and the type auto-sizing recorded
	private static ObjectNode resolved(ArchitectureDocument design, String recorded) {
		ObjectNode resolved = DesignDocuments.toJson(design);
		((ObjectNode) resolved.at("/architecture/nodes/1/config")).put("cpuCores", 2).put("instanceType", recorded);
		return resolved;
	}

	// clients calling a service of 4 vCPU with more settings, each after a comma, and the
	// scenarios
	private static ArchitectureDocument design(String settings, String scenarios) {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "scenarios"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "config": {"cpuCores": 4%s}}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 100}, "scenarios": %s}""".formatted(settings, scenarios)));
	}

}
