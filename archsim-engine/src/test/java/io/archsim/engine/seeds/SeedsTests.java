package io.archsim.engine.seeds;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How the seeds of a group of runs read. */
class SeedsTests {

	@Test
	void describe_noSeeds_isEmpty() {
		assertThat(new Seeds(List.of()).describe()).isEmpty();
	}

	@Test
	void describe_oneSeed_namesIt() {
		assertThat(new Seeds(List.of(42L)).describe()).isEqualTo("seed 42");
	}

	@Test
	void describe_contiguousSeeds_isARange() {
		assertThat(new Seeds(List.of(42L, 43L, 44L)).describe()).isEqualTo("seeds 42–44");
	}

	@Test
	void describe_contiguousSeedsOutOfOrder_isARange() {
		assertThat(new Seeds(List.of(44L, 42L, 43L)).describe()).isEqualTo("seeds 42–44");
	}

	@Test
	void describe_seedsWithGaps_listsThemInTheRunsOrder() {
		assertThat(new Seeds(List.of(9L, 1L, 5L)).describe()).isEqualTo("seeds 9, 1, 5");
	}

	@Test
	void describe_aRepeatedSeed_listsEveryRun() {
		assertThat(new Seeds(List.of(42L, 42L, 43L)).describe()).isEqualTo("seeds 42, 42, 43");
	}

}
