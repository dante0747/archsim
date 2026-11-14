package io.archsim.tools.prices;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * GCP's Compute Engine machine prices from the Cloud Billing Catalog API, which needs an
 * API key. Compute Engine bills N2, N2D, E2 and C3 machines per vCPU-hour and per
 * GiB-hour, so each machine type's price is its vCPUs times the core price plus its
 * memory times the RAM price, for the standard (4 GiB per vCPU), highmem (8) and highcpu
 * (1; C3: 2) shapes in each family's predefined sizes.
 */
final class GcpPrices {

	/** Compute Engine's SKUs in the Cloud Billing Catalog API. */
	static final String API = "https://cloudbilling.googleapis.com/v1/services/6F81-5844-456A/skus";

	private static final Snapshot.Source SOURCE = new Snapshot.Source("Cloud Billing Catalog API (Compute Engine)",
			API);

	private static final String NOTE = "Machine prices are vCPU x core price + GiB x RAM price per family.";

	private static final List<String> FAMILIES = List.of("N2", "N2D", "E2", "C3");

	private static final List<String> SHAPES = List.of("standard", "highmem", "highcpu");

	private static final List<Integer> N2_SIZES = List.of(2, 4, 8, 16, 32, 48, 64, 80, 96);

	private static final List<Integer> C3_SIZES = List.of(4, 8, 22, 44, 88, 176);

	private static final Map<String, List<Integer>> SIZES = Map.ofEntries(Map.entry("n2-standard", N2_SIZES),
			Map.entry("n2-highmem", N2_SIZES), Map.entry("n2-highcpu", N2_SIZES), Map.entry("n2d-standard", N2_SIZES),
			Map.entry("n2d-highmem", N2_SIZES), Map.entry("n2d-highcpu", N2_SIZES),
			Map.entry("e2-standard", List.of(2, 4, 8, 16, 32)), Map.entry("e2-highmem", List.of(2, 4, 8, 16)),
			Map.entry("e2-highcpu", List.of(2, 4, 8, 16, 32)), Map.entry("c3-standard", C3_SIZES),
			Map.entry("c3-highmem", C3_SIZES), Map.entry("c3-highcpu", C3_SIZES));

	private static final int STANDARD_GIB_PER_VCPU = 4;

	private static final int HIGHMEM_GIB_PER_VCPU = 8;

	private static final int C3_HIGHCPU_GIB_PER_VCPU = 2;

	private static final Pattern LINE = Pattern.compile("^(N2D|N2|E2|C3) Instance (Core|Ram) running in ");

	private static final double NANOS_PER_UNIT = 1e9;

	private GcpPrices() {
	}

	/**
	 * A region's snapshot of the machine types these SKUs price.
	 * @param region the region, such as {@code us-central1}
	 * @param skus the catalog's SKUs
	 * @return the snapshot
	 */
	static Snapshot snapshot(String region, List<JsonNode> skus) {
		return Snapshot.ofCompute(new Snapshot.Where("gcp", region), SOURCE, NOTE, machines(unitPrices(skus, region)));
	}

	/**
	 * Each family's on-demand price per vCPU-hour and per GiB-hour in a region: the first
	 * tier's, of the SKUs whose description names a family's cores or RAM.
	 * @param skus the catalog's SKUs
	 * @param region the region
	 * @return the prices by family and resource, the last SKU's when several name one
	 */
	static Map<Unit, Double> unitPrices(List<JsonNode> skus, String region) {
		Map<Unit, Double> prices = new LinkedHashMap<>();
		for (JsonNode sku : skus) {
			Matcher line = LINE.matcher(JsonValues.strOrEmpty(sku.path("description")));
			boolean onDemand = JsonValues.isText(sku.path("category").path("usageType"), "OnDemand");
			if (!line.find() || !onDemand || !isIn(region, sku.path("serviceRegions"))) {
				continue;
			}
			firstTierPrice(sku).ifPresent((price) -> prices.put(new Unit(line.group(1), line.group(2)), price));
		}
		return prices;
	}

