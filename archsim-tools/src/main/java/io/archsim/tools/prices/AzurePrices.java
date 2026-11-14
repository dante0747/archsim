package io.archsim.tools.prices;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * Azure's virtual machine prices from the Retail Prices API (public, no account needed):
 * pay-as-you-go Linux prices of the D v3 to v6 (general purpose, 4 GiB per vCPU), E v3 to
 * v6 (memory optimised, 8 GiB per vCPU) and F v2 (compute optimised, 2 GiB per vCPU)
 * series. The API gives prices, not shapes: the vCPUs come from the size's name
 * ({@code Standard_D4s_v5}: 4) and the memory from the series' published ratio, so other
 * series (older D, the low-memory {@code l} sizes, constrained vCPU sizes) are left out.
 */
final class AzurePrices {

	/** The Retail Prices API. */
	static final String API = "https://prices.azure.com/api/retail/prices";

	private static final Snapshot.Source SOURCE = new Snapshot.Source("Azure Retail Prices API", API);

	private static final String NOTE = "Memory per vCPU from each series' published ratio (D 4, E 8, F 2 GiB).";

	private static final Map<String, Integer> GIB_PER_VCPU = Map.of("D", 4, "E", 8, "F", 2);

	private static final Map<String, Set<String>> VERSIONS = Map.of("D", Set.of("3", "4", "5", "6"), "E",
			Set.of("3", "4", "5", "6"), "F", Set.of("2"));

	/**
	 * A size's name: its series, vCPUs, features and version; any decimal digit, as
	 * Python's.
	 */
	private static final Pattern SIZE = Pattern.compile(
			"Standard_(?<series>[DEF])(?<vcpus>\\d+)(?<features>[a-z]*)_v(?<version>\\d+)",
			Pattern.UNICODE_CHARACTER_CLASS);

	private static final Pattern DISCOUNTED = Pattern.compile("Spot|Low Priority");

	private AzurePrices() {
	}

	/**
	 * A region's snapshot of these items' sizes.
	 * @param region the region, such as {@code eastus}
	 * @param items the API's items for it
	 * @return the snapshot
	 */
	static Snapshot snapshot(String region, List<JsonNode> items) {
		return Snapshot.ofCompute(new Snapshot.Where("azure", region), SOURCE, NOTE, vms(items));
	}

	/**
	 * The hourly Linux pay-as-you-go price of each D, E and F series size, its cheapest
	 * meter's, in the order the sizes first came.
	 * @param items the API's items
	 * @return the sizes
	 */
	static List<Sku> vms(List<JsonNode> items) {
		SequencedMap<String, Sku> best = new LinkedHashMap<>();
		for (JsonNode item : items) {
			String name = JsonValues.strOrEmpty(item.path("armSkuName"));
			Matcher size = SIZE.matcher(name);
			if (!size.matches() || !isKnownSeries(size) || !isLinuxPayAsYouGo(item)) {
				continue;
			}
			double price = JsonValues.floatOrZero(item.path("retailPrice"));
			if (price <= 0) {
				continue;
			}
			int vcpu = Integer.parseInt(size.group("vcpus"));
			Shape shape = Shape.whole(vcpu, vcpu * GIB_PER_VCPU.getOrDefault(size.group("series"), 0));
			String family = size.group("series") + size.group("features") + "v" + size.group("version");
			Sku current = best.get(name);
			if (current == null || price < current.hourly()) {
				best.put(name, Sku.of(name, family, shape, price));
			}
		}
		return List.copyOf(best.values());
	}

	/**
	 * The API's virtual machine items for a region, page after page.
	 * @param downloads where the pages come from
	 * @param region the region
	 * @return the items, in the API's order
	 * @throws IOException when a page cannot be fetched
	 */
	static List<JsonNode> items(PriceDownloads downloads, String region) throws IOException {
		String filter = "serviceName eq 'Virtual Machines' and armRegionName eq '" + region
				+ "' and priceType eq 'Consumption'";
		SequencedMap<String, String> query = new LinkedHashMap<>();
		query.put("$filter", filter);
		List<JsonNode> items = new ArrayList<>();
		Optional<String> address = Optional.of(API + "?" + Queries.of(query));
		while (address.isPresent()) {
			byte[] body = downloads.bytes(URI.create(address.get()));
			JsonNode page = PythonJsonReader.decode(new String(body, StandardCharsets.UTF_8));
			page.path("Items").forEach(items::add);
			address = Optional.of(JsonValues.strOrEmpty(page.path("NextPageLink"))).filter((link) -> !link.isEmpty());
		}
		return items;
	}

	private static boolean isKnownSeries(Matcher size) {
		return VERSIONS.getOrDefault(size.group("series"), Set.of()).contains(size.group("version"))
				&& !size.group("features").contains("l");
	}

	private static boolean isLinuxPayAsYouGo(JsonNode item) {
		String meter = JsonValues.str(item.path("skuName")) + JsonValues.str(item.path("meterName"));
		return JsonValues.isText(item.path("type"), "Consumption")
				&& JsonValues.isText(item.path("unitOfMeasure"), "1 Hour")
				&& !JsonValues.str(item.path("productName")).contains("Windows") && !DISCOUNTED.matcher(meter).find();
	}

}
