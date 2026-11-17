package io.archsim.engine.findings;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who looks a cache up for reads, and who of them falls back to the data store when a
 * lookup fails (ADR-0082).
 */
class CacheCallersTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CacheCallers callers = CacheCallers.of(design(), "cache");

	@Test
	void lookups_aCache_areTheLinksThatCarryReadsToItOtherThanAMissLink() {
		assertThat(this.callers.lookups()).extracting(EdgeSpec::id)
			.containsExactly("clients-cache", "api-cache", "worker-cache");
	}

	@Test
	void fromServices_aCache_areTheLookupsServicesMake() {
		assertThat(this.callers.fromServices()).extracting(EdgeSpec::id).containsExactly("api-cache", "worker-cache");
	}

	@Test
	void fallsBack_aServiceThatCarriesOnAndReadsTheStoreOnAMiss_isTrue() {
		assertThat(this.callers.fallsBack(lookup("api-cache"))).isTrue();
	}

	@Test
	void fallsBack_aServiceThatCarriesOnWithoutAMissLink_isFalse() {
		assertThat(this.callers.fallsBack(lookup("worker-cache"))).isFalse();
	}

	@Test
	void fallsBack_aPartOtherThanAServiceThatCarriesOn_isFalse() {
		assertThat(this.callers.fallsBack(lookup("clients-cache"))).isFalse();
	}

	@Test
	void stores_aCaller_areThePartsItReadsOnAMiss() {
		assertThat(this.callers.stores("api")).containsExactly("db");
	}

	private EdgeSpec lookup(String id) {
		return this.callers.lookups().stream().filter((edge) -> edge.id().equals(id)).findFirst().orElseThrow();
	}

	private static ArchitectureDocument design() {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "callers"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "jobs", "type": "traffic_source"},
				    {"id": "api", "type": "service"}, {"id": "worker", "type": "service"},
				    {"id": "writer", "type": "service"}, {"id": "front", "type": "cache"},
				    {"id": "cache", "type": "cache"}, {"id": "db", "type": "database"}],
				  "edges": [{"id": "clients-cache", "source": "clients", "target": "cache",
				     "config": {"continueOnFailure": true}},
				    {"id": "jobs-writer", "source": "jobs", "target": "writer"},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": true}},
				    {"id": "api-db", "source": "api", "target": "db", "config": {"order": 1, "readsOnMiss": true}},
				    {"id": "worker-cache", "source": "worker", "target": "cache",
				     "config": {"continueOnFailure": true}},
				    {"id": "writer-cache", "source": "writer", "target": "cache",
				     "config": {"requestTypes": ["write"]}},
				    {"id": "front-cache", "source": "front", "sourcePort": "miss", "target": "cache"}]},
				 "workload": {"rps": 10}}"""));
	}

}
