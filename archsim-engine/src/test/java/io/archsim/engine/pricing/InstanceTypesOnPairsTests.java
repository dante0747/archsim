package io.archsim.engine.pricing;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.document.Burstable;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance types a part covers when its vCPUs may be hyperthread pairs (ADR-0091):
 * each type must cover the need of the kind of vCPU it gives the part, whole cores on a
 * whole-core family's types and on a type of 1 vCPU, pairs on the others.
 */
class InstanceTypesOnPairsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Sku GRAVITON_MEDIUM = sku("m7g.medium", 1, 4, 0.0408);

	private static final Sku GRAVITON = sku("m7g.xlarge", 4, 16, 0.1632);

	private static final Sku GRAVITON_LARGER = sku("m7g.2xlarge", 8, 32, 0.3264);

	private static final Sku INTEL_LARGE = sku("m6i.large", 2, 8, 0.096);

	private static final Sku INTEL = sku("m6i.xlarge", 4, 16, 0.192);

	private static final Sku INTEL_LARGER = sku("m6i.2xlarge", 8, 32, 0.384);

	private static final PriceSnapshot SNAPSHOT = new PriceSnapshot("aws", "us-east-1", "2026-10-28", List.of(), "USD",
			false, null,
			Map.of(SkuCategory.COMPUTE,
					List.of(GRAVITON_MEDIUM, GRAVITON, GRAVITON_LARGER, INTEL_LARGE, INTEL, INTEL_LARGER)),
			Map.of(), null);

	private static final MachineKind COMPUTE = new MachineKind(SkuCategory.COMPUTE, null);

	private static final InstanceTypes TYPES = new InstanceTypes(SNAPSHOT, Purchase.ON_DEMAND);

	private static final InstanceTypes INTEL_ONLY = new InstanceTypes(SNAPSHOT,
			new Purchase("on_demand", List.of("m6i"), Burstable.AUTO));

	@Test
	void cover_onTheDefault_isTheCheapestTypeThatCoversItsOwnFamilysNeed() {
		assertThat(TYPES.cover(COMPUTE, onTheDefault(4, 8, 4))).hasValue(GRAVITON);
	}

	@Test
	void cover_onTheDefaultWithFamiliesWhoseVcpusArePairs_coversThePairsNeed() {
		assertThat(INTEL_ONLY.cover(COMPUTE, onTheDefault(4, 8, 4))).hasValue(INTEL_LARGER);
	}

	@Test
	void cover_onTheDefaultWithAWholeCoreNeedOfOneVcpu_coversThePairsNeedOnATypeOfTwo() {
		assertThat(INTEL_ONLY.cover(COMPUTE, onTheDefault(1, 4, 8))).hasValue(INTEL);
	}

	@Test
	void cover_onTheDefaultWithAWholeCoreNeedOfOneVcpu_mayTakeATypeOfOne() {
		assertThat(TYPES.cover(COMPUTE, onTheDefault(1, 4, 2))).hasValue(GRAVITON_MEDIUM);
	}

	@Test
	void cover_onASiblingSpeedOfItsOwn_coversThePairsNeedOnAWholeCoreFamilysTypeToo() {
		MachineSize size = new MachineSize(4.0, 4.0, null, new OnPairs(8, null));

		assertThat(TYPES.cover(COMPUTE, size)).hasValue(GRAVITON_LARGER);
	}

	@Test
	void choose_aNamedTypeOfPairsTooSmallForThePairsNeed_isTheCheapestThatCoversItsOwnFamilysNeed() {
		Machine machine = TYPES.choose("service", settings("{\"instanceType\": \"m6i.xlarge\"}"), onTheDefault(4, 8, 4))
			.orElseThrow();

		assertThat(machine).isEqualTo(new Machine(SkuCategory.COMPUTE, null, GRAVITON, false, false));
	}

	@Test
	void choose_aNamedTypeOfPairsThatCoversThePairsNeed_isKept() {
		Machine machine = TYPES
			.choose("service", settings("{\"instanceType\": \"m6i.2xlarge\"}"), onTheDefault(4, 8, 4))
			.orElseThrow();

		assertThat(machine).isEqualTo(new Machine(SkuCategory.COMPUTE, null, INTEL_LARGER, false, true));
	}

	@Test
	void runsAsPairs_onTheDefault_aWholeCoreFamilysTypeRunsWholeCores() {
		assertThat(new OnPairs(8, "aws").runsAsPairs(GRAVITON)).isFalse();
	}

	@Test
	void runsAsPairs_onTheDefault_anotherFamilysTypeRunsPairs() {
		assertThat(new OnPairs(8, "aws").runsAsPairs(INTEL)).isTrue();
	}

	@Test
	void runsAsPairs_onASiblingSpeedOfItsOwn_aWholeCoreFamilysTypeRunsPairs() {
		assertThat(new OnPairs(8, null).runsAsPairs(GRAVITON)).isTrue();
	}

	@Test
	void runsAsPairs_aTypeOfOneVcpu_runsAWholeCore() {
		assertThat(new OnPairs(2, null).runsAsPairs(GRAVITON_MEDIUM)).isFalse();
	}

	@Test
	void fitsIn_onWholeCoresOnly_coversTheVcpuOnEveryType() {
		assertThat(new MachineSize(4.0, 4.0, null).fitsIn(INTEL)).isTrue();
	}

	/**
	 * A part on the default: whole cores on a whole-core family's types, pairs elsewhere.
	 */
	private static MachineSize onTheDefault(double wholeCores, double pairs, double memoryGib) {
		return new MachineSize(wholeCores, memoryGib, null, new OnPairs(pairs, "aws"));
	}

	private static LenientSettings settings(String config) {
		return LenientSettings.of(JSON.readTree(config));
	}

	private static Sku sku(String name, double vcpu, double memoryGib, double hourly) {
		String family = name.substring(0, name.indexOf('.'));
		return new Sku(name, family, vcpu, memoryGib, Map.of("on_demand", hourly), List.of(), false, null, null);
	}

}
