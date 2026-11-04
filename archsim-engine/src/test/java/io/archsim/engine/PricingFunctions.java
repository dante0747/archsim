package io.archsim.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.pricing.Busy;
import io.archsim.engine.pricing.InstanceTypes;
import io.archsim.engine.pricing.MachineKind;
import io.archsim.engine.pricing.MachineSize;
import io.archsim.engine.pricing.Purchase;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.Burstable;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;
import io.archsim.parity.GoldenEntry;

/** Instance types: which price list a part's machines come from, and covering a size. */
final class PricingFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.pricing.Snapshot.cover", PricingFunctions::cover),
			Map.entry("archsim_engine.pricing.machine_kind", PricingFunctions::machineKind));

	private PricingFunctions() {
	}

	private static JsonNode cover(GoldenEntry entry) {
		JsonNode families = entry.input("families");
		List<String> named = new ArrayList<>();
		families.forEach((family) -> named.add(family.stringValue()));
		Purchase purchase = new Purchase(entry.input("option").stringValue(), named,
				Burstable.valueOf(entry.input("burstable").stringValue().toUpperCase(Locale.ROOT)));
		MachineKind kind = new MachineKind(category(entry.input("category").stringValue()),
				GoldenJson.readNullableText(entry.input("engine")));
		MachineSize size = new MachineSize(GoldenJson.readNullableNumber(entry.input("cpu")),
				GoldenJson.readNullableNumber(entry.input("memoryGib")), busy(entry.input("busy")));
		return GoldenJson.orNone(new InstanceTypes(snapshotOf(entry.input("snapshot")), purchase).cover(kind, size)
			.map(RecordCodec::write));
	}

	/** {@code (category, engine)}, or {@code None}. */
	private static JsonNode machineKind(GoldenEntry entry) {
		return GoldenJson
			.orNone(MachineKind.of(entry.input("nodeType").stringValue(), LenientSettings.of(entry.input("cfg")))
				.map((kind) -> GoldenJson.NODES.arrayNode().add(kind.category().json()).add(kind.engine())));
	}

	private static @Nullable Busy busy(JsonNode busy) {
		return busy.isNull() ? null : new Busy(busy.get("peak").doubleValue(), busy.get("base").doubleValue());
	}

	private static SkuCategory category(String json) {
		return Arrays.stream(SkuCategory.values())
			.filter((category) -> category.json().equals(json))
			.findFirst()
			.orElseThrow();
	}

	/**
	 * A snapshot back from what the golden file records: {@code to_json()} plus its
	 * types.
	 */
	private static PriceSnapshot snapshotOf(JsonNode json) {
		Map<SkuCategory, List<Sku>> skus = new EnumMap<>(SkuCategory.class);
		for (SkuCategory category : SkuCategory.values()) {
			List<Sku> items = new ArrayList<>();
			json.path("skus").path(category.json()).forEach((sku) -> items.add(RecordCodec.read(sku, Sku.class)));
			skus.put(category, items);
		}
		return new PriceSnapshot(json.get("provider").stringValue(), GoldenJson.readNullableText(json.get("region")),
				GoldenJson.readNullableText(json.get("snapshotDate")), List.of(), json.get("currency").stringValue(),
				json.get("illustrative").booleanValue(), GoldenJson.readNullableText(json.get("note")), skus, Map.of(),
				GoldenJson.readNullableText(json.get("sha256")));
	}

}
