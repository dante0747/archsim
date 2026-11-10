package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.assumptions.AssumptionsPage;
import io.archsim.engine.assumptions.EngineConstant;
import io.archsim.engine.assumptions.EngineConstants;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.GoldenFile;

/**
 * The page of every number ArchSim uses without the user typing it, and the engine-wide
 * constants it lists.
 */
final class AssumptionFunctions {

	// ENGINE_VERSION and REPORT_VERSION are not checked: the Java engine is 1.0.0 and
	// writes report 1.15 (ADR-0050), departures the engine's README lists
	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.assumptions.constants", (entry) -> constants()),
			Map.entry("archsim_engine.assumptions.render", (entry) -> GoldenJson
				.text(AssumptionsPage.render(StandardComponents.registry(), recordingEngine(entry)))));

	private AssumptionFunctions() {
	}

	/**
	 * The version of the engine that recorded an entry, which the page it rendered names.
	 * @param entry a golden entry
	 * @return the version its golden file records
	 */
	private static String recordingEngine(GoldenEntry entry) {
		return GoldenFile.read(entry.file()).root().required("engineVersion").stringValue();
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
