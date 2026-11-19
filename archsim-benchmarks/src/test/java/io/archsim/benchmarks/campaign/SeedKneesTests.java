package io.archsim.benchmarks.campaign;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A predicted knee searched again at other seeds: pg-hot-row's latest calibration, whose
 * knee of 856 req/s makes each search cheap, at seeds 1 and 2.
 */
class SeedKneesTests {

	private static final Experiment HOT_ROW = Experiments.standard().get("pg-hot-row");

	private static final JsonNode KNEE = JsonNodeFactory.instance.numberNode(856);

	private static ObjectNode bySeed = JsonNodeFactory.instance.objectNode();

	@BeforeAll
	static void search() {
		JsonNode calibration = ResultFiles
			.read(RepositoryLayout.current().results().resolve("pg-hot-row/calibration.json"))
			.orElseThrow();
		bySeed = new SeedKnees(Simulations.onTheEngine(), List.of(1, 2)).of(HOT_ROW, calibration.get("inputs"), KNEE);
	}

	@Test
	void of_aKnee_isSearchedAgainAtEachSeedTheDesignsOwnFirst() {
		assertThat(bySeed.get("knees").valueStream().map((knee) -> knee.get("seed").intValue())).containsExactly(42, 1,
				2);
	}

	@Test
	void of_aKnee_keepsItAsTheDesignsOwnSeeds() {
		assertThat(bySeed.at("/knees/0/kneeRps")).isEqualTo(KNEE);
	}

	@Test
	void of_aSeed_startsItsSearchAtTheKneeAlreadyFound() {
		assertThat(bySeed.at("/knees/1/tried/0/offeredRps").intValue()).isEqualTo(856);
	}

	// the same load, simulated at seeds 1 and 2: were the seed lost on the way to the
	// engine, the two would be one simulation
	@Test
	void of_twoSeeds_simulateTheSameLoadDifferently() {
		assertThat(bySeed.at("/knees/1/tried/0/p95").doubleValue())
			.isNotEqualTo(bySeed.at("/knees/2/tried/0/p95").doubleValue());
	}

	@Test
	void of_theSeedsKnees_areBoundedByTheLeastAndTheGreatest() {
		List<Double> knees = bySeed.get("knees")
			.valueStream()
			.map((knee) -> knee.get("kneeRps").doubleValue())
			.toList();

		assertThat(knees).contains(bySeed.get("lowRps").doubleValue(), bySeed.get("highRps").doubleValue())
			.allSatisfy((knee) -> assertThat(knee).isBetween(bySeed.get("lowRps").doubleValue(),
					bySeed.get("highRps").doubleValue()));
	}

}
