package io.archsim.engine.calibration.resources;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code cpuShare} fitted as the Python engine's {@code fit_cpu_share} fits it, where the
 * golden files do not reach. Expected values are the Python engine's.
 */
class CpuFitsTests {

	@Test
	void fitShare_cpuPerRequestAboveTheServiceTime_isCappedAtOneWithANote() {
		CpuFit fit = CpuFits.fitShare(List.of(new RatePoint(10, 0.2), new RatePoint(20, 0.4)), 5.0, 1.0).orElseThrow();

		assertThat(fit.share()).isEqualTo(1.0);
		assertThat(fit.cpuMs()).isEqualTo(20.000000000000004);
		assertThat(fit.capped()).isTrue();
		assertThat(fit.error()).isEqualTo(0.75);
		assertThat(fit.points()).isEqualTo(2);
		assertThat(fit.notes()).containsExactly("20 ms of CPU per request is more than the 5 ms service time: the "
				+ "process spends CPU outside the handler time (HTTP parsing, drivers, garbage collection). The CPU "
				+ "share is at most 1, so the model under-counts CPU; use a service time that covers the CPU per "
				+ "request");
	}

	@Test
	void fitShare_noTraffic_isEmpty() {
		assertThat(CpuFits.fitShare(List.of(new RatePoint(0, 0.2)), 5.0, 1.0)).isEmpty();
	}

	@Test
	void fitShare_noServiceTime_isEmpty() {
		assertThat(CpuFits.fitShare(List.of(new RatePoint(10, 0.2)), 0.0, 1.0)).isEmpty();
	}

}
