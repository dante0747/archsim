package io.archsim.model.pricing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The price snapshots: which ones there are, how a document's pricing finds one, and
 * their hashes.
 */
class PriceSnapshotsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final PriceSnapshots SHIPPED = PriceSnapshots.shipped();

	@Test
	void shipped_pythonEngineSnapshots_areTheSameFiles() throws IOException {
		Path engine = Repository.root().resolve("packages/engine/src/archsim_engine/pricing");
		try (Stream<Path> files = Files.list(engine)) {
			Stream<String> names = files.map((path) -> path.getFileName().toString());
			assertThat(names.filter((name) -> name.endsWith(".json")).sorted())
				.containsExactlyElementsOf(PriceSnapshots.SHIPPED);
		}
	}

	@Test
	void forPricing_genericPrices_needNoSnapshot() {
		assertThat(SHIPPED.forPricing(null)).isSameAs(PriceSnapshot.GENERIC);
		assertThat(SHIPPED.forPricing(JSON.readTree("{\"provider\": \"generic\"}"))).isSameAs(PriceSnapshot.GENERIC);
		assertThat(PriceSnapshot.GENERIC.hasShapes()).isFalse();
	}

	@Test
	void forPricing_unpinnedPrices_takeTheNewestSnapshot() {
		PriceSnapshot newest = SHIPPED.forPricing(JSON.readTree("{\"provider\": \"aws\", \"region\": \"us-east-1\"}"));
		assertThat(newest.label()).isEqualTo("aws us-east-1 (2026-10-28)");
		assertThat(newest.families()).contains("m7g", "t4g");
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
		var pinned = Objects
			.requireNonNull(SHIPPED.pinned(JSON.readTree("{\"provider\": \"aws\", \"region\": \"us-east-1\"}")));
		assertThat(pinned.get("snapshotDate").stringValue()).isEqualTo("2026-10-28");
		assertThat(pinned.get("snapshotSha256").stringValue()).startsWith("35599a05bdf9");
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
