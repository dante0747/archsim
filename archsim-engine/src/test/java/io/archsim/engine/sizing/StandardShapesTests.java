package io.archsim.engine.sizing;

import org.junit.jupiter.api.Test;

import io.archsim.engine.sizing.StandardShapes.Shape;

import static org.assertj.core.api.Assertions.assertThat;

/** The cheapest standard shape that covers a size: the Python engine's shapes. */
class StandardShapesTests {

	/**
	 * A part whose vCPUs may be pairs (ADR-0091): 1 vCPU on whole cores, 4 on a shape of
	 * 2 or more, which runs them as pairs.
	 */
	private static final CpuKinds ONE_OR_FOUR = new CpuKinds(new CpuSize(1, "whole cores", 1, null),
			new CpuSize(4, "pairs", 3.6, 0.3), true);

	@Test
	void cheapest_aSize_isTheCheapestCommonCountWithEnoughMemoryPerVcpu() {
		assertThat(StandardShapes.cheapest(3, 1, new SetSpecs(null, null), null)).hasValue(new Shape(4, 8));
	}

	@Test
	void cheapest_muchMemory_takesTheMemoryOptimisedRatio() {
		assertThat(StandardShapes.cheapest(1, 20, new SetSpecs(null, null), null)).hasValue(new Shape(4, 32));
	}

	@Test
	void cheapest_aCpuSetOnThePart_keepsIt() {
		assertThat(StandardShapes.cheapest(2, 1, new SetSpecs(2.0, null), null)).hasValue(new Shape(2, 4));
	}

	@Test
	void cheapest_memorySetOnThePart_keepsIt() {
		assertThat(StandardShapes.cheapest(1, 1, new SetSpecs(null, 16.0), null)).hasValue(new Shape(2, 16));
	}

	@Test
	void cheapest_onPairsWithMemoryOneVcpuHolds_isAShapeOfOneVcpu() {
		assertThat(StandardShapes.cheapest(1, 8, new SetSpecs(null, null), ONE_OR_FOUR)).hasValue(new Shape(1, 8));
	}

	@Test
	void cheapest_onPairsWithMoreMemoryThanOneVcpuHolds_coversThePairsNeed() {
		// 2 vCPU and 16 GiB would hold the memory, but its two vCPUs are pairs,
		// which need 4
		assertThat(StandardShapes.cheapest(1, 16, new SetSpecs(null, null), ONE_OR_FOUR)).hasValue(new Shape(4, 16));
	}

}
