package io.archsim.engine.sizing;

import org.junit.jupiter.api.Test;

import io.archsim.engine.sizing.StandardShapes.Shape;

import static org.assertj.core.api.Assertions.assertThat;

/** The cheapest standard shape that covers a size: the Python engine's shapes. */
class StandardShapesTests {

	@Test
	void cheapest_aSize_isTheCheapestCommonCountWithEnoughMemoryPerVcpu() {
		assertThat(StandardShapes.cheapest(3, 1, new SetSpecs(null, null))).hasValue(new Shape(4, 8));
	}

	@Test
	void cheapest_muchMemory_takesTheMemoryOptimisedRatio() {
		assertThat(StandardShapes.cheapest(1, 20, new SetSpecs(null, null))).hasValue(new Shape(4, 32));
	}

	@Test
	void cheapest_aCpuSetOnThePart_keepsIt() {
		assertThat(StandardShapes.cheapest(2, 1, new SetSpecs(2.0, null))).hasValue(new Shape(2, 4));
	}

	@Test
	void cheapest_memorySetOnThePart_keepsIt() {
		assertThat(StandardShapes.cheapest(1, 1, new SetSpecs(null, 16.0))).hasValue(new Shape(2, 16));
	}

}
