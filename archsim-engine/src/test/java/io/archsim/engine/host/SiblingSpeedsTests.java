package io.archsim.engine.host;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the vCPUs of a service, database or cache count (schema 1.11, ADR-0091): the part's
 * own sibling speed, else the design's, else whole cores on a whole-core family, a
 * serverless platform or a pod, else hyperthread pairs at 0.6.
 */
class SiblingSpeedsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final SiblingSpeeds SPEEDS = new SiblingSpeeds(PriceSnapshots.shipped());

	private static final String AWS = "{\"provider\": \"aws\", \"region\": \"us-east-1\"}";

	private static final String GENERIC = "{\"provider\": \"generic\"}";

	@Test
	void speedsOf_aPartThatSetsItsSiblingSpeed_countsItsOwn() {
		assertThat(speedOf(service("\"siblingSpeed\": 0.5"), "", GENERIC))
			.hasValue(new SiblingSpeed(0.5, VcpuBasis.PART));
	}

	@Test
	void speedsOf_aPartThatSetsWholeCores_countsWholeCores() {
		assertThat(speedOf(service("\"siblingSpeed\": 1, \"instanceType\": \"m6i.xlarge\""), "", AWS))
			.hasValue(new SiblingSpeed(1.0, VcpuBasis.PART));
	}

	@Test
	void speedsOf_aDesignThatSetsASiblingSpeed_countsTheDesigns() {
		assertThat(speedOf(service(""), "\"siblingSpeed\": 0.7, ", GENERIC))
			.hasValue(new SiblingSpeed(0.7, VcpuBasis.DESIGN));
	}

	@Test
	void speedsOf_aDesignPinnedToWholeCores_countsWholeCoresOnAnyFamily() {
		assertThat(speedOf(service("\"instanceType\": \"m6i.xlarge\""), "\"siblingSpeed\": 1, ", AWS))
			.hasValue(new SiblingSpeed(1.0, VcpuBasis.DESIGN));
	}

	@Test
	void speedsOf_aPartThatSetsItsSiblingSpeedInADesignThatSetsOne_countsItsOwn() {
		assertThat(speedOf(service("\"siblingSpeed\": 0.5"), "\"siblingSpeed\": 1, ", GENERIC))
			.hasValue(new SiblingSpeed(0.5, VcpuBasis.PART));
	}

	@Test
	void speedsOf_aPartOnAGravitonType_countsWholeCores() {
		assertThat(speedOf(service("\"instanceType\": \"m7g.xlarge\""), "", AWS))
			.hasValue(new SiblingSpeed(1.0, VcpuBasis.WHOLE_CORE_FAMILY));
	}

	@Test
	void speedsOf_aPartOnAnIntelType_countsPairsAtTheDefaultSpeed() {
		assertThat(speedOf(service("\"instanceType\": \"m6i.xlarge\""), "", AWS))
			.hasValue(new SiblingSpeed(0.6, VcpuBasis.DEFAULT));
	}

	@Test
	void speedsOf_aPartWhoseNamedTypeIsTooSmall_countsAsTheTypeTheCostPrices() {
		// m6i.large has 2 vCPU; the cost prices the cheapest type of 4 vCPU and 16 GiB,
		// m7g.xlarge, whose vCPUs are whole cores
		assertThat(speedOf(service("\"instanceType\": \"m6i.large\""), "", AWS))
			.hasValue(new SiblingSpeed(1.0, VcpuBasis.WHOLE_CORE_FAMILY));
	}

	@Test
	void speedsOf_aPartWithoutANamedType_countsAsTheCheapestTypeThatCoversIt() {
		assertThat(speedOf(service(""), "", AWS)).hasValue(new SiblingSpeed(1.0, VcpuBasis.WHOLE_CORE_FAMILY));
	}

	@Test
	void speedsOf_aDatabaseOnAGravitonType_countsWholeCores() {
		assertThat(speedOf(part("database", "\"instanceType\": \"db.m6g.xlarge\""), "", AWS))
			.hasValue(new SiblingSpeed(1.0, VcpuBasis.WHOLE_CORE_FAMILY));
	}

	@Test
	void speedsOf_aCacheOnAnIntelType_countsPairsAtTheDefaultSpeed() {
		String cache = """
				{"id": "api", "type": "cache", "config": {"cpuCores": 4, "memoryGib": 8,
				 "instanceType": "cache.m5.xlarge"}}""";

		assertThat(speedOf(cache, "", AWS)).hasValue(new SiblingSpeed(0.6, VcpuBasis.DEFAULT));
	}

	@Test
	void speedsOf_aPartAtGenericPrices_countsPairsAtTheDefaultSpeed() {
		assertThat(speedOf(service(""), "", GENERIC)).hasValue(new SiblingSpeed(0.6, VcpuBasis.DEFAULT));
	}

	@Test
	void speedsOf_aServerlessService_countsWholeCores() {
		assertThat(speedOf(service("\"platform\": \"serverless\""), "", GENERIC))
			.hasValue(new SiblingSpeed(1.0, VcpuBasis.SERVERLESS));
	}

	@Test
	void speedsOf_aServiceOnKubernetes_countsWholeCores() {
		assertThat(speedOf(service("\"platform\": \"kubernetes\""), "", GENERIC))
			.hasValue(new SiblingSpeed(1.0, VcpuBasis.POD));
	}

	@Test
	void speedsOf_aServiceOnKubernetesThatSetsItsSiblingSpeed_countsItsOwn() {
		assertThat(speedOf(service("\"platform\": \"kubernetes\", \"siblingSpeed\": 0.6"), "", GENERIC))
			.hasValue(new SiblingSpeed(0.6, VcpuBasis.PART));
	}

	@Test
	void speedsOf_aDesignWithAHost_countsNoPartsOwnVcpus() {
		assertThat(speedOf(service(""), "\"host\": {\"physicalCores\": 8}, ", GENERIC)).isEmpty();
	}

	@Test
	void speedsOf_aPartOfOneVcpu_countsNoPair() {
		assertThat(speedOf(part("service", "\"cpuCores\": 1"), "", GENERIC)).isEmpty();
	}

	@Test
	void speedsOf_aPartWithoutAVcpuSize_countsNoPair() {
		assertThat(speedOf("{\"id\": \"api\", \"type\": \"service\", \"config\": {}}", "", GENERIC)).isEmpty();
	}

	@Test
	void speedsOf_aQueue_countsNoVcpus() {
		assertThat(speedOf(part("message_queue", ""), "", GENERIC)).isEmpty();
	}

	@Test
	void writeInto_aPartOnTheDefault_getsTheSpeedItsVcpusCountAt() {
		ObjectNode design = json(service(""), "", GENERIC);

		SPEEDS.writeInto(design);

		assertThat(design.at("/architecture/nodes/1/config/siblingSpeed").doubleValue()).isEqualTo(0.6);
	}

	@Test
	void writeInto_aPartThatSetsItsSpeed_keepsIt() {
		ObjectNode design = json(service("\"siblingSpeed\": 0.5"), "\"siblingSpeed\": 0.7, ", GENERIC);

		SPEEDS.writeInto(design);

		assertThat(design.at("/architecture/nodes/1/config/siblingSpeed").doubleValue()).isEqualTo(0.5);
	}

	@Test
	void writeInto_aDesignThatSetsASpeedBelowOne_writesItIntoItsParts() {
		ObjectNode design = json(service(""), "\"siblingSpeed\": 0.5, ", GENERIC);

		SPEEDS.writeInto(design);

		assertThat(design.at("/architecture/nodes/1/config/siblingSpeed").doubleValue()).isEqualTo(0.5);
	}

	@Test
	void writeInto_aDesignPinnedToWholeCores_leavesItsPartsAsTheyAre() {
		ObjectNode design = json(service(""), "\"siblingSpeed\": 1, ", GENERIC);

		SPEEDS.writeInto(design);

		assertThat(design.at("/architecture/nodes/1/config").has("siblingSpeed")).isFalse();
	}

	@Test
	void writtenIn_aRunsParts_areThoseWhoseSpeedTheResolutionWrote() {
		ObjectNode drawn = json(service("") + ", " + part("database", "\"siblingSpeed\": 0.5").replace("api", "db"), "",
				GENERIC);
		ObjectNode resolved = drawn.deepCopy();
		SPEEDS.writeInto(resolved);

		Set<String> written = SPEEDS.writtenIn(DesignDocuments.parse(drawn), DesignDocuments.parse(resolved));

		assertThat(written).containsExactly("api");
	}

	@Test
	void asRun_aSpeedTheDesignSets_isTheDesignsWhereTheResolutionWroteIt() {
		ObjectNode drawn = json(service(""), "\"siblingSpeed\": 0.5, ", GENERIC);
		ObjectNode resolved = drawn.deepCopy();
		SPEEDS.writeInto(resolved);

		Map<String, SiblingSpeed> speeds = SPEEDS.asRun(DesignDocuments.parse(drawn), DesignDocuments.parse(resolved));

		assertThat(speeds).containsEntry("api", new SiblingSpeed(0.5, VcpuBasis.DESIGN));
	}

	@Test
	void leaveOut_aWrittenSpeed_isLeftForAResolutionToWriteAgain() {
		ObjectNode design = json(service(""), "", GENERIC);
		SPEEDS.writeInto(design);

		SiblingSpeeds.leaveOut(design, Set.of("api"));

		assertThat(design.at("/architecture/nodes/1/config").has("siblingSpeed")).isFalse();
	}

	@Test
	void beforeFamily_aPartWithoutAVcpuSize_goesByTheDefault() {
		Optional<SiblingSpeed> speed = SiblingSpeeds.beforeFamily(design(service(""), "", GENERIC).architecture(),
				"service", LenientSettings.of(JSON.readTree("{}")));

		assertThat(speed).hasValue(new SiblingSpeed(0.6, VcpuBasis.DEFAULT));
	}

	private static Optional<SiblingSpeed> speedOf(String node, String architecture, String pricing) {
		return Optional.ofNullable(SPEEDS.speedsOf(design(node, architecture, pricing)).get("api"));
	}

	/** A service of 4 vCPU and 16 GiB, with more settings, each after a comma. */
	private static String service(String settings) {
		return part("service", "\"cpuCores\": 4" + (settings.isEmpty() ? "" : ", " + settings));
	}

	/** A part named api of a type, of 4 vCPU and 16 GiB unless its settings say. */
	private static String part(String type, String settings) {
		String size = settings.contains("cpuCores") ? "" : "\"cpuCores\": 4, ";
		return "{\"id\": \"api\", \"type\": \"%s\", \"config\": {%s\"memoryGib\": 16%s}}".formatted(type, size,
				settings.isEmpty() ? "" : ", " + settings);
	}

	private static ArchitectureDocument design(String nodes, String architecture, String pricing) {
		return DesignDocuments.parse(json(nodes, architecture, pricing));
	}

	/**
	 * Clients and the given parts.
	 * @param architecture more of the architecture's settings, each followed by a comma
	 */
	private static ObjectNode json(String nodes, String architecture, String pricing) {
		return (ObjectNode) JSON.readTree("""
				{"schemaVersion": "1.11", "kind": "archsim.architecture", "metadata": {"name": "vcpus"},
				 "architecture": {%s"nodes": [{"id": "clients", "type": "traffic_source"}, %s], "edges": []},
				 "pricing": %s}""".formatted(architecture, nodes, pricing));
	}

}
