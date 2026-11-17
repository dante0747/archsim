package io.archsim.engine.roundtrip;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the exports carry so an import gives the design back.
 */
class RoundTripTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ArchitectureDocument DESIGN = DesignDocuments.parse(JSON.readTree("""
			{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "Shop"},
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

	@Test
	void decodeCalls_linksWithPortsTypesAndFlags_areReadBack() {
		List<ExportedLink> links = RoundTrip.decodeCalls("db/miss:read!on-miss!nope,  ,cache:read+write!carry-on");

		assertThat(links).containsExactly(new ExportedLink("db", "miss", List.of("read"), Map.of("readsOnMiss", true)),
				new ExportedLink("cache", null, List.of("read", "write"), Map.of("continueOnFailure", true)));
	}

	@Test
	void parseParts_entriesThatAreNotParts_areSkipped() {
		List<ObjectNode> parts = RoundTrip.parseParts(JSON.readTree("""
				[{"id": "users", "type": "traffic_source"}, {"id": "db", "type": "database"}, "x",
				 {"type": "traffic_source"}]"""));

		assertThat(parts).extracting((part) -> part.path("id").stringValue()).containsExactly("users");
	}

	@Test
	void parseParts_textThatIsNotJson_isNone() {
		assertThat(RoundTrip.parseParts(JSON.getNodeFactory().stringNode("[not json"))).isEmpty();
	}

}
