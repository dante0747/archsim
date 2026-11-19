package io.archsim.engine.sizing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-sizing a part on the default of schema 1.11 (ADR-0091): its vCPUs are whole cores
 * on a whole-core family's types and hyperthread pairs at 0.6 elsewhere, so it needs the
 * same work's vCPU on each kind, and gets the cheapest type that covers its own family's
 * need; at generic prices, with no family, pairs.
 */
class AutoSizerDefaultTests {

	/** 2.4 cores' work a replica: 4 vCPU on whole cores, 8 on pairs at 0.6. */
	private static final String WORKERS = "{\"workersPerReplica\": 16}";

	private static final int RPS = 480;

	private final AutoSizer sizer = new AutoSizer(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void sizes_aServiceAtGenericPrices_isSizedOnPairs() {
		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService(WORKERS, "generic", RPS));

		assertThat(api.cpuCores()).isEqualTo(8.0);
		assertThat(api.siblingSpeed()).isEqualTo(0.6);
	}

	@Test
	void sizes_aServiceOnTheDefaultWithAnyFamily_getsTheCheapestTypeThatCoversItsOwnFamilysNeed() {
		// a Graviton type of 4 vCPU, whose vCPUs are whole cores, is cheaper than an x86
		// type of 8
		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService(WORKERS, "aws", RPS));

		assertThat(api.cpuCores()).isEqualTo(4.0);
		assertThat(api.why().get(Spec.CPU_CORES)).endsWith(", whose vCPUs are whole cores");
	}

	@Test
	void sizes_aServiceOnTheDefaultOnAWholeCoreFamily_needsWhatItNeedsOnWholeCores() {
		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService(WORKERS, "aws", RPS));

		assertThat(api.siblingSpeed()).isNull();
		assertThat(api.need().get(Need.CPU_CORES)).isEqualTo(2.4 / Sizing.TARGET_CPU);
	}

	@Test
	void sizes_aServiceOnTheDefaultWithFamiliesWhoseVcpusArePairs_getsATypeThatCoversItsNeedOnPairs() {
		PartSize api = SizingDesigns.size("api", onFamily(SizingDesigns.ofService(WORKERS, "aws", RPS), "m6i"));

		assertThat(api.sku()).isEqualTo("m6i.2xlarge");
		assertThat(api.why().get(Spec.CPU_CORES)).endsWith(", whose vCPUs it counts as hyperthread pairs");
	}

	@Test
	void sizes_aServiceOnTheDefaultOnAFamilyWhoseVcpusArePairs_needsWhatItNeedsOnPairs() {
		PartSize api = SizingDesigns.size("api", onFamily(SizingDesigns.ofService(WORKERS, "aws", RPS), "m6i"));

		assertThat(api.siblingSpeed()).isEqualTo(0.6);
	}

	@Test
	void sizes_aNamedTypeOfPairsTooSmallForItsNeedOnPairs_givesWayToTheCheapestThatCoversItsFamilysNeed() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"workersPerReplica\": 16, \"instanceType\": \"m6i.xlarge\"}", "aws", RPS));

		assertThat(api.sku()).isNotEqualTo("m6i.xlarge");
		assertThat(api.cpuCores()).isEqualTo(4.0);
	}

	@Test
	void applyTo_aNamedTypeOfPairsTooSmallForItsNeedOnPairs_recordsTheTypeItGets() {
		ObjectNode design = SizingDesigns.ofService("{\"workersPerReplica\": 16, \"instanceType\": \"m6i.xlarge\"}",
				"aws", RPS);

		SizedDocument sized = this.sizer.applyTo(design);

		assertThat(sized.document().at("/architecture/nodes/1/config/instanceType").asString()).isEqualTo("c6g.xlarge");
	}

	@Test
	void sizes_aNamedTypeOfPairsThatCoversItsNeedOnPairs_isKept() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"workersPerReplica\": 16, \"instanceType\": \"m6i.2xlarge\"}", "aws", RPS));

		assertThat(api.sku()).isEqualTo("m6i.2xlarge");
	}

	@Test
	void sizes_aDesignPinnedToWholeCoresAtGenericPrices_isSizedOnWholeCores() {
		ObjectNode design = SizingDesigns.ofService(WORKERS, "generic", RPS);
		((ObjectNode) design.get("architecture")).put("siblingSpeed", 1);

		PartSize api = SizingDesigns.size("api", design);

		assertThat(api.cpuCores()).isEqualTo(4.0);
		assertThat(api.kinds()).isNull();
	}

	@Test
	void sizes_aServiceOnKubernetes_isSizedOnWholeCores() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"workersPerReplica\": 16, \"platform\": \"kubernetes\"}", "generic", RPS));

		assertThat(api.kinds()).isNull();
	}

	// the design with its price list restricted to one family
	private static ObjectNode onFamily(ObjectNode design, String family) {
		((ObjectNode) design.get("pricing")).putArray("families").add(family);
		return design;
	}

}
