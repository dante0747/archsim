package io.archsim.tools.prices;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class GcpPricesTests {

	/**
	 * The Cloud Billing Catalog's first page of Compute Engine's SKUs, for the key
	 * {@code k}.
	 */
	static final String FIRST_PAGE = "https://cloudbilling.googleapis.com/v1/services/6F81-5844-456A/skus"
			+ "?key=k&currencyCode=USD&pageSize=5000";

	/** The core and RAM prices the recorded us-central1 snapshot was written from. */
	static final List<ObjectNode> SAMPLE = List.of(item("N2 Instance Core running in Americas", 31_611_000),
			item("N2 Instance Ram running in Americas", 4_237_000),
			item("C3 Instance Core running in Americas", 34_806_000),
			item("C3 Instance Ram running in Americas", 4_664_000));

	@Test
	void unitPrices_skus_keepTheRegionsOnDemandCoreAndRamPrices() {
		List<JsonNode> skus = List.of(item("N2 Instance Core running in Americas", 30_000_000),
				item("N2 Instance Ram running in Americas", 4_000_000),
				item("N2 Instance Core running in Europe", 99_000_000).set("serviceRegions",
						JsonNodeFactory.instance.arrayNode().add("europe-west1")));

		Map<GcpPrices.Unit, Double> prices = GcpPrices.unitPrices(skus, "us-central1");

		assertThat(prices).containsOnly(entry(new GcpPrices.Unit("N2", "Core"), 0.03),
				entry(new GcpPrices.Unit("N2", "Ram"), 0.004));
	}

	@Test
	void machines_unitPrices_priceEachSizeByItsCoresAndMemory() {
		Map<GcpPrices.Unit, Double> prices = Map.of(new GcpPrices.Unit("N2", "Core"), 0.03,
				new GcpPrices.Unit("N2", "Ram"), 0.004);

		List<Sku> machines = GcpPrices.machines(prices);

		assertThat(machines).filteredOn((machine) -> machine.sku().equals("n2-standard-4"))
			.singleElement()
			.satisfies((machine) -> assertThat(machine.shape()).isEqualTo(Shape.whole(4, 16)))
			.satisfies((machine) -> assertThat(machine.hourly()).isEqualTo(0.184));
		assertThat(machines).extracting(Sku::family).doesNotContain("e2-standard");
	}

	@Test
	void snapshot_aRegionsSkus_isWhatScriptsPricesWrote(@TempDir Path folder) throws IOException {
		Path out = folder.resolve("out.json");

		new SnapshotWriter(folder, SnapshotWriterTests.NOVEMBER_14, new PrintWriter(new StringWriter()))
			.write(GcpPrices.snapshot("us-central1", List.copyOf(SAMPLE)), Optional.of(out));

		assertThat(Files.readString(out, StandardCharsets.UTF_8))
			.isEqualTo(Recorded.text("gcp-us-central1-2026-11-14.json"));
	}

	@Test
	void skus_severalPages_followsEachPageToken() throws IOException {
		String second = FIRST_PAGE + "&pageToken=p%2F2%2B%3D";
		FakeDownloads downloads = new FakeDownloads(
				Map.of(FIRST_PAGE, page(SAMPLE.subList(0, 2), "p/2+="), second, page(SAMPLE.subList(2, 4), "")));

		List<JsonNode> skus = GcpPrices.skus(downloads, "k");

		assertThat(skus).containsExactlyElementsOf(SAMPLE);
		assertThat(downloads.asked()).containsExactly(FIRST_PAGE, second);
	}

	/**
	 * A page of the catalog's SKUs, and the token of the next page; empty on the last.
	 */
	static String page(List<ObjectNode> skus, String next) {
		ObjectNode page = JsonNodeFactory.instance.objectNode();
		page.putArray("skus").addAll(skus);
		page.put("nextPageToken", next);
		return page.toString();
	}

	// a SKU of us-central1 at a price per unit in nanos of a dollar
	private static ObjectNode item(String description, int nanos) {
		ObjectNode item = JsonNodeFactory.instance.objectNode().put("description", description);
		item.putObject("category").put("usageType", "OnDemand");
		item.putArray("serviceRegions").add("us-central1");
		ObjectNode rate = item.putArray("pricingInfo")
			.addObject()
			.putObject("pricingExpression")
			.putArray("tieredRates")
			.addObject()
			.put("startUsageAmount", 0);
		rate.putObject("unitPrice").put("units", "0").put("nanos", nanos);
		return item;
	}

}
