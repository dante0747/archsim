package io.archsim.engine.load;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.NodeSpec;

/** How a design's clients share its workload: by their traffic shares. */
final class ClientShares {

	/** A client's traffic share when it sets none, in percent. */
	private static final double DEFAULT_SHARE = 100.0;

	private ClientShares() {
	}

	/**
	 * Each client's fraction of the workload, in the design's order; of two clients with
	 * one id, the later's.
	 * @param nodes the design's parts
	 * @param registry the component types
	 * @return the fraction of each client, by its id
	 */
	static Map<String, Double> of(List<NodeSpec> nodes, ComponentRegistry registry) {
		List<NodeSpec> clients = nodes.stream()
			.filter((node) -> ComponentFamily.of(node.type(), registry) == ComponentFamily.TRAFFIC)
			.toList();
		double[] weights = clients.stream().mapToDouble((client) -> weight(client, registry)).toArray();
		double total = FloatSum.of(Arrays.stream(weights));
		Map<String, Double> shares = new LinkedHashMap<>();
		for (int i = 0; i < clients.size(); i++) {
			shares.put(clients.get(i).id(), (total > 0) ? weights[i] / total : 0.0);
		}
		return shares;
	}

	private static double weight(NodeSpec client, ComponentRegistry registry) {
		LenientSettings settings = LenientSettings.ofNode(registry.find(client.type()), client.config());
		double share = settings.number("trafficShare").orElse(DEFAULT_SHARE);
		return Math.max(share, 0.0);
	}

}
