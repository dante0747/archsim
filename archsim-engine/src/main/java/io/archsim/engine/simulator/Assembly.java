package io.archsim.engine.simulator;

import java.util.function.Function;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.HostSpec;
import io.archsim.model.document.NodeSpec;

/**
 * A design built into a run: its host machine, its links and the clusters services find,
 * before each part.
 */
final class Assembly {

	private Assembly() {
	}

	/**
	 * Builds a resolved design's links and parts into a run.
	 * @param context the run
	 * @param resolved the design as resolved
	 * @param config each part's settings, as validated
	 * @param clients what the run's users experience
	 */
	static void build(SimContext context, ArchitectureDocument resolved, Function<NodeSpec, Record> config,
			ClientMetrics clients) {
		HostSpec host = resolved.architecture().host();
		if (host != null) {
			// every part with a CPU size shares the design's host machine
			context.useHost(host, resolved.workload()::rateAt);
		}
		for (EdgeSpec edge : resolved.architecture().edges()) {
			context
				.addEdge(new EdgeRuntime(edge, context.timeline(), context.stream("edge:" + edge.id() + ":latency")));
		}
		// every service on Kubernetes finds its cluster as it is built
		context.useClusters(resolved.architecture()
			.nodes()
			.stream()
			.filter((node) -> KubernetesClusterSpec.TYPE.equals(node.type()))
			.map(NodeSpec::id)
			.toList());
		for (NodeSpec node : resolved.architecture().nodes()) {
			context.addComponent(Parts.build(context, node, config.apply(node), clients));
		}
	}

}
