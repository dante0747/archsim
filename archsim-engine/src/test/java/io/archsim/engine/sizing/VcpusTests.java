package io.archsim.engine.sizing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.host.HostMachine;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.engine.sizing.Placement.Work;
import io.archsim.engine.sizing.Vcpus.CpuSize;
import io.archsim.engine.sizing.Vcpus.Hyperthreads;
import io.archsim.model.document.HostSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

/** What a part's vCPUs are, and the vCPU it gets for its work on them. */
class VcpusTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Hyperthreads at the sibling speed of Intel and AMD cores. */
	private static final Hyperthreads HYPERTHREADS = new Hyperthreads(0.6);

	private static final HostSpec HOST = new HostSpec(2, 2, 0.6, 1.0, 0.0, 0.0);

	private static final String REASON = "its workers cannot use more cores";

	/** Handlers enough for their cap not to count. */
	private static final int MANY_HANDLERS = 64;

	private static final double TOLERANCE = 1e-9;

	@Test
	void of_aPartThatSetsItsSiblingSpeed_hasHyperthreads() {
		assertThat(Vcpus.of(null, settings("{\"siblingSpeed\": 0.6}"))).isEqualTo(HYPERTHREADS);
	}

	@Test
	void of_aPartWithoutASiblingSpeed_hasWholeCores() {
		assertThat(Vcpus.of(null, settings("{}"))).isEqualTo(Vcpus.WHOLE_CORES);
	}

	@Test
	void of_aSiblingSpeedOfOne_hasWholeCores() {
		assertThat(Vcpus.of(null, settings("{\"siblingSpeed\": 1.0}"))).isEqualTo(Vcpus.WHOLE_CORES);
	}

	@Test
	void of_aPartOnADesignWithAHost_hasWholeCoresWhateverItsSiblingSpeed() {
		// the host's threads slow its work instead, through its placement's speed
		assertThat(Vcpus.of(HOST, settings("{\"siblingSpeed\": 0.6}"))).isEqualTo(Vcpus.WHOLE_CORES);
	}

	@ParameterizedTest
	@ValueSource(doubles = { -0.5, 0.0, 1.0, 1.5 })
	void hyperthreads_aSiblingSpeedThatIsNotBetweenZeroAndOne_isRefused(double siblingSpeed) {
		assertThatIllegalArgumentException().isThrownBy(() -> new Hyperthreads(siblingSpeed))
			.withMessage("hyperthreads have a sibling speed above 0 and below 1, not " + siblingSpeed);
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0.3, 0.4, 0.5, 0.6, 0.8 })
	void coresPerVcpu_aSiblingSpeed_isThatSpeed(double siblingSpeed) {
		// what each vCPU does with every one of them busy, which a run reaches under load
		assertThat(new Hyperthreads(siblingSpeed).coresPerVcpu()).isCloseTo(siblingSpeed, within(TOLERANCE));
	}

	@ParameterizedTest
	@ValueSource(doubles = { 4, 6, 8, 512 })
	void coresPerVcpu_anyNumberOfPairs_isWhatEachDoesWithAllOfThemBusy(double vcpus) {
		HostMachine own = HostMachine.ownHyperthreads(vcpus, HYPERTHREADS.siblingSpeed());

		assertThat(own.speedWithEveryThreadBusy()).isCloseTo(HYPERTHREADS.coresPerVcpu(), within(TOLERANCE));
	}

	@Test
	void forWork_onWholeCores_isSizedAtTheTargetAndCappedAtTheHandlers() {
		CpuSize cpu = Vcpus.WHOLE_CORES.forWork(new Work(2.4, "intro"), 3, REASON);

		assertThat(cpu.vcpu()).isEqualTo(3.0);
		assertThat(cpu.why()).isEqualTo("intro = 2.4 cores busy; at a 60% target that is 4 → 4 vCPU; capped at 3 "
				+ "because its workers cannot use more cores");
		assertThat(cpu.needed()).isEqualTo(2.4 / Sizing.TARGET_CPU);
	}

	@Test
	void forWork_onHyperthreads_isSizedForTheTargetShareOfWhatTheyCanDo() {
		CpuSize cpu = HYPERTHREADS.forWork(new Work(2.4, "240 req/s"), MANY_HANDLERS, REASON);

		assertThat(cpu.vcpu()).isEqualTo(8.0);
		assertThat(cpu.why()).isEqualTo("240 req/s = 2.4 cores busy, which is what 4 vCPU can do as hyperthreads, "
				+ "each counting as 0.6 of a core (the sibling speed); at a 60% target that is 6.67 → 8 vCPU");
	}

	@Test
	void forWork_onHyperthreads_needsItsWholeCoreNeedOverWhatAVcpuCanDo() {
		CpuSize cpu = HYPERTHREADS.forWork(new Work(2.4, "intro"), MANY_HANDLERS, REASON);

		assertThat(cpu.needed()).isCloseTo(2.4 / Sizing.TARGET_CPU / 0.6, within(TOLERANCE));
	}

	@Test
	void forWork_onHyperthreadsWithAFasterSibling_needsFewerVcpu() {
		// 1.9 cores' work: 3.96 vCPU at a sibling speed of 0.8, 5.28 at 0.6
		CpuSize cpu = new Hyperthreads(0.8).forWork(new Work(1.9, "intro"), MANY_HANDLERS, REASON);

		assertThat(cpu.vcpu()).isEqualTo(4.0);
		assertThat(cpu.needed()).isCloseTo(1.9 / Sizing.TARGET_CPU / 0.8, within(TOLERANCE));
	}

	@Test
	void forWork_onHyperthreadsSlowerThanHalf_countsAVcpuAsItsSiblingSpeed() {
		// one busy vCPU on each core would do more, but a run under load has all of them
		// busy, each at the sibling speed
		CpuSize cpu = new Hyperthreads(0.3).forWork(new Work(1.9, "intro"), MANY_HANDLERS, REASON);

		assertThat(cpu.vcpu()).isEqualTo(16.0);
		assertThat(cpu.needed()).isCloseTo(1.9 / Sizing.TARGET_CPU / 0.3, within(TOLERANCE));
	}

	@Test
	void forWork_onHyperthreadsAtASiblingSpeedOfAHalf_goesFromOneVcpuToFour() {
		// two such vCPUs with both busy do no more than the one vCPU of whole cores
		CpuSize cpu = new Hyperthreads(0.5).forWork(new Work(0.66, "intro"), MANY_HANDLERS, REASON);

		assertThat(cpu.vcpu()).isEqualTo(4.0);
	}

	@Test
	void forWork_onHyperthreadsFasterThanHalf_canGetTwoVcpu() {
		CpuSize cpu = HYPERTHREADS.forWork(new Work(0.7, "intro"), MANY_HANDLERS, REASON);

		assertThat(cpu.vcpu()).isEqualTo(2.0);
	}

	@Test
	void forWork_onHyperthreadsAFewPointsOverTheTargetOfTheSizeBelow_keepsTheSizeBelow() {
		// 1.5 cores' work is 62.5% of what four hyperthreads can do: the slack of whole
		// cores
		CpuSize cpu = HYPERTHREADS.forWork(new Work(1.5, "intro"), MANY_HANDLERS, REASON);

		assertThat(cpu.vcpu()).isEqualTo(4.0);
		assertThat(cpu.why()).endsWith("; 4 vCPU instead, since it runs the peak at 63% of what they can do "
				+ "(up to 65% rather than double the cores)");
	}

	@Test
	void forWork_onHyperthreadsWorkThatNeedsNoMoreThanOneVcpu_isSizedAsOnWholeCores() {
		// one vCPU has no sibling
		Work light = new Work(0.65, "intro");

		assertThat(HYPERTHREADS.forWork(light, 4, REASON)).isEqualTo(Vcpus.WHOLE_CORES.forWork(light, 4, REASON))
			.extracting(CpuSize::vcpu)
			.isEqualTo(1.0);
	}

	@Test
	void forWork_onHyperthreadsWithOneHandler_isSizedAsOnWholeCores() {
		// one handler never has two vCPUs busy: a single-threaded cache, one event loop
		Work heavy = new Work(0.9, "intro");

		assertThat(HYPERTHREADS.forWork(heavy, 1, REASON)).isEqualTo(Vcpus.WHOLE_CORES.forWork(heavy, 1, REASON))
			.extracting(CpuSize::vcpu)
			.isEqualTo(1.0);
	}

	@Test
	void forWork_onHyperthreadsWithFewHandlers_isCappedAtTwoVcpuForEach() {
		CpuSize cpu = HYPERTHREADS.forWork(new Work(2.4, "intro"), 3, REASON);

		assertThat(cpu.vcpu()).isEqualTo(6.0);
		assertThat(cpu.why()).endsWith("; capped at 6, the hyperthreads of 3 cores, because " + REASON);
	}

	@Test
	void forWork_onHyperthreadsMoreWorkThanTheLargestSizeDoes_isTheLargestSize() {
		assertThat(HYPERTHREADS.forWork(new Work(400, "intro"), 4096, REASON).vcpu()).isEqualTo(Sizing.MAX_CPU);
	}

	private static LenientSettings settings(String config) {
		return LenientSettings.of(JSON.readTree(config));
	}

}
