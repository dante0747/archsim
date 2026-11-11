package io.archsim.model.pricing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The price snapshots: which ones there are, how a document's pricing finds one, and
 * their hashes.
 */
class PriceSnapshotsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final PriceSnapshots SHIPPED = PriceSnapshots.shipped();

	/** The snapshots ArchSim ships: the model's resources. */
	private static final Path RESOURCES = Repository.root()
		.resolve("archsim-model/src/main/resources/io/archsim/model/pricing/snapshots");

	/** A valid snapshot file of aws prices: its region, date and note. */
	private static final String SNAPSHOT = """
			{"format": "archsim.prices/1", "provider": "aws", "region": "%s", "snapshotDate": "%s",
			 "sources": [{"name": "test", "url": "https://example.com/prices"}], "note": "%s"}""";

	// the newest shipped snapshot of the default provider and region: what an unpinned
	// design prices with
	private static AvailableSnapshot newestOfUsEast1() {
		return SHIPPED.newest()
			.stream()
			.filter((snapshot) -> snapshot.provider().equals(PriceSnapshots.DEFAULT_PROVIDER)
					&& snapshot.region().equals(PriceSnapshots.DEFAULT_REGION))
			.findFirst()
			.orElseThrow();
	}

	private static List<String> jsonFiles(Path folder) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.map((path) -> path.getFileName().toString())
				.filter((name) -> name.endsWith(".json"))
				.sorted()
				.toList();
		}
	}

	private static Path write(Path folder, String name, String content) throws IOException {
		Files.createDirectories(folder);
		return Files.writeString(folder.resolve(name), content);
	}

	@Test
	void shippedNames_everySnapshotFileOfTheResources_isShippedInFileOrder() throws IOException {
		// a refreshed snapshot ships by adding its file: the build lists the folder
		assertThat(SnapshotFiles.shippedNames()).containsExactlyElementsOf(jsonFiles(RESOURCES));
	}

	@Test
	void shipped_pythonEngineSnapshots_areShippedAsTheyAre() throws IOException {
		// the frozen engine's copy, until it is deleted: in the jar, byte for byte
		Path engine = Repository.root().resolve("packages/engine/src/archsim_engine/pricing");

		assertThat(jsonFiles(engine)).isNotEmpty().allSatisfy((name) -> {
			assertThat(SnapshotFiles.shippedNames()).contains(name);
			try (InputStream shipped = SnapshotFiles.class.getResourceAsStream("snapshots/" + name);
					InputStream copy = Files.newInputStream(engine.resolve(name))) {
				assertThat(shipped).hasSameContentAs(copy);
			}
		});
	}

	@Test
	void newest_shippedSnapshots_listTheNewestOfEachRegionOnce() {
		List<AvailableSnapshot> newest = SHIPPED.newest();

		assertThat(newest).extracting(AvailableSnapshot::provider, AvailableSnapshot::region)
			.doesNotHaveDuplicates()
			.containsExactlyInAnyOrderElementsOf(SHIPPED.everyDate()
				.stream()
				.map((snapshot) -> tuple(snapshot.provider(), snapshot.region()))
				.distinct()
				.toList());
		assertThat(newest).allMatch(AvailableSnapshot::newest);
	}

	@Test
	void everyDate_shippedSnapshots_listEachRegionNewestFirst() {
		Map<List<String>, List<AvailableSnapshot>> byRegion = SHIPPED.everyDate()
			.stream()
			.collect(Collectors.groupingBy((snapshot) -> List.of(snapshot.provider(), snapshot.region())));

		assertThat(byRegion.values()).allSatisfy((snapshots) -> {
			assertThat(snapshots).extracting(AvailableSnapshot::date).isSortedAccordingTo(Comparator.reverseOrder());
			assertThat(snapshots.getFirst().newest()).isTrue();
			assertThat(snapshots.subList(1, snapshots.size())).noneMatch(AvailableSnapshot::newest);
		});
	}

	@Test
	void load_sameDateInExtraFolderAndShipped_takesTheExtraFolders(@TempDir Path folder) throws IOException {
		write(folder, "aws-us-east-1-2026-10-28.json", SNAPSHOT.formatted("us-east-1", "2026-10-28", "extra folder"));

		PriceSnapshot loaded = PriceSnapshots.with(List.of(folder)).load("aws", "us-east-1", "2026-10-28", null);

		assertThat(loaded.note()).isEqualTo("extra folder");
	}

	@Test
	void everyDate_folderWithInvalidFiles_skipsThem(@TempDir Path folder) throws IOException {
		write(folder, "next-format.json", "{\"format\": \"archsim.prices/2\"}");
		write(folder, "not-json.json", "prices");

		List<AvailableSnapshot> everyDate = PriceSnapshots.with(List.of(folder)).everyDate();

		assertThat(everyDate).isEqualTo(SHIPPED.everyDate());
	}

	@Test
	void everyDate_sameContentInTwoFolders_listsItOnceFromTheFirst(@TempDir Path folder) throws IOException {
		Path first = folder.resolve("first");
		Path second = folder.resolve("second");
		String content = SNAPSHOT.formatted("eu-west-1", "2026-01-01", "copied");
		write(first, "copy-a.json", content);
		write(second, "copy-b.json", content);

		List<AvailableSnapshot> everyDate = PriceSnapshots.with(List.of(first, second)).everyDate();

		assertThat(everyDate).filteredOn((snapshot) -> snapshot.region().equals("eu-west-1"))
			.extracting(AvailableSnapshot::file)
			.containsExactly("copy-a.json");
	}

	@Test
	void forPricing_genericPrices_needNoSnapshot() {
		assertThat(SHIPPED.forPricing(null)).isSameAs(PriceSnapshot.GENERIC);
		assertThat(SHIPPED.forPricing(JSON.readTree("{\"provider\": \"generic\"}"))).isSameAs(PriceSnapshot.GENERIC);
		assertThat(PriceSnapshot.GENERIC.hasShapes()).isFalse();
	}

	@Test
	void forPricing_unpinnedPrices_takeTheNewestSnapshot() {
		PriceSnapshot found = SHIPPED.forPricing(JSON.readTree("{\"provider\": \"aws\", \"region\": \"us-east-1\"}"));

		assertThat(found.label()).isEqualTo("aws us-east-1 (" + newestOfUsEast1().date() + ")");
		assertThat(found.families()).contains("m7g", "t4g");
	}

	@Test
	void load_missingPinnedSnapshot_isNeverSwappedForAnother() {
		assertThatThrownBy(() -> SHIPPED.load("aws", "us-east-1", "2020-01-01", null))
			.isInstanceOf(PricingException.class)
			.hasMessageStartingWith("no price snapshot for aws us-east-1 of 2020-01-01 (this machine has aws")
			.hasMessageEndingWith("a pinned snapshot is never swapped for another.");
	}

	@Test
	void pinned_unpinnedPrices_writeInTheSnapshotFound() {
		AvailableSnapshot newest = newestOfUsEast1();

		var pinned = Objects
			.requireNonNull(SHIPPED.pinned(JSON.readTree("{\"provider\": \"aws\", \"region\": \"us-east-1\"}")));

		assertThat(pinned.get("snapshotDate").stringValue()).isEqualTo(newest.date());
		assertThat(pinned.get("snapshotSha256").stringValue()).isEqualTo(newest.sha256());
	}

	@Test
	void contentSha256_otherFormattingAndKeyOrder_isTheSame() {
		var compact = JSON.readTree("{\"b\":1.5,\"a\":[1,\"é\"]}");
		var spaced = JSON.readTree("{ \"a\": [ 1, \"é\" ],\n  \"b\": 1.5 }");
		assertThat(PriceSnapshot.contentSha256(compact)).isEqualTo(PriceSnapshot.contentSha256(spaced));
	}

	@Test
	void parse_notASnapshot_isRefusedWithItsFirstError() {
		assertThatThrownBy(() -> PriceSnapshot.parse(JSON.readTree("{\"format\": \"archsim.prices/2\"}")))
			.isInstanceOf(PricingException.class)
			.hasMessage("not a valid price snapshot (format: Input should be 'archsim.prices/1')");
	}

}
