package io.archsim.tools.prices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Stream;

/**
 * RDS's prices from its offer file: Single-AZ instance-hours for each engine, the surplus
 * CPU credits of the db.t types (RDS runs them in unlimited mode), and the gp3 storage of
 * a Single-AZ instance.
 */
final class RdsPrices {

	private static final Map<String, String> ENGINES = Map.of("PostgreSQL", "postgresql", "MySQL", "mysql", "MariaDB",
			"mariadb");

	private RdsPrices() {
	}

	/**
	 * Single-AZ instance-hours for each engine, a db.t type with its family's credit
	 * price.
	 * @param rows the offer file's rows
	 * @return the database types, in the file's order
	 */
	static List<Sku> databases(Stream<OfferRow> rows) {
		List<Sku> databases = new ArrayList<>();
		Map<List<String>, Double> credits = new HashMap<>();
		rows.forEach((row) -> {
			String engine = ENGINES.get(row.text("Database Engine"));
			if (engine != null && isRegionalCredits(row)) {
				credits.put(List.of(Burstables.creditFamily(row), engine), row.price());
			}
			else if (engine != null && isSingleAzHour(row)) {
				instance(row, engine).ifPresent(databases::add);
			}
		});
		return databases.stream()
			.map((type) -> Burstables.withCredit(type, credits.get(List.of(type.family(), type.engines().getFirst()))))
			.toList();
	}

	/**
	 * The gp3 storage of a Single-AZ instance per GiB-month, which the engines share.
	 * @param rows the offer file's rows
	 * @return {@code rdsStorageGibMonth}, or no rate when the file has no such price
	 * @throws PriceListException when it has several
	 */
	static SequencedMap<String, Double> storage(Stream<OfferRow> rows) {
		Set<Double> prices = new LinkedHashSet<>();
		rows.filter(RdsPrices::isSingleAzGp3).forEach((row) -> prices.add(row.price()));
		return UsageRates.rate("rdsStorageGibMonth", UsageRates.only(prices, "AmazonRDS gp3 storage"));
	}

	private static Optional<Sku> instance(OfferRow row, String engine) {
		String type = row.text("Instance Type");
		String vcpu = row.text("vCPU");
		Double memory = OfferText.gib(row.text("Memory"));
		double price = row.price();
		if (type.isEmpty() || !OfferText.isDigits(vcpu) || price == 0) {
			return Optional.empty();
		}
		if (memory == null || memory == 0) {
			return Optional.empty();
		}
		Shape shape = Shape.of(OfferText.vcpu(vcpu), memory);
		return Optional.of(Sku.of(type, OfferText.part(type, 1), shape, price)
			.withEngines(List.of(engine))
			.withBurst(Burstables.isBurstable(type), Burstables.baselineCpu(type)));
	}

	private static boolean isRegionalCredits(OfferRow row) {
		return row.isOnDemand() && row.isRegional() && row.is("Product Family", "CPU Credits") && row.price() > 0;
	}

	private static boolean isSingleAzHour(OfferRow row) {
		boolean hour = row.is("Product Family", "Database Instance") && row.is("Deployment Option", "Single-AZ")
				&& row.is("Unit", "Hrs");
		return hour && row.isOnDemand() && row.isRegional();
	}

	private static boolean isSingleAzGp3(OfferRow row) {
		boolean gp3 = row.is("Product Family", "Database Storage") && row.is("Volume Type", "General Purpose-GP3")
				&& row.is("Deployment Option", "Single-AZ") && row.is("Unit", "GB-Mo");
		return gp3 && row.isOnDemand() && row.isRegional() && row.price() > 0;
	}

}
