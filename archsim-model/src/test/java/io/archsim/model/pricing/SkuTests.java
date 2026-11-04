package io.archsim.model.pricing;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An instance type of a price snapshot. */
class SkuTests {

	private static final Sku POSTGRES = new Sku("db.m6g.large", "m6g", 2, 8, Map.of("on_demand", 0.152),
			List.of("postgresql"), false, null, null);

	private static final Sku COMPUTE = new Sku("c6g.large", "c6g", 2, 4, Map.of("on_demand", 0.068), List.of(), false,
			null, null);

	@Test
	void runs_anEngineTheTypeLists_isTrue() {
		assertThat(POSTGRES.runs("postgresql")).isTrue();
	}

	@Test
	void runs_anEngineTheTypeDoesNotList_isFalse() {
		assertThat(POSTGRES.runs("mysql")).isFalse();
	}

	@Test
	void runs_aTypeListingNoEngine_runsAny() {
		assertThat(COMPUTE.runs("mysql")).isTrue();
	}

	@Test
	void runs_noEngineAskedFor_isTrue() {
		assertThat(POSTGRES.runs(null)).isTrue();
	}

}
