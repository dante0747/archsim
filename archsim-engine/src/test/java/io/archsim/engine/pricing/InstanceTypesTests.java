package io.archsim.engine.pricing;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.document.Burstable;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;

import static org.assertj.core.api.Assertions.assertThat;

/** The instance types a design may buy from a price snapshot. */
class InstanceTypesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Sku SMALL = sku("c6g.large", 2, 4, 0.068);

	private static final Sku LARGE = sku("c6g.xlarge", 4, 8, 0.136);

	private static final Sku OLD = sku("c4.large", 2, 3.75, 0.1);

	private static final Sku BURSTABLE = new Sku("t4g.small", "t4g", 2, 2, Map.of("on_demand", 0.0168), List.of(), true,
			0.2, 0.04);

	private static final Sku POSTGRES = new Sku("db.m6g.large", "m6g", 2, 8, Map.of("on_demand", 0.152),
			List.of("postgresql"), false, null, null);

	private static final PriceSnapshot SNAPSHOT = new PriceSnapshot("aws", "us-east-1", "2026-10-28", List.of(), "USD",
			false, null,
			Map.of(SkuCategory.COMPUTE, List.of(SMALL, LARGE, OLD, BURSTABLE), SkuCategory.DATABASE, List.of(POSTGRES)),
			Map.of(), null);

	private static final MachineKind COMPUTE = new MachineKind(SkuCategory.COMPUTE, null);

	private final InstanceTypes types = new InstanceTypes(SNAPSHOT, Purchase.ON_DEMAND);

	@Test
	void cover_aSize_isTheCheapestTypeWithAtLeastIt() {
		assertThat(this.types.cover(COMPUTE, size(3, 4, null))).hasValue(LARGE);
	}

	@Test
	void cover_aNamedFamily_restrictsTheList() {
		InstanceTypes restricted = new InstanceTypes(SNAPSHOT,
				new Purchase("on_demand", List.of("c4"), Burstable.AUTO));

		assertThat(restricted.cover(COMPUTE, size(1, 1, null))).hasValue(OLD);
	}

	@Test
	void cover_aLightPartThatRunsOnCredits_getsABurstableType() {
		assertThat(this.types.cover(COMPUTE, size(1, 1, new Busy(0.1, 0.1)))).hasValue(BURSTABLE);
	}

	@Test
	void cover_aPartTooBusyForCredits_getsAFixedType() {
		assertThat(this.types.cover(COMPUTE, size(1, 1, new Busy(1.0, 0.5)))).hasValue(SMALL);
	}

	@Test
	void cover_burstableNever_neverGetsABurstableType() {
		InstanceTypes never = new InstanceTypes(SNAPSHOT, new Purchase("on_demand", List.of(), Burstable.NEVER));

		assertThat(never.cover(COMPUTE, size(1, 1, new Busy(0.1, 0.1)))).hasValue(SMALL);
	}

	@Test
	void cover_aSizeNoTypeHas_isNone() {
		assertThat(this.types.cover(COMPUTE, size(64, 4, null))).isEmpty();
	}

	@Test
	void choose_thePartsOwnTypeThatCoversIt_isKept() {
		Machine machine = this.types.choose("service", settings("{\"instanceType\": \"c6g.xlarge\"}"), size(1, 1, null))
			.orElseThrow();

		assertThat(machine).isEqualTo(new Machine(SkuCategory.COMPUTE, null, LARGE, false, true));
	}

	@Test
	void choose_anEngineTheSnapshotHasNoTypeFor_runsSelfManagedOnCompute() {
		Machine machine = this.types.choose("database", settings("{\"technology\": \"mysql\"}"), size(1, 1, null))
			.orElseThrow();

		assertThat(machine).isEqualTo(new Machine(SkuCategory.COMPUTE, "mysql", SMALL, true, false));
	}

	@Test
	void choose_thePartsOwnTypeTooSmall_isTheCheapestThatCoversIt() {
		Machine machine = this.types.choose("service", settings("{\"instanceType\": \"c6g.large\"}"), size(3, 4, null))
			.orElseThrow();

		assertThat(machine).isEqualTo(new Machine(SkuCategory.COMPUTE, null, LARGE, false, false));
	}

	@Test
	void choose_aDatabaseNamingAComputeType_runsSelfManagedOnIt() {
		Machine machine = this.types
			.choose("database", settings("{\"technology\": \"postgresql\", \"instanceType\": \"c6g.xlarge\"}"),
					size(1, 1, null))
			.orElseThrow();

		assertThat(machine).isEqualTo(new Machine(SkuCategory.COMPUTE, "postgresql", LARGE, true, true));
	}

	@Test
	void choose_anEngineOfferedButNoTypeCovers_hasNoType() {
		Machine machine = this.types.choose("database", settings("{}"), size(64, 4, null)).orElseThrow();

		assertThat(machine).isEqualTo(new Machine(SkuCategory.DATABASE, "postgresql", null, false, false));
	}

	@Test
	void choose_aManagedService_isNotOnAMachine() {
		assertThat(this.types.choose("database", settings("{\"technology\": \"dynamodb\"}"), size(1, 1, null)))
			.isEmpty();
	}

	@Test
	void choose_genericRates_haveNoMachines() {
		InstanceTypes generic = new InstanceTypes(PriceSnapshot.GENERIC, Purchase.ON_DEMAND);

		assertThat(generic.choose("service", settings("{}"), size(1, 1, null))).isEmpty();
	}

	private static LenientSettings settings(String config) {
		return LenientSettings.of(JSON.readTree(config));
	}

	private static MachineSize size(double cpu, double memoryGib, @Nullable Busy busy) {
		return new MachineSize(cpu, memoryGib, busy);
	}

	/**
	 * A type of the family its name starts with, such as {@code c6g} for
	 * {@code c6g.large}.
	 */
	private static Sku sku(String name, double vcpu, double memoryGib, double hourly) {
		String family = name.substring(0, name.indexOf('.'));
		return new Sku(name, family, vcpu, memoryGib, Map.of("on_demand", hourly), List.of(), false, null, null);
	}

}
