package io.archsim.tools.prices;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

class SkuListTests {

	@Test
	void dedupe_typesThatDifferOnlyInTheirEngines_areMergedWithTheirEnginesSorted() {
		List<Sku> merged = SkuList.dedupe(
				List.of(database("postgresql", 0.25), database("mysql", 0.24), database("mariadb", 0.24)), "databases");

		assertThat(merged).extracting(Sku::hourly, Sku::engines)
			.containsExactly(tuple(0.25, List.of("postgresql")), tuple(0.24, List.of("mariadb", "mysql")));
	}

	@Test
	void dedupe_aTypeAndEngineAtTwoPrices_isRefused() {
		List<Sku> twice = List.of(database("postgresql", 0.25), database("postgresql", 0.1));

		assertThatExceptionOfType(PriceListException.class).isThrownBy(() -> SkuList.dedupe(twice, "databases"))
			.withMessage("databases: db.y9.large (postgresql) has two prices, 0.25 and 0.1; "
					+ "the price list format may have changed");
	}

	@Test
	void dedupe_aComputeTypeAtTwoPrices_isRefusedByItsNameAlone() {
		List<Sku> twice = List.of(Sku.of("x9.large", "x9", Shape.of(2, 8), 0.1),
				Sku.of("x9.large", "x9", Shape.of(2, 8), 0.05));

		assertThatExceptionOfType(PriceListException.class).isThrownBy(() -> SkuList.dedupe(twice, "compute"))
			.withMessageStartingWith("compute: x9.large has two prices, 0.1 and 0.05;");
	}

	@Test
	void inOrder_types_areSortedByFamilyShapeAndName() {
		List<Sku> types = List.of(Sku.of("m5.xlarge", "m5", Shape.of(4, 16), 0.192),
				Sku.of("m5.large-b", "m5", Shape.of(2, 8), 0.1), Sku.of("m5.large", "m5", Shape.of(2, 8), 0.096),
				Sku.of("c5.large", "c5", Shape.of(2, 4), 0.085));

		List<Sku> sorted = SkuList.inOrder(types, "compute");

		assertThat(sorted).extracting(Sku::sku).containsExactly("c5.large", "m5.large", "m5.large-b", "m5.xlarge");
	}

	@Test
	void inOrder_oneTypeForSeveralEngines_putsTheEnginesInOrder() {
		List<Sku> types = List.of(database("redis", 0.274), database("mysql", 0.168), database("valkey", 0.219));

		List<Sku> sorted = SkuList.inOrder(types, "caches");

		assertThat(sorted).extracting(Sku::engines)
			.containsExactly(List.of("mysql"), List.of("redis"), List.of("valkey"));
	}

	private static Sku database(String engine, double price) {
		return Sku.of("db.y9.large", "y9", Shape.of(2, 16), price).withEngines(List.of(engine));
	}

}
