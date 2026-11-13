package io.archsim.benchmarks.range;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.machine.ContentionFit;
import io.archsim.benchmarks.machine.KnownMachine;
import io.archsim.benchmarks.machine.MachineFit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The range fit's cache: the work done on a host machine kept apart from the engine's
 * own.
 */
class RangeFitCacheTests {

	private static final Path CACHE = Path.of("tmp", "archsim-range-fit-0.16.0.json");

	private static final MachineFit FIT = ContentionFit.of(KnownMachine.check()).orElseThrow();

	@Test
	void cachePath_aHostMachine_isAFileBesideTheEngines() {
		assertThat(RangeFit.cachePath(CACHE, FIT))
			.isEqualTo(Path.of("tmp", "archsim-range-fit-0.16.0-host-" + FIT.digest() + ".json"));
	}

	@Test
	void cachePath_anotherHostMachine_isAnotherFile() {
		assertThat(RangeFit.cachePath(CACHE, FIT.withClock("0.9"))).isNotEqualTo(RangeFit.cachePath(CACHE, FIT));
	}

}
