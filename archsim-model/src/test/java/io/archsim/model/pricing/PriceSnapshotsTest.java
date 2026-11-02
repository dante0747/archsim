package io.archsim.model.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.archsim.parity.Repository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The price snapshots: which ones there are, how a document's pricing finds one, and their hashes. */
class PriceSnapshotsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PriceSnapshots SHIPPED = PriceSnapshots.shipped();

    @Test
    void the_shipped_snapshots_are_the_python_engines() throws IOException {
        Path engine = Repository.root().resolve("packages/engine/src/archsim_engine/pricing");
        try (Stream<Path> files = Files.list(engine)) {
            assertThat(files.map(p -> p.getFileName().toString())
                            .filter(n -> n.endsWith(".json"))
                            .sorted())
                    .containsExactlyElementsOf(PriceSnapshots.SHIPPED);
        }
    }

    @Test
    void generic_prices_need_no_snapshot() {
        assertThat(SHIPPED.forPricing(null)).isSameAs(PriceSnapshot.GENERIC);
        assertThat(SHIPPED.forPricing(JSON.readTree("{\"provider\": \"generic\"}")))
                .isSameAs(PriceSnapshot.GENERIC);
        assertThat(PriceSnapshot.GENERIC.hasShapes()).isFalse();
    }

    @Test
    void unpinned_prices_take_the_newest_snapshot() {
        PriceSnapshot newest = SHIPPED.forPricing(JSON.readTree("{\"provider\": \"aws\", \"region\": \"us-east-1\"}"));
        assertThat(newest.label()).isEqualTo("aws us-east-1 (2026-10-28)");
        assertThat(newest.families()).contains("m7g", "t4g");
    }

    @Test
    void a_pinned_snapshot_is_never_swapped_for_another() {
        assertThatThrownBy(() -> SHIPPED.load("aws", "us-east-1", "2020-01-01", null))
                .isInstanceOf(PricingException.class)
                .hasMessageStartingWith("no price snapshot for aws us-east-1 of 2020-01-01 (this machine has aws")
                .hasMessageEndingWith("a pinned snapshot is never swapped for another.");
    }

    @Test
    void pinning_writes_in_the_snapshot_found() {
        var pinned = Objects.requireNonNull(
                SHIPPED.pinned(JSON.readTree("{\"provider\": \"aws\", \"region\": \"us-east-1\"}")));
        assertThat(pinned.get("snapshotDate").stringValue()).isEqualTo("2026-10-28");
        assertThat(pinned.get("snapshotSha256").stringValue()).startsWith("35599a05bdf9");
    }

    @Test
    void the_content_hash_ignores_formatting_and_key_order() {
        var compact = JSON.readTree("{\"b\":1.5,\"a\":[1,\"é\"]}");
        var spaced = JSON.readTree("{ \"a\": [ 1, \"é\" ],\n  \"b\": 1.5 }");
        assertThat(PriceSnapshot.contentSha256(compact)).isEqualTo(PriceSnapshot.contentSha256(spaced));
    }

    @Test
    void a_file_that_is_not_a_snapshot_is_refused_with_its_first_error() {
        assertThatThrownBy(() -> PriceSnapshot.parse(JSON.readTree("{\"format\": \"archsim.prices/2\"}")))
                .isInstanceOf(PricingException.class)
                .hasMessage("not a valid price snapshot (format: Input should be 'archsim.prices/1')");
    }
}
