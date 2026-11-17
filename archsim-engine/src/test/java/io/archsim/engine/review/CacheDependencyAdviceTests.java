package io.archsim.engine.review;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review's advice to a part whose reads fail with its cache asks only for the part of
 * the fallback it lacks (ADR-0082).
 */
class CacheDependencyAdviceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** The part's link to the data store, read on a miss. */
	private static final String FETCH = """
			{"id": "api-db", "source": "api", "target": "db", "config": {"order": 1, "readsOnMiss": true}}""";

	private final DesignReview review = new DesignReview(StandardComponents.registry());

	@Test
	void review_aPartThatReadsTheStoreOnAMissButFailsWithTheCache_isAskedOnlyToCarryOn() {
		String advice = advice(design(false, true));

		assertThat(advice).isEqualTo("Turn on 'Carry on if this call fails' on the link to Cache, so a failed lookup "
				+ "counts as a miss and API reads DB instead (check it can take every read), or run the cache with "
				+ "replicas.");
	}

	@Test
	void review_aPartThatCarriesOnWithoutAMissLink_isAskedOnlyForTheLink() {
		String advice = advice(design(true, false));

		assertThat(advice).isEqualTo("Give API a link to the data store set to 'Reads only on a cache miss', so reads "
				+ "fall back to the data store when Cache fails (check it can take every read), or run the cache with "
				+ "replicas.");
	}

	@Test
	void review_aPartWithNeither_isAskedForBoth() {
		String advice = advice(design(false, false));

		assertThat(advice).startsWith("Turn on 'Carry on if this call fails' on the link to Cache and give API a link");
	}

	private String advice(ArchitectureDocument design) {
		return this.review.review(design)
			.stream()
			.filter((finding) -> finding.rule() == ReviewRule.CACHE_DEPENDENCY)
			.findFirst()
			.orElseThrow()
			.suggestion();
	}

	private static ArchitectureDocument design(boolean carriesOn, boolean readsOnMiss) {
		String fetch = readsOnMiss ? ", " + FETCH : "";
		return DesignDocuments.load(JSON.readTree("""
				{"schemaVersion": "1.10", "kind": "archsim.architecture", "metadata": {"name": "cache-aside"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				    {"id": "api", "type": "service", "label": "API"},
				    {"id": "cache", "type": "cache", "label": "Cache"},
				    {"id": "db", "type": "database", "label": "DB"}],
				  "edges": [{"id": "clients-api", "source": "clients", "target": "api"},
				    {"id": "api-cache", "source": "api", "target": "cache",
				     "config": {"order": 0, "continueOnFailure": %s}}%s]},
				 "workload": {"rps": 10}}""".formatted(carriesOn, fetch)));
	}

}
