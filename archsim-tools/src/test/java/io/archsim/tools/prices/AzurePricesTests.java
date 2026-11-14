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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AzurePricesTests {

	/**
	 * The Retail Prices API's first page for eastus, as {@code azure.py} asked for it.
	 */
	static final String EASTUS = "https://prices.azure.com/api/retail/prices?%24filter=serviceName+eq+"
			+ "%27Virtual+Machines%27+and+armRegionName+eq+%27eastus%27+and+priceType+eq+%27Consumption%27";

	/** The sizes the recorded eastus snapshot was written from. */
	static final List<ObjectNode> SAMPLE = List.of(item("Standard_D4s_v5", 0.192), item("Standard_D2s_v5", 0.096),
			item("Standard_D2s_v5", 0.0961), item("Standard_E8s_v5", 0.504), item("Standard_E8as_v5", 0.452),
			item("Standard_F4s_v2", 0.169), item("Standard_D96ds_v6", 5.0347), item("Standard_D2s_v5", 0.0959999));

	@Test
	void vms_retailPrices_keepTheLinuxPayAsYouGoPricesOfTheKnownSeries() {
		ObjectNode windows = item("Standard_D4s_v5", 0.4).put("productName", "Virtual Machines Dsv5 Series Windows");
		ObjectNode spot = item("Standard_D4s_v5", 0.05).put("skuName", "D4s v5 Spot");
		// an older series, whose memory per vCPU differs, and a low-memory size
		List<ObjectNode> items = List.of(item("Standard_D4s_v5", 0.2), windows, spot, item("Standard_E8s_v5", 0.5),
				item("Standard_D4_v2", 0.3), item("Standard_D4pls_v5", 0.15));

		List<Sku> vms = AzurePrices.vms(List.copyOf(items));

		assertThat(vms).extracting(Sku::sku, (vm) -> vm.shape().vcpu(), (vm) -> vm.shape().memoryGib(), Sku::hourly)
			.containsExactly(tuple("Standard_D4s_v5", 4, 16, 0.2), tuple("Standard_E8s_v5", 8, 64, 0.5));
	}

	@Test
	void vms_oneSizeAtSeveralPrices_keepsTheCheapestInItsFirstPlace() {
		List<Sku> vms = AzurePrices.vms(
				List.of(item("Standard_D2s_v5", 0.096), item("Standard_D4s_v5", 0.192), item("Standard_D2s_v5", 0.09)));

		assertThat(vms).extracting(Sku::sku, Sku::hourly)
			.containsExactly(tuple("Standard_D2s_v5", 0.09), tuple("Standard_D4s_v5", 0.192));
	}

	@Test
	void snapshot_aRegionsSizes_isWhatScriptsPricesWrote(@TempDir Path folder) throws IOException {
		Path out = folder.resolve("out.json");

		new SnapshotWriter(folder, SnapshotWriterTests.NOVEMBER_14, new PrintWriter(new StringWriter()))
			.write(AzurePrices.snapshot("eastus", List.copyOf(SAMPLE)), Optional.of(out));

		assertThat(Files.readString(out, StandardCharsets.UTF_8))
			.isEqualTo(Recorded.text("azure-eastus-2026-11-14.json"));
	}

	@Test
	void items_severalPages_followsEachNextPageLink() throws IOException {
		String second = "https://prices.azure.com/api/retail/prices?$skip=1000";
		FakeDownloads downloads = new FakeDownloads(
				Map.of(EASTUS, page(SAMPLE.subList(0, 2), second), second, page(SAMPLE.subList(2, 3), null)));

		List<JsonNode> items = AzurePrices.items(downloads, "eastus");

		assertThat(items).containsExactlyElementsOf(SAMPLE.subList(0, 3));
		assertThat(downloads.asked()).containsExactly(EASTUS, second);
	}

	/** A page of the Retail Prices API, and the link to the next when there is one. */
	static String page(List<ObjectNode> items, @Nullable String next) {
		ObjectNode page = JsonNodeFactory.instance.objectNode();
		page.putArray("Items").addAll(items);
		page.put("NextPageLink", next);
		return page.toString();
	}

	private static ObjectNode item(String name, double price) {
		return JsonNodeFactory.instance.objectNode()
			.put("armSkuName", name)
			.put("retailPrice", price)
			.put("type", "Consumption")
			.put("unitOfMeasure", "1 Hour")
			.put("productName", "Virtual Machines Dsv5 Series")
			.put("skuName", "D4s v5")
			.put("meterName", "D4s v5");
	}

}
