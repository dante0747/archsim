package io.archsim.engine.roundtrip;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the exports carry so an import gives the design back.
 */
class RoundTripTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ArchitectureDocument DESIGN = DesignDocuments.parse(JSON.readTree("""
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "Shop"},
			 "architecture": {
			   "nodes": [
			     {"id": "clients", "type": "traffic_source", "label": "Shoppers", "config": {"timeoutMs": 3000}},
			     {"id": "api", "type": "service", "config": {}},
			     {"id": "cache", "type": "cache", "config": {}},
			     {"id": "db", "type": "database", "config": {}}],
			   "edges": [
			     {"id": "clients-api", "source": "clients", "target": "api"},
			     {"id": "api-cache", "source": "api", "target": "cache", "config": {"requestTypes": ["read"]}},
			     {"id": "api-db", "source": "api", "target": "db",
			      "config": {"readsOnMiss": true, "continueOnFailure": true}},
			     {"id": "cache-db", "source": "cache", "sourcePort": "miss", "target": "db",
			      "config": {"requestTypes": ["read"]}}]},
			 "workload": {"rps": 100, "readRatio": 0.8, "durationS": 60}}"""));

	@Test
	void calls_linksWithPortsTypesAndFlags_areEachWrittenInTheirOrder() {
		String calls = RoundTrip.calls(DESIGN.architecture().edges());

		assertThat(calls).isEqualTo("api,cache:read,db!on-miss!carry-on,db/miss:read");
	}

	@Test
	void parts_design_areTheClientsWithTheirSettingsAndLinks() {
		JsonNode parts = RoundTrip.parts(DESIGN);

		assertThat(parts).isEqualTo(JSON.readTree("""
				[{"id": "clients", "type": "traffic_source", "label": "Shoppers", "config": {"timeoutMs": 3000},
				  "calls": "api"}]"""));
	}

}
