package io.archsim.engine;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.sizing.MemoryModel;
import io.archsim.engine.sizing.Need;
import io.archsim.engine.sizing.PartSize;
import io.archsim.engine.sizing.Spec;

/**
 * Auto sizes as the golden files record them: {@code PartSize}'s fields at full
 * precision, camelCase, its memory model a four-number array.
 */
final class GoldenSizes {

	private GoldenSizes() {
	}

	static ObjectNode write(Map<String, PartSize> sizes) {
		ObjectNode json = GoldenJson.NODES.objectNode();
		sizes.forEach((id, size) -> json.set(id, size(size)));
		return json;
	}

	static Map<String, PartSize> read(JsonNode json) {
		Map<String, PartSize> sizes = new LinkedHashMap<>();
		json.properties().forEach((size) -> sizes.put(size.getKey(), new Reader(size.getValue()).build()));
		return sizes;
	}

	private static ObjectNode size(PartSize size) {
		ObjectNode json = GoldenJson.NODES.objectNode();
		json.put("cpuCores", size.cpuCores());
		json.put("memoryGib", size.memoryGib());
		json.put("storageGib", size.storageGib());
		json.put("peakRps", size.peakRps());
		ObjectNode why = json.putObject("why");
		size.why().forEach((spec, text) -> why.put(spec.json(), text));
		json.put("skipped", size.skipped());
		json.put("sku", size.sku());
		ObjectNode need = json.putObject("need");
		size.need().forEach((key, value) -> need.put(key.json(), value));
		MemoryModel model = size.memoryModel();
		if (model == null) {
			json.putNull("memoryModel");
		}
		else {
			json.putArray("memoryModel")
				.add(model.fixedGib())
				.add(model.perRequestGib())
				.add(model.pools())
				.add(model.perRpsGib());
		}
		json.put("smallest", size.smallest());
		return json;
	}

	/** One recorded size, read back. */
	private record Reader(JsonNode json) {

		PartSize build() {
			Map<Spec, String> why = new EnumMap<>(Spec.class);
			for (Spec spec : Spec.values()) {
				JsonNode text = this.json.path("why").path(spec.json());
				if (text.isString()) {
					why.put(spec, text.stringValue());
				}
			}
			Map<Need, Double> need = new EnumMap<>(Need.class);
			for (Need key : Need.values()) {
				JsonNode value = this.json.path("need").path(key.json());
				if (value.isNumber()) {
					need.put(key, value.doubleValue());
				}
			}
			return new PartSize(number("cpuCores"), number("memoryGib"), number("storageGib"),
					this.json.path("peakRps").doubleValue(), why, text("skipped"), text("sku"), need, model(),
					this.json.path("smallest").booleanValue());
		}

		private @Nullable MemoryModel model() {
			if (!(this.json.path("memoryModel") instanceof ArrayNode values)) {
				return null;
			}
			return new MemoryModel(values.get(0).doubleValue(), values.get(1).doubleValue(),
					values.get(2).doubleValue(), values.get(3).doubleValue());
		}

		private @Nullable Double number(String name) {
			return GoldenJson.readNullableNumber(this.json.path(name));
		}

		private @Nullable String text(String name) {
			return GoldenJson.readNullableText(this.json.path(name));
		}

	}

}
