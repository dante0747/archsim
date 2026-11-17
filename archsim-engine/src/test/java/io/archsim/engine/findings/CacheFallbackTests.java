package io.archsim.engine.findings;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advice after a cache's outage asks only for what the cache's callers do not do yet
 * (ADR-0082).
 */
class CacheFallbackTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Map<String, String> LABELS = Map.of("api", "API", "worker", "Worker", "writer", "Writer",
			"cache", "Cache", "db", "DB");

	@Test
	void afterOutage_aCallerWithNeitherSetting_asksForBoth() {
		String advice = CacheFallback.afterOutage(design(false, false), "cache", LABELS);

		assertThat(advice).isEqualTo(CacheFallback.FALL_BACK);
	}

	@Test
	void afterOutage_aCallerThatCarriesOnWithoutAMissLink_asksOnlyForTheLink() {
		String advice = CacheFallback.afterOutage(design(true, false), "cache", LABELS);

		assertThat(advice).isEqualTo("Treat cache errors as misses and fall back to the database: give API a link to "
				+ "the database set to 'Reads only on a cache miss' (check the database can take every read), or run "
				+ "a replicated cache.");
	}

	@Test
	void afterOutage_aCallerWithBothSettings_asksForCapacity() {
		String advice = CacheFallback.afterOutage(design(true, true), "cache", LABELS);

		assertThat(advice).isEqualTo("Its caller, API, already treats a failed lookup on Cache as a miss and reads "
				+ "DB ('Carry on if this call fails', 'Reads only on a cache miss'): what ran short was the capacity "
				+ "of API and DB for the reads Cache serves. Size them for those reads, or run a replicated cache so "
				+ "that losing one node does not move every read.");
	}

	@Test
	void afterOutage_twoCallersOneWithoutAMissLink_namesOnlyThatOne() {
		String advice = CacheFallback.afterOutage(twoCallers(), "cache", LABELS);

		assertThat(advice).isEqualTo("Treat cache errors as misses and fall back to the database: give Worker a link "
				+ "to the database set to 'Reads only on a cache miss' (check the database can take every read), or "
				+ "run a replicated cache.");
	}

	@Test
	void afterOutage_aCallerThatOnlyFailsItsLookups_asksOnlyToCarryOn() {
		String advice = CacheFallback.afterOutage(design(false, true), "cache", LABELS);

		assertThat(advice).isEqualTo("Treat cache errors as misses and fall back to the database: turn on 'Carry on if "
				+ "this call fails' on API → Cache, or run a replicated cache.");
	}

	@Test
	void afterOutage_aLinkThatOnlyInvalidates_isNotAskedToFallBack() {
		String advice = CacheFallback.afterOutage(withAWriter(), "cache", LABELS);

		assertThat(advice).isEqualTo("Its caller, API, already treats a failed lookup on Cache as a miss and reads "
				+ "DB ('Carry on if this call fails', 'Reads only on a cache miss'): what ran short was the capacity "
				+ "of API and DB for the reads Cache serves. Size them for those reads, or run a replicated cache so "
				+ "that losing one node does not move every read.");
	}

	@Test
	void afterOutage_aCacheNoServiceLooksUp_givesTheGeneralAdvice() {
		String advice = CacheFallback.afterOutage(writerOnly(), "cache", LABELS);

		assertThat(advice).isEqualTo(CacheFallback.FALL_BACK);
	}

	private static ArchitectureDocument design(boolean carriesOn, boolean readsOnMiss) {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "cache-aside"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "label": "API"},
				    {"id": "cache", "type": "cache", "label": "Cache"},
				    {"id": "db", "type": "database", "label": "DB"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": %s}},
				    {"id": "api-db", "source": "api", "target": "db",
				     "config": {"order": 1, "readsOnMiss": %s}}]},
				 "workload": {"rps": 10}}""".formatted(carriesOn, readsOnMiss)));
	}

	private static ArchitectureDocument twoCallers() {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "two callers"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service"}, {"id": "worker", "type": "service"},
				    {"id": "cache", "type": "cache"}, {"id": "db", "type": "database"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "clients-worker", "source": "clients", "target": "worker"},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": true}},
				    {"id": "api-db", "source": "api", "target": "db", "config": {"order": 1, "readsOnMiss": true}},
				    {"id": "worker-cache", "source": "worker", "target": "cache",
				     "config": {"continueOnFailure": true}}]},
				 "workload": {"rps": 10}}"""));
	}

	private static ArchitectureDocument withAWriter() {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "a writer"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service"}, {"id": "writer", "type": "service"},
				    {"id": "cache", "type": "cache"}, {"id": "db", "type": "database"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "clients-writer", "source": "clients", "target": "writer",
				     "config": {"requestTypes": ["write"]}},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": true}},
				    {"id": "api-db", "source": "api", "target": "db", "config": {"order": 1, "readsOnMiss": true}},
				    {"id": "writer-cache", "source": "writer", "target": "cache",
				     "config": {"requestTypes": ["write"]}}]},
				 "workload": {"rps": 10}}"""));
	}

	private static ArchitectureDocument writerOnly() {
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.9", "kind": "archsim.architecture", "metadata": {"name": "a writer alone"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "writer", "type": "service"}, {"id": "cache", "type": "cache"}],
				  "edges": [{"id": "clients-writer", "source": "clients", "target": "writer",
				     "config": {"requestTypes": ["write"]}},
				    {"id": "writer-cache", "source": "writer", "target": "cache",
				     "config": {"requestTypes": ["write"]}}]},
				 "workload": {"rps": 10, "readRatio": 0.0}}"""));
	}

}
