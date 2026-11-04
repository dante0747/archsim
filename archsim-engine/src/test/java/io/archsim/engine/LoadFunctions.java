package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.TtlCacheTraffic;
import io.archsim.engine.queueing.ErlangC;
import io.archsim.engine.queueing.QueueWait;
import io.archsim.engine.queueing.Zipf;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;
import io.archsim.parity.GoldenEntry;

/**
 * Load and queueing: the queueing formulas, key popularity, TTL caches' hit ratios and
 * service times' means.
 */
final class LoadFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.explain.erlang_c",
					(entry) -> GoldenJson.number(ErlangC.waitProbability(entry.input("c").intValue(),
							entry.input("offered").doubleValue()))),
			Map.entry("archsim_engine.explain.zipf_share",
					(entry) -> GoldenJson.number(Zipf.share(entry.input("n").longValue(),
							entry.input("s").doubleValue(), entry.input("top").longValue()))),
			Map.entry("archsim_engine.estimate.mmc_wait", LoadFunctions::mmcWait),
			Map.entry("archsim_engine.load.ttl_hit_ratio", (entry) -> GoldenJson.number(ttlTraffic(entry).hitRatio())),
			// the model's distributions give the mean the Python engine's mean_ms gives
			Map.entry("archsim_engine.load.mean_ms",
					(entry) -> GoldenJson.number(RecordCodec.read(entry.input("d"), Distribution.class).expected())));

	private LoadFunctions() {
	}

	/** {@code (probability, Moments(mean, var))}, or {@code None} when unstable. */
	private static JsonNode mmcWait(GoldenEntry entry) {
		return QueueWait
			.mmc(entry.input("slots").intValue(), entry.input("lam").doubleValue(), entry.input("holdMs").doubleValue())
			.<JsonNode>map((wait) -> {
				ObjectNode moments = GoldenJson.NODES.objectNode()
					.put("mean", wait.moments().mean())
					.put("var", wait.moments().variance());
				return GoldenJson.NODES.arrayNode().add(wait.probability()).add(moments);
			})
			.orElse(GoldenJson.NODES.nullNode());
	}

	private static TtlCacheTraffic ttlTraffic(GoldenEntry entry) {
		JsonNode capacity = entry.input("capacityKeys");
		@Nullable Long capacityKeys = capacity.isNull() ? null : capacity.longValue();
		return new TtlCacheTraffic(entry.input("readRps").doubleValue(), entry.input("writeRps").doubleValue(),
				entry.input("keys").longValue(), entry.input("skew").doubleValue(), entry.input("ttlS").doubleValue(),
				capacityKeys);
	}

}