	/**
	 * The machine types of the families with both prices: each predefined size of each
	 * shape, priced by its cores and memory.
	 * @param prices the prices by family and resource
	 * @return the machine types, family by family and shape by shape
	 */
	static List<Sku> machines(Map<Unit, Double> prices) {
		List<Sku> machines = new ArrayList<>();
		for (String family : FAMILIES) {
			Double core = prices.get(new Unit(family, "Core"));
			Double ram = prices.get(new Unit(family, "Ram"));
			if (core == null || ram == null) {
				continue;
			}
			for (String shape : SHAPES) {
				machines.addAll(sizes(family, shape, core, ram));
			}
		}
		return machines;
	}

	/**
	 * The catalog's Compute Engine SKUs, page after page.
	 * @param downloads where the pages come from
	 * @param key the API key, which only the request carries
	 * @return the SKUs, in the catalog's order
	 * @throws IOException when a page cannot be fetched
	 */
	static List<JsonNode> skus(PriceDownloads downloads, String key) throws IOException {
		List<JsonNode> skus = new ArrayList<>();
		String token = "";
		do {
			SequencedMap<String, String> query = new LinkedHashMap<>();
			query.put("key", key);
			query.put("currencyCode", "USD");
			query.put("pageSize", "5000");
			if (!token.isEmpty()) {
				query.put("pageToken", token);
			}
			byte[] body = downloads.bytes(URI.create(API + "?" + Queries.of(query)));
			JsonNode page = PythonJsonReader.decode(new String(body, StandardCharsets.UTF_8));
			page.path("skus").forEach(skus::add);
			token = JsonValues.strOrEmpty(page.path("nextPageToken"));
		}
		while (!token.isEmpty());
		return skus;
	}

	// each predefined size of a family's shape: its vCPUs, its memory at
	// the shape's GiB per vCPU, its price at the core and RAM prices
	private static List<Sku> sizes(String family, String shape, double core, double ram) {
		String name = family.toLowerCase(Locale.ROOT) + "-" + shape;
		int gibPerVcpu = gibPerVcpu(family, shape);
		List<Sku> sizes = new ArrayList<>();
		for (int vcpu : SIZES.getOrDefault(name, List.of())) {
			int memory = vcpu * gibPerVcpu;
			sizes.add(Sku.of(name + "-" + vcpu, name, Shape.whole(vcpu, memory), vcpu * core + memory * ram));
		}
		return sizes;
	}

	private static int gibPerVcpu(String family, String shape) {
		return switch (shape) {
			case "standard" -> STANDARD_GIB_PER_VCPU;
			case "highmem" -> HIGHMEM_GIB_PER_VCPU;
			default -> "C3".equals(family) ? C3_HIGHCPU_GIB_PER_VCPU : 1;
		};
	}

	private static boolean isIn(String region, JsonNode regions) {
		for (JsonNode listed : regions) {
			if (JsonValues.isText(listed, region)) {
				return true;
			}
		}
		return false;
	}

	// the price of the tier that starts at zero: units and nanos of a dollar
	private static Optional<Double> firstTierPrice(JsonNode sku) {
		for (JsonNode info : sku.path("pricingInfo")) {
			for (JsonNode rate : info.path("pricingExpression").path("tieredRates")) {
				if (JsonValues.floatOrZero(rate.path("startUsageAmount")) == 0) {
					JsonNode money = rate.path("unitPrice");
					double units = JsonValues.intOrZero(money.path("units")).doubleValue();
					return Optional
						.of(units + JsonValues.intOrZero(money.path("nanos")).doubleValue() / NANOS_PER_UNIT);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * What a SKU prices: a family's cores or RAM.
	 *
	 * @param family the family, such as {@code N2}
	 * @param resource {@code Core} or {@code Ram}
	 */
	record Unit(String family, String resource) {

	}

}
