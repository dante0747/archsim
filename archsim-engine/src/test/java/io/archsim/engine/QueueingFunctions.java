package io.archsim.engine;

import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.queueing.ErlangC;
import io.archsim.engine.queueing.QueueWait;
import io.archsim.engine.queueing.Zipf;
import io.archsim.parity.GoldenEntry;

/**
 * Queueing theory and key popularity: the Erlang C formula, the M/M/c wait, Zipf shares.
 */
final class QueueingFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.explain.erlang_c",
					(entry) -> GoldenJson.number(ErlangC.waitProbability(entry.input("c").intValue(),
							entry.input("offered").doubleValue()))),
			Map.entry("archsim_engine.explain.zipf_share",
					(entry) -> GoldenJson.number(Zipf.share(entry.input("n").longValue(),
							entry.input("s").doubleValue(), entry.input("top").longValue()))),
			Map.entry("archsim_engine.estimate.mmc_wait", QueueingFunctions::mmcWait));

	private QueueingFunctions() {
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

}
