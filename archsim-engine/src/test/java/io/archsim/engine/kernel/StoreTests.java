package io.archsim.engine.kernel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stores beyond SimPy's recorded programs ({@link QueueCasesTests}).
 */
class StoreTests {

	private final Simulation simulation = new Simulation();

	@Test
	void size_afterPutsAndAGet_countsTheItemsLeft() {
		Store<String> store = new Store<>(this.simulation);
		store.put("a");
		store.put("b");

		store.get();

		assertThat(store.size()).isOne();
	}

}
