package io.archsim.tools.prices;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * MSK's standard broker-hours, named as AWS names its broker types
 * ({@code kafka.m7g.large}). The price list names the compute family ({@code m7g.large})
 * with the unit {@code hours}; the shape is the row's own, else the EC2 type's of the
 * same name. Express brokers are another product (storage and throughput included) and
 * are left out, as are serverless, connectivity and replicator hours.
 */
final class MskPrices {

	private static final String KAFKA = "kafka.";

	private MskPrices() {
	}

	/**
	 * The standard brokers' hours.
	 * @param rows the offer file's rows
	 * @param shapes EC2's shapes by type, for a row without its own
	 * @return the broker types, in the file's order
	 */
	static List<Sku> streams(Stream<OfferRow> rows, Map<String, Shape> shapes) {
		List<Sku> streams = new ArrayList<>();
		rows.filter(MskPrices::isRegionalHour).forEach((row) -> broker(row, shapes, streams));
		return streams;
	}

	private static void broker(OfferRow row, Map<String, Shape> shapes, List<Sku> streams) {
		String name = name(row);
		String usage = row.text("usageType");
		boolean brokerUsage = usage.isEmpty() || usage.contains("Kafka.");
		if (!name.contains(".") || name.startsWith("express.") || !brokerUsage) {
			return;
		}
		Shape shape = ownShape(row).orElseGet(() -> shapes.get(name));
		double price = row.price();
		if (shape == null || price == 0) {
			return;
		}
		String type = KAFKA + name;
		streams.add(Sku.of(type, OfferText.part(name, 0), shape, price)
			.withEngines(List.of("kafka"))
			.withBurst(Burstables.isBurstable(type), Burstables.baselineCpu(type)));
	}

	// the row's own vCPUs and memory, when it gives both
	private static Optional<Shape> ownShape(OfferRow row) {
		String vcpu = row.text("vCPU");
		Double memory = OfferText.gib(row.text("Memory (GiB)"));
		if (!OfferText.isDigits(vcpu) || memory == null || memory == 0) {
			return Optional.empty();
		}
		return Optional.of(Shape.of(OfferText.vcpu(vcpu), memory));
	}

	// the compute family the row prices, without the prefix AWS's broker names have
	private static String name(OfferRow row) {
		String type = row.text("Instance Type");
		String name = type.isEmpty() ? row.text("Compute Family") : type;
		return name.startsWith(KAFKA) ? name.substring(KAFKA.length()) : name;
	}

	private static boolean isRegionalHour(OfferRow row) {
		String unit = row.text("Unit").toLowerCase(Locale.ROOT);
		return row.isOnDemand() && row.isRegional() && ("hrs".equals(unit) || "hours".equals(unit));
	}

}
