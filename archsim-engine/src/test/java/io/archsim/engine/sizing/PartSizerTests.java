package io.archsim.engine.sizing;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.pricing.PricingChoice;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;

/** Sizing one part, for loads given. */
class PartSizerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void size_aPartWhoseLoadIsNotEstimated_isSkippedWithTheReason() {
		ArchitectureDocument design = DesignDocuments.parse(JSON.readTree("""
				{"architecture": {"nodes": [{"id": "api", "type": "service"}]}, "workload": {"rps": 100}}"""));
		// The estimator gives every standard part a load, so this
		// estimate is built by hand (hence LoadEstimate's public
		// constructor): it guards a part whose load depends on the run,
		// as the Python engine does.
		Map<String, @Nullable NodeLoad> unknown = new HashMap<>();
		unknown.put("api", null);
		LoadEstimate loads = new LoadEstimate(unknown, Map.of());
		PartSizer sizer = new PartSizer(design, StandardComponents.registry(),
				PricingChoice.of(design.pricing(), PriceSnapshots.shipped()),
				new SizingLoads(loads, loads, Map.of(), Map.of()));

		PartSize api = sizer.size(design.node("api").orElseThrow()).orElseThrow();

		assertThat(api.skipped()).isEqualTo("Its load cannot be estimated before a run.");
		assertThat(api.cpuCores()).isNull();
	}

}
