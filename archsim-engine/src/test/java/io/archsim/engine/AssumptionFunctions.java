package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.assumptions.AssumptionsPage;
import io.archsim.engine.assumptions.EngineConstant;
import io.archsim.engine.assumptions.EngineConstants;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenEntry;

/**
 * The page of every number ArchSim uses without the user typing it, the engine-wide
 * constants it lists, and the engine's versions.
 */
final class AssumptionFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.assumptions.constants", (entry) -> constants()),
			Map.entry("archsim_engine.assumptions.render",
					(entry) -> GoldenJson.text(AssumptionsPage.render(StandardComponents.registry()))),
			Map.entry("archsim_engine.version.ENGINE_VERSION", (entry) -> GoldenJson.text(EngineVersions.ENGINE)),
			Map.entry("archsim_engine.version.REPORT_VERSION", (entry) -> GoldenJson.text(EngineVersions.REPORT)));

	private AssumptionFunctions() {
	}

	/**
	 * The constants as the Python's {@code Constant} dataclasses are recorded.
	 * @return the constants, in the page's order
	 */
	private static ArrayNode constants() {
		ArrayNode out = GoldenJson.NODES.arrayNode();
		for (EngineConstant constant : EngineConstants.all()) {
			out.addObject()
				.put("area", constant.area())
				.put("name", constant.name())
				.put("value", constant.value())
				.put("code", constant.code())
				.put("source", constant.source());
		}
		return out;
	}

}
