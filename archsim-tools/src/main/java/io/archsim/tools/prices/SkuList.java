package io.archsim.tools.prices;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.archsim.model.text.PythonText;

/**
 * A snapshot's list of types, made from the types a price list gave: one entry per type,
 * shape and price, in the snapshot's order.
 */
final class SkuList {

	/**
	 * The order a snapshot lists its types in: by family, vCPUs, memory, name, engines.
	 */
	static final Comparator<Sku> ORDER = Comparator.comparing(Sku::family, CodePoints.ORDER)
		.thenComparingDouble((type) -> type.shape().vcpu().doubleValue())
		.thenComparingDouble((type) -> type.shape().memoryGib().doubleValue())
		.thenComparing(Sku::sku, CodePoints.ORDER)
		.thenComparing(Sku::engines, CodePoints.LISTS);

	private SkuList() {
	}

	/**
	 * The types deduplicated ({@link #dedupe}), in the snapshot's order.
	 * @param types the types, in the order the price list gave them
	 * @param list the list they are for, such as {@code databases}, for a message
	 * @return the list
	 * @throws PriceListException when a type and engine has two prices
	 */
	static List<Sku> inOrder(List<Sku> types, String list) {
		return dedupe(types, list).stream().sorted(ORDER).toList();
	}

	/**
	 * One entry per type, shape and price: entries that differ only in their engines are
	 * merged, their engines sorted (an ElastiCache type costs the same for Redis and
	 * Memcached). The same type and engine at two prices means a filter let a row through
	 * that it should not (an Outposts or Local Zone price, a Capacity Block), and is
	 * refused, naming the type, rather than leaving the cheaper price to be picked by
	 * accident.
	 * @param types the types, in the order the price list gave them
	 * @param list the list they are for, such as {@code databases}, for a message
	 * @return the types, merged, in the order of their first entries
	 * @throws PriceListException when a type and engine has two prices
	 */
	static List<Sku> dedupe(List<Sku> types, String list) {
		Map<List<String>, Double> prices = new HashMap<>();
		Map<Key, Sku> merged = new LinkedHashMap<>();
		for (Sku type : types) {
			requireOnePrice(type, prices, list);
			Sku sorted = type.withEngines(type.engines().stream().sorted(CodePoints.ORDER).toList());
			merged.merge(Key.of(type), sorted, SkuList::withBothEngines);
		}
		return List.copyOf(merged.values());
	}

	// the first price each type and engine had, or none for a compute type
	private static void requireOnePrice(Sku type, Map<List<String>, Double> prices, String list) {
		for (String engine : type.engines().isEmpty() ? List.of("") : type.engines()) {
			double first = prices.computeIfAbsent(List.of(type.sku(), engine), (key) -> type.hourly());
			if (first != type.hourly()) {
				String on = engine.isEmpty() ? "" : " (" + engine + ")";
				throw new PriceListException(
						list + ": " + type.sku() + on + " has two prices, " + PythonText.repr(first) + " and "
								+ PythonText.repr(type.hourly()) + "; the price list format may have changed");
			}
		}
	}

	private static Sku withBothEngines(Sku first, Sku other) {
		Set<String> engines = new TreeSet<>(CodePoints.ORDER);
		engines.addAll(first.engines());
		engines.addAll(other.engines());
		return first.withEngines(engines);
	}

	/**
	 * What makes two entries one: the type, its shape and its price.
	 *
	 * @param sku the type
	 * @param family its family
	 * @param vcpu its vCPUs, a whole number equal to the same float as in Python
	 * @param memoryGib its memory in GiB
	 * @param hourly its price per hour
	 */
	private record Key(String sku, String family, double vcpu, double memoryGib, double hourly) {

		static Key of(Sku type) {
			return new Key(type.sku(), type.family(), type.shape().vcpu().doubleValue(),
					type.shape().memoryGib().doubleValue(), type.hourly());
		}

	}

}
