package io.archsim.engine.sizing;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.engine.sizing.Steps.Explained;

import static org.assertj.core.api.Assertions.assertThat;

/** Rounding a need up to a common size. */
class StepsTests {

	@Test
	void cpu_busyCores_areSizedForTheTargetUtilization() {
		Explained cpu = Steps.cpu(2.0, "200 req/s");

		assertThat(cpu.value()).isEqualTo(4.0);
		assertThat(cpu.why()).isEqualTo("200 req/s = 2 cores busy; at a 60% target that is 3.33 → 4 vCPU");
	}

	@Test
	void cpu_aFewPointsOverTheTargetOfTheSizeBelow_keepsTheSizeBelow() {
		Explained cpu = Steps.cpu(2.6, "intro");

		assertThat(cpu.value()).isEqualTo(4.0);
		assertThat(cpu.why())
			.endsWith("; 4 vCPU instead, since it runs the peak at 65% (up to 65% rather than double the cores)");
	}

	@Test
	void capped_moreCoresThanHandlers_isCappedAtTheHandlers() {
		Explained cpu = Steps.capped(new Explained(8, "why"), 3, "3 workers cannot use more cores");

		assertThat(cpu).isEqualTo(new Explained(3, "why; capped at 3 because 3 workers cannot use more cores"));
	}

	@Test
	void cappedOnHyperthreads_moreVcpuThanTwoForEachHandler_isCappedAtACoreForEach() {
		Explained cpu = Steps.cappedOnHyperthreads(new Explained(16, "why"), 4, "4 workers cannot use more cores");

		assertThat(cpu).isEqualTo(new Explained(8,
				"why; capped at 8, the hyperthreads of 4 cores, because 4 workers cannot use more cores"));
	}

	@Test
	void cappedOnHyperthreads_twoVcpuForEachHandler_isKept() {
		Explained cpu = new Explained(8, "why");

		assertThat(Steps.cappedOnHyperthreads(cpu, 4, "4 workers cannot use more cores")).isEqualTo(cpu);
	}

	@Test
	void cappedOnHyperthreads_oneHandler_isLeftToTheCapOfWholeCores() {
		// one handler never has two vCPUs busy: its cap is one vCPU, as on whole cores
		Explained cpu = new Explained(8, "why");

		assertThat(Steps.cappedOnHyperthreads(cpu, 1, "it runs one command at a time")).isEqualTo(cpu);
	}

	@Test
	void atTarget_workExplainedSoFar_isSizedForTheTargetFromThere() {
		Explained cpu = Steps.atTarget(2.0, "so far", "");

		assertThat(cpu).isEqualTo(new Explained(4, "so far; at a 60% target that is 3.33 → 4 vCPU"));
	}

	@Test
	void atTarget_aFewPointsOverTheTargetOfTheSizeBelow_saysWhatItsShareIsOf() {
		Explained cpu = Steps.atTarget(2.5, "so far", " of what they can do");

		assertThat(cpu).isEqualTo(new Explained(4, "so far; at a 60% target that is 4.17 → 8 vCPU; 4 vCPU instead, "
				+ "since it runs the peak at 63% of what they can do (up to 65% rather than double the cores)"));
	}

	@Test
	void up_beyondTheLastStep_isAMultipleOfIt() {
		assertThat(Steps.up(1200, List.of(1.0, 512.0))).isEqualTo(1536.0);
	}

}
