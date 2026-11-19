package io.archsim.engine.sizing;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-sizing the parts of a design for its own peak. The cases the golden files do not
 * reach expect the sizes and words the Python engine 0.16.0 gives.
 */
class AutoSizerTests {

	private static final String AT_THE_PEAK = "50 req/s per replica at peak (100 over 2) × 10 ms of CPU each = "
			+ "0.5 cores busy; at a 60% target that is 0.83 → 1 vCPU";

	private static final String REPLICA_MEMORY = "256 MiB base + 54 requests held (4 working, 50 queued) × 2 MiB = "
			+ "364 MiB + 256 MiB for the operating system, +25% headroom";

	private final AutoSizer sizer = new AutoSizer(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void sizes_aServiceWithGenericRates_getsAStandardShapeForItsWorkAtTheTarget() {
		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService("{}", "generic", 100));

		assertThat(api.cpuCores()).isEqualTo(1.0);
		assertThat(api.memoryGib()).isEqualTo(2.0);
		assertThat(api.sku()).isNull();
		assertThat(api.why().get(Spec.CPU_CORES)).isEqualTo(AT_THE_PEAK);
	}

	@Test
	void sizes_aServiceNeedingMoreMemoryThanOneVcpuComesWith_getsAShapeThatCoversItsNeedOnPairs() {
		// a shape of 2 vCPU gives the part a pair of hyperthreads (ADR-0091),
		// which do the work 1 vCPU of whole cores does at the target with 2
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"memoryBaselineMb\": 7000}", "generic", 100));

		assertThat(api.cpuCores()).isEqualTo(2.0);
		assertThat(api.memoryGib()).isEqualTo(16.0);
		assertThat(api.why().get(Spec.CPU_CORES)).isEqualTo("50 req/s per replica at peak (100 over 2) × 10 ms of "
				+ "CPU each = 0.5 cores busy, which is what 0.83 vCPU can do as hyperthreads, each counting as 0.6 of "
				+ "a core (the sibling speed); at a 60% target that is 1.39 → 2 vCPU");
	}

	@Test
	void sizes_aServiceNeedingMoreMemoryThanOneVcpuComesWithOnWholeCores_getsTheVcpuOfTheShape() {
		ObjectNode design = SizingDesigns.ofService("{\"memoryBaselineMb\": 7000}", "generic", 100);
		((ObjectNode) design.get("architecture")).put("siblingSpeed", 1);

		PartSize api = SizingDesigns.size("api", design);

		assertThat(api.why().get(Spec.CPU_CORES))
			.isEqualTo(AT_THE_PEAK + "; rounded up to a standard shape, 2 vCPU and 16 GiB (8 GiB per vCPU)");
	}

	@Test
	void sizes_withAPriceSnapshot_snapsToTheCheapestInstanceType() {
		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService("{}", "aws", 100));

		assertThat(api.sku()).isNotNull();
		assertThat(api.why().get(Spec.CPU_CORES)).contains("; snapped to ");
	}

	@Test
	void sizes_aManagedService_isLeftToItsProvider() {
		PartSize db = SizingDesigns.size("db",
				SizingDesigns.design(SizingDesigns.service("{}") + ", {\"id\": \"db\", \"type\": \"database\", "
						+ "\"config\": {\"technology\": \"dynamodb\"}}", "generic", 100, "api-db"));

		assertThat(db.skipped()).isEqualTo("A managed service: the provider sizes it.");
		assertThat(db.cpuCores()).isNull();
	}

	@Test
	void sizes_aConfigThatIsNotValid_isNotSized() {
		Map<String, PartSize> sizes = this.sizer
			.sizes(DesignDocuments.parse(SizingDesigns.ofService("{\"replicas\": \"two\"}", "generic", 100)));

		assertThat(sizes).doesNotContainKey("api");
	}

	@Test
	void sizes_onABusyHostMachine_slowsTheWorkAndSaysSo() {
		ObjectNode design = SizingDesigns.onABusyHost(SizingDesigns.ofService("{}", "generic", 1000));

		PartSize api = SizingDesigns.size("api", design);

		assertThat(api.why().get(Spec.CPU_CORES)).isEqualTo("500 req/s per replica at peak (1,000 over 2) × 10 ms "
				+ "of CPU each, at 0.01 of full speed on the host machine = 693 cores busy; at a 60% target that is "
				+ "1,155 → 512 vCPU; capped at 4 because 4 workers cannot use more cores (add workers or replicas)");
		assertThat(api.need()).containsEntry(Need.BASE_BUSY_CORES, 692.7083333333334);
	}

	@Test
	void sizes_aPodBiggerThanItsClustersNodes_isCappedAtWhatANodeAllocates() {
		String nodes = "{\"id\": \"k8s\", \"type\": \"kubernetes_cluster\", \"config\": {\"nodeVcpu\": 2, "
				+ "\"nodeMemoryGib\": 4}}, " + SizingDesigns.service("{\"platform\": \"kubernetes\", \"replicas\": 1, "
						+ "\"workersPerReplica\": 64, \"queueCapacity\": 5000}");

		PartSize api = SizingDesigns.size("api", SizingDesigns.design(nodes, "generic", 1000));

		assertThat(api.cpuCores()).isEqualTo(1.8);
		assertThat(api.memoryGib()).isEqualTo(3.6);
		assertThat(api.why().get(Spec.CPU_CORES))
			.endsWith("; capped at 1.8, what one node of k8s can allocate to a pod");
		assertThat(api.why().get(Spec.MEMORY_GIB))
			.endsWith("; capped at 3.6 GiB, what one node of k8s can allocate to a pod");
	}

	@Test
	void sizes_theInstanceTypeSetOnThePart_isKeptWhileItCoversTheSize() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"instanceType\": \"c6g.2xlarge\"}", "aws", 100));

		assertThat(api.sku()).isEqualTo("c6g.2xlarge");
		assertThat(api.why().get(Spec.CPU_CORES))
			.isEqualTo(AT_THE_PEAK + "; snapped to c6g.2xlarge (8 vCPU, 16 GiB), the instance type set on the part, "
					+ "whose vCPUs are whole cores");
	}

	@Test
	void sizes_anInstanceTypeSetOnThePartThatIsTooSmall_givesWayToTheCheapest() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"instanceType\": \"c6g.medium\"}", "aws", 1000));

		assertThat(api.sku()).isEqualTo("c6g.xlarge");
		assertThat(api.why().get(Spec.MEMORY_GIB)).isEqualTo(REPLICA_MEMORY + "; snapped to c6g.xlarge (4 vCPU, "
				+ "8 GiB), the cheapest type in aws us-east-1 (2026-10-28) that covers it (c6g.medium, the type set on "
				+ "the part, is not in it or is too small), whose vCPUs are whole cores");
	}

	@Test
	void sizes_aSizeNoTypeOfItsKindCovers_isKeptAsComputed() {
		PartSize db = SizingDesigns.size("db",
				SizingDesigns.design(SizingDesigns.service("{}") + ", {\"id\": \"db\", \"type\": \"database\", "
						+ "\"config\": {\"maxConnections\": 5000}}", "aws", 100_000, "api-db"));

		assertThat(db.sku()).isNull();
		assertThat(db.cpuCores()).isEqualTo(512.0);
		assertThat(db.why().get(Spec.CPU_CORES))
			.endsWith("; no database type in aws us-east-1 (2026-10-28) has 512 vCPU and 64 GiB: kept as computed");
	}

	@Test
	void sizes_aFunctionWithItsMemorySet_getsTheCpuThatComesWithIt() {
		PartSize function = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"platform\": \"serverless\", \"memoryGib\": 0.5}", "generic", 100));

		assertThat(function.cpuCores()).isEqualTo(0.28942905596382135);
		assertThat(function.why().get(Spec.CPU_CORES)).isEqualTo("0.5 GiB of memory: a function gets 1 vCPU per 1,769 "
				+ "MB of memory (AWS Lambda), so 0.29 vCPU; below 1 its work runs slower");
	}

	@Test
	void sizes_aServiceWithACpuShareALoadCurveAndMemoryPerRate_explainsEach() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.design(SizingDesigns.service("{\"cpuShare\": 0.5, \"memoryPerRpsMb\": 0.5, "
						+ "\"serviceTimeByLoad\": [{\"rps\": 10, \"factor\": 1.0}, {\"rps\": 100, \"factor\": 0.5}]}"),
						"generic", 100));

		assertThat(api.why().get(Spec.CPU_CORES)).isEqualTo("50 req/s per replica at peak (100 over 2) × 10 ms × 50% "
				+ "CPU share × 0.65 at that rate (service time by load) = 0.16 cores busy; at a 60% target that is "
				+ "0.27 → 1 vCPU");
		assertThat(api.why().get(Spec.MEMORY_GIB))
			.startsWith("256 MiB base + 54 requests held (4 working, 50 queued) × "
					+ "2 MiB + 50 req/s × 0.5 MiB per req/s = 389 MiB");
	}

	@Test
	void sizes_aReplicaSetReadingAll_readsFromEveryReplica() {
		PartSize ring = SizingDesigns.size("ring", SizingDesigns.design(
				SizingDesigns.service("{}") + ", {\"id\": \"ring\", \"type\": \"database\", \"config\": "
						+ "{\"technology\": \"cassandra\", \"replicationFactor\": 3, \"consistency\": \"all\"}}",
				"generic", 100, "api-ring"));

		assertThat(ring.why().get(Spec.CPU_CORES)).startsWith("80 reads/s × 3 replicas × 7.13 ms + 20 writes/s × 3 "
				+ "replicas × 14.3 ms, 50% of it CPU, over 3 nodes = 0.43 cores busy");
	}

	@Test
	void sizes_aDatabaseScatteringSomeReads_sendsThemToEveryShard() {
		PartSize search = SizingDesigns
			.size("search",
					SizingDesigns.design(
							SizingDesigns.service("{}") + ", {\"id\": \"search\", \"type\": \"database\", "
									+ "\"config\": {\"shards\": 4, \"scatterReads\": 0.5}}",
							"generic", 100, "api-search"));

		assertThat(search.why().get(Spec.CPU_CORES)).startsWith("Each primary: 50 reads/s (50% of reads search all 4 "
				+ "shards) × 7.13 ms + 5 writes/s × 14.3 ms, 50% of it CPU = 0.21 cores busy");
	}

	@Test
	void sizes_aCpuSetOnThePart_isKeptAndTheMemoryRoundedToItsShape() {
		PartSize api = SizingDesigns.size("api", SizingDesigns.ofService("{\"cpuCores\": 2}", "generic", 100));

		assertThat(api.memoryGib()).isEqualTo(4.0);
		assertThat(api.why().get(Spec.MEMORY_GIB))
			.isEqualTo(REPLICA_MEMORY + "; rounded up to a standard shape, 2 vCPU and 4 GiB (2 GiB per vCPU)");
	}

	@Test
	void sizes_bothCpuAndMemorySetOnThePart_isNotRounded() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"cpuCores\": 2, \"memoryGib\": 3}", "generic", 100));

		assertThat(api.why().get(Spec.MEMORY_GIB)).isEqualTo(REPLICA_MEMORY);
	}

	@Test
	void sizes_aPodWithGenericRates_isNotRoundedToAMachineShape() {
		PartSize api = SizingDesigns.size("api",
				SizingDesigns.ofService("{\"platform\": \"kubernetes\"}", "generic", 100));

		assertThat(api.memoryGib()).isEqualTo(0.5);
	}

	@Test
	void applyTo_anEmptySpec_isFilledWithItsAutoSize() {
		SizedDocument sized = this.sizer.applyTo(SizingDesigns.ofService("{}", "generic", 100));

		assertThat(sized.document().at("/architecture/nodes/1/config/cpuCores").doubleValue()).isEqualTo(1.0);
	}

	@Test
	void applyTo_aSpecSetOnThePart_isKept() {
		SizedDocument sized = this.sizer.applyTo(SizingDesigns.ofService("{\"cpuCores\": 8}", "generic", 100));

		assertThat(sized.document().at("/architecture/nodes/1/config/cpuCores").doubleValue()).isEqualTo(8.0);
	}

	@Test
	void applyTo_theDesign_isLeftAsItWas() {
		ObjectNode design = SizingDesigns.ofService("{}", "generic", 100);

		this.sizer.applyTo(design);

		assertThat(design.at("/architecture/nodes/1/config").has("cpuCores")).isFalse();
	}

	@Test
	void applyTo_aNamedTypeTooSmallOnWholeCores_keepsTheNamedType() {
		// a design pinned to whole cores: the cost, which finds the named type too small
		// for the size, chooses again
		ObjectNode design = SizingDesigns.ofService("{\"instanceType\": \"c6g.medium\"}", "aws", 1000);
		((ObjectNode) design.get("architecture")).put("siblingSpeed", 1);

		SizedDocument sized = this.sizer.applyTo(design);

		assertThat(sized.document().at("/architecture/nodes/1/config/instanceType").asString()).isEqualTo("c6g.medium");
	}

	@Test
	void applyTo_autoSizingOff_fillsNothing() {
		ObjectNode design = SizingDesigns.ofService("{}", "generic", 100);
		((ObjectNode) design.get("architecture")).put("autoSize", false);

		SizedDocument sized = this.sizer.applyTo(design);

		assertThat(sized.sizes()).isEmpty();
		assertThat(sized.document().at("/architecture/nodes/1/config").has("cpuCores")).isFalse();
	}

}
