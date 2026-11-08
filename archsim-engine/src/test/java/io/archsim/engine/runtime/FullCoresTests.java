package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Full cores at a fixed speed: work takes its length, a ceiling stays as it is. */
class FullCoresTests {

	@Test
	void start_work_takesItsLength() {
		double took = FullCores.INSTANCE.start(12.5);

		assertThat(took).isEqualTo(12.5);
	}

	@Test
	void capacity_aCeiling_isThatCeiling() {
		double ceiling = FullCores.INSTANCE.capacity(250.0, 4.0, 100.0);

		assertThat(ceiling).isEqualTo(250.0);
	}

}
