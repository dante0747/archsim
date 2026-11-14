package io.archsim.tools.prices;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ElastiCache's node-hours from its offer file, for each engine, without the
 * extended-support surcharge billed on top of an old engine's node price.
 */
final class ElastiCachePrices {

	private static final Map<String, String> ENGINES = Map.of("Redis", "redis", "Valkey", "valkey", "Memcached",
			"memcached");

	private ElastiCachePrices() {
	}

	/**
	 * The node-hours of each engine.
	 * @param rows the offer file's rows
	 * @return the cache types, in the file's order
	 */
	static List<Sku> caches(Stream<OfferRow> rows) {
		List<Sku> caches = new ArrayList<>();
		rows.forEach((row) -> {
			String engine = ENGINES.get(row.text("Cache Engine"));
			if (engine != null && isNodeHour(row)) {
				node(row, engine, caches);
			}
		});
		return caches;
	}

	private static void node(OfferRow row, String engine, List<Sku> caches) {
		String type = row.text("Instance Type");
		String vcpu = row.text("vCPU");
		Double memory = OfferText.gib(row.text("Memory"));
		double price = row.price();
		if (type.isEmpty() || !OfferText.isDigits(vcpu) || price == 0) {
			return;
		}
		if (memory == null || memory == 0) {
			return;
		}
		caches.add(Sku.of(type, OfferText.part(type, 1), Shape.of(OfferText.vcpu(vcpu), memory), price)
			.withEngines(List.of(engine))
			.withBurst(Burstables.isBurstable(type), Burstables.baselineCpu(type)));
	}

	private static boolean isNodeHour(OfferRow row) {
		boolean hour = row.is("Product Family", "Cache Instance") && row.is("Unit", "Hrs")
				&& !row.text("usageType").contains("ExtendedSupport");
		return hour && row.isOnDemand() && row.isRegional();
	}

}
