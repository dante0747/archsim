package io.archsim.model.pricing;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The instance families whose vCPUs are whole cores, held against the price snapshots
 * ArchSim ships.
 */
class WholeCoreFamiliesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String AWS = "aws";

	// every family listed for a provider, group by group
	private static List<String> listed(String provider) {
		return WholeCoreFamilies.byProvider()
			.getOrDefault(provider, List.of())
			.stream()
			.flatMap((group) -> group.families().stream())
			.toList();
	}

	// every family the shipped snapshots of a provider price, whatever their date
	private static Set<String> shippedFamilies(String provider) {
		return SnapshotFiles.shipped()
			.stream()
			.map(FoundSnapshot::snapshot)
			.filter((snapshot) -> snapshot.provider().equals(provider))
			.flatMap((snapshot) -> snapshot.families().stream())
			.collect(Collectors.toSet());
	}

	// the newest shipped snapshot of each provider and region: what an unpinned design
	// prices with
	private static List<PriceSnapshot> newestShipped() {
		PriceSnapshots shipped = PriceSnapshots.shipped();
		return shipped.newest()
			.stream()
			.map((newest) -> shipped.load(newest.provider(), newest.region(), newest.date(), newest.sha256()))
			.toList();
	}

	// the families of a snapshot that sell an instance type with one vCPU
	private static Set<String> sellingOneVcpu(PriceSnapshot snapshot) {
		return snapshot.skus()
			.values()
			.stream()
			.flatMap(List::stream)
			.filter((sku) -> sku.vcpu() == 1)
			.map(Sku::family)
			.collect(Collectors.toSet());
	}

	@Test
	void has_aGravitonFamily_isTrue() {
		assertThat(WholeCoreFamilies.has(AWS, "m7g")).isTrue();
	}

	@Test
	void has_aFamilyOfHyperthreads_isFalse() {
		assertThat(WholeCoreFamilies.has(AWS, "m5")).isFalse();
	}

	@Test
	void has_aListedFamilyUnderAnotherProvider_isFalse() {
		assertThat(WholeCoreFamilies.has("gcp", "m7g")).isFalse();
	}

	@Test
	void has_everyListedFamily_isTrue() {
		// those that sell no one-vCPU type too: being listed is what counts
		assertThat(WholeCoreFamilies.byProvider()).isNotEmpty()
			.allSatisfy((provider, _) -> assertThat(listed(provider))
				.allMatch((family) -> WholeCoreFamilies.has(provider, family)));
	}

	@Test
	void byProvider_everyListedFamily_isPricedByAShippedSnapshotOfItsProvider() {
		assertThat(WholeCoreFamilies.byProvider()).isNotEmpty()
			.allSatisfy((provider, _) -> assertThat(shippedFamilies(provider)).containsAll(listed(provider)));
	}

	@Test
	void byProvider_everyGroup_hasFamiliesAReasonAndAnHttpsSource() {
		List<WholeCoreGroup> groups = WholeCoreFamilies.byProvider().values().stream().flatMap(List::stream).toList();

		assertThat(groups).isNotEmpty().allSatisfy((group) -> {
			assertThat(group.families()).isNotEmpty();
			assertThat(group.reason()).isNotBlank();
			assertThat(group.source()).startsWith("https://");
		});
	}

	@Test
	void byProvider_aProvidersFamilies_areEachListedOnce() {
		assertThat(WholeCoreFamilies.byProvider()).isNotEmpty()
			.allSatisfy((provider, _) -> assertThat(listed(provider)).doesNotHaveDuplicates());
	}

	@Test
	void has_everyFamilySellingAOneVcpuTypeInTheNewestSnapshots_isTrue() {
		// a one-vCPU type is not a pair of hyperthreads, and every family that sells one
		// today is a whole-core family throughout. A refreshed snapshot with a new such
		// family fails here until its page is read: listed if it says the family's vCPUs
		// are cores; if it does not, this guard is narrowed, the list is not padded
		assertThat(newestShipped()).isNotEmpty()
			.allSatisfy((snapshot) -> assertThat(sellingOneVcpu(snapshot))
				.allMatch((family) -> WholeCoreFamilies.has(snapshot.provider(), family)));
	}

	@Test
	void byProvider_awsFamiliesSellingNoOneVcpuType_areTheGravitonOnesThatStartLarger() {
		// the converse of the guard above does not hold: a whole-core family need not
		// sell a one-vCPU type, and these are listed by their sources alone
		Set<String> sellingOneVcpu = newestShipped().stream()
			.filter((snapshot) -> snapshot.provider().equals(AWS))
			.flatMap((snapshot) -> sellingOneVcpu(snapshot).stream())
			.collect(Collectors.toSet());

		List<String> startingLarger = listed(AWS).stream()
			.filter((family) -> !sellingOneVcpu.contains(family))
			.toList();

		assertThat(startingLarger).containsExactlyInAnyOrder("t4g", "hpc7g", "i4g", "i8g", "i8ge", "im4gn", "x2g");
	}

	@Test
	void has_u6tb1WithOneThreadPerCoreOnIts56xlargeAndTwoOnItsOtherTypes_isFalse() {
		assertThat(shippedFamilies(AWS)).contains("u-6tb1");
		assertThat(WholeCoreFamilies.has(AWS, "u-6tb1")).isFalse();
	}

	@Test
	void parse_aGroupWhoseSourceIsNotAnHttpsPage_isRefusedSayingWhereItIs() {
		JsonNode file = JSON.readTree("""
				{"aws": [{"families": ["m7g"], "reason": "Graviton", "source": "https://example.com/m7g"},
				         {"families": ["t2"], "reason": "one thread per core", "source": "a forum post"}]}""");

		assertThatIllegalStateException().isThrownBy(() -> WholeCoreFamilies.parse(file))
			.withMessage("whole-core-families.json is not valid "
					+ "(aws.1.source: String should match pattern '^https://\\S+$')");
	}

	@Test
	void parse_aProviderWhoseGroupsAreNotInAnArray_isRefusedNamingTheProvider() {
		JsonNode file = JSON.readTree("{\"aws\": {\"families\": [\"m7g\"]}}");

		assertThatIllegalStateException().isThrownBy(() -> WholeCoreFamilies.parse(file))
			.withMessage("whole-core-families.json is not valid (aws: a provider lists its groups in an array)");
	}

	@Test
	void parse_aGroupThatIsNotAnObject_isRefusedNamingTheGroupItself() {
		JsonNode file = JSON.readTree("{\"aws\": [\"m7g\"]}");

		assertThatIllegalStateException().isThrownBy(() -> WholeCoreFamilies.parse(file))
			.withMessageStartingWith("whole-core-families.json is not valid (aws.0: ");
	}

}
