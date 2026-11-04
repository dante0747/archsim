package io.archsim.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.BaseRate;
import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.load.TtlCacheTraffic;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;
import io.archsim.parity.GoldenEntry;

/**
 * The load a design carries: its peak and base rates, each part's load, TTL caches' miss
 * shares and hit ratios, and service times' means.
 */
final class LoadFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			// the model's workload gives the peak the Python engine's peak_rps gives
			Map.entry("archsim_engine.load.peak_rps", (entry) -> GoldenJson.number(workload(entry).peakRate())),
			Map.entry("archsim_engine.load.base_rps", (entry) -> GoldenJson.number(BaseRate.of(workload(entry)))),
			Map.entry("archsim_engine.load.estimate_load", LoadFunctions::estimateLoad),
			Map.entry("archsim_engine.load.ttl_miss_shares", LoadFunctions::ttlMissShares),
			Map.entry("archsim_engine.load.ttl_hit_ratio", (entry) -> GoldenJson.number(ttlTraffic(entry).hitRatio())),
			Map.entry("archsim_engine.load.mean_ms", LoadFunctions::meanMs));

	private static final LoadEstimator ESTIMATOR = new LoadEstimator(StandardComponents.registry());

	private LoadFunctions() {
	}

	/** {@code mean_ms(d)}: the mean of a distribution read as a setting. */
	private static JsonNode meanMs(GoldenEntry entry) {
		ObjectNode settings = GoldenJson.NODES.objectNode();
		settings.set("d", entry.input("d"));
		return GoldenJson.number(LenientSettings.of(settings).meanMs("d"));
	}

	/** {@code {loads, edgeLoads}}: the return value and the link loads it fills. */
	private static JsonNode estimateLoad(GoldenEntry entry) {
		ArchitectureDocument design = DesignDocuments.parse(entry.input("doc"));
		Map<String, Double> shares = new LinkedHashMap<>();
		entry.input("missShares")
			.properties()
			.forEach((share) -> shares.put(share.getKey(), share.getValue().doubleValue()));
		CacheMisses misses = new CacheMisses(shares, GoldenJson.readNullableNumber(entry.input("ttlMissShare")));
		LoadEstimate loads = ESTIMATOR.estimate(design.architecture(), design.workload(), rate(entry, design), misses);
		ObjectNode output = GoldenJson.NODES.objectNode();
		output.set("loads", loads(loads.nodes()));
		output.set("edgeLoads", loads(loads.edges()));
		return output;
	}

	private static JsonNode ttlMissShares(GoldenEntry entry) {
		ArchitectureDocument design = DesignDocuments.parse(entry.input("doc"));
		ObjectNode output = GoldenJson.NODES.objectNode();
		ESTIMATOR.ttlMissShares(design.architecture(), design.workload(), rate(entry, design)).forEach(output::put);
		return output;
	}

	/** The entry's rate, or the workload's peak when it gives none. */
	private static double rate(GoldenEntry entry, ArchitectureDocument design) {
		JsonNode rate = entry.input("rate");
		return rate.isNull() ? design.workload().peakRate() : rate.doubleValue();
	}

	private static ObjectNode loads(Map<String, @Nullable NodeLoad> loads) {
		ObjectNode json = GoldenJson.NODES.objectNode();
		loads.forEach(
				(id, load) -> json.set(id, (load != null) ? RecordCodec.write(load) : GoldenJson.NODES.nullNode()));
		return json;
	}

	private static Workload workload(GoldenEntry entry) {
		return RecordCodec.read(entry.input("workload"), Workload.class);
	}

	private static TtlCacheTraffic ttlTraffic(GoldenEntry entry) {
		JsonNode capacity = entry.input("capacityKeys");
		@Nullable Long capacityKeys = capacity.isNull() ? null : capacity.longValue();
		return new TtlCacheTraffic(entry.input("readRps").doubleValue(), entry.input("writeRps").doubleValue(),
				entry.input("keys").longValue(), entry.input("skew").doubleValue(), entry.input("ttlS").doubleValue(),
				capacityKeys);
	}

}
