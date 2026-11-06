package io.archsim.engine.imports;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.load.ComponentFamily;

/**
 * Clients for an imported design, which the file does not describe: one Clients part
 * (assumed) sending to the entry point. The entry is a preferred part (published ports, a
 * LoadBalancer Service, an Ingress), else a load balancer or service nothing calls; never
 * a Kubernetes cluster (services run on it, nothing sends to it) nor a tool. Clients send
 * to one part, so several entries get a load balancer in front of them (assumed).
 */
final class EntryPoint {

	private final DesignBuilder design;

	private final ImportLog log;

	private final Set<String> skip;

	/**
	 * Finds the entry point of a design.
	 * @param design the design built
	 * @param log the report, which the guess goes into
	 * @param skip parts never taken as the entry point (tools)
	 */
	EntryPoint(DesignBuilder design, ImportLog log, Set<String> skip) {
		this.design = design;
		this.log = log;
		this.skip = Set.copyOf(skip);
	}

	/**
	 * Adds the Clients part, sending to the entry point.
	 * @param preferred the parts preferred as the entry point, in order
	 */
	void addClients(List<String> preferred) {
		List<String> entry = entry(preferred);
		if (entry.isEmpty()) {
			return;
		}
		String clients = this.design.uniqueId("clients");
		this.design.add(clients, "traffic_source", "Clients", JsonNodeFactory.instance.objectNode());
		if (entry.size() == 1) {
			this.design.link(clients, entry.getFirst());
			this.log.guessed("a Clients part sending to " + entry.getFirst()
					+ " (the entry point; clients are not in the file)");
			return;
		}
		String gateway = this.design.uniqueId("entry");
		this.design.add(gateway, "load_balancer", "Entry load balancer", JsonNodeFactory.instance.objectNode());
		entry.forEach((target) -> this.design.link(gateway, target));
		this.design.link(clients, gateway);
		this.log
			.guessed("a Clients part sending to " + gateway + ", a load balancer added in front of the entry points "
					+ String.join(", ", entry) + " (clients send to one part; clients are not in the file)");
	}

	/**
	 * The parts clients send to.
	 * @param preferred the parts preferred, in order
	 * @return the preferred load balancers, else the preferred parts nothing calls, else
	 * the load balancers nothing calls, else any part nothing calls that may be an entry
	 */
	private List<String> entry(List<String> preferred) {
		Set<String> called = new HashSet<>();
		this.design.edges().forEach((edge) -> called.add(edge.path("target").stringValue()));
		List<String> candidates = new LinkedHashSet<>(preferred).stream().filter(this::isCandidate).toList();
		List<String> entry = networkOrElse(candidates,
				candidates.stream().filter((candidate) -> !called.contains(candidate)).toList());
		if (!entry.isEmpty()) {
			return entry;
		}
		List<String> free = this.design.nodes()
			.stream()
			.map((node) -> node.path("id").stringValue())
			.filter((nodeId) -> isCandidate(nodeId) && !called.contains(nodeId))
			.toList();
		return networkOrElse(free, free);
	}

	/**
	 * The load balancers among some parts, or other parts when there are none.
	 * @param parts the parts
	 * @param otherwise the parts when none is a load balancer
	 * @return the load balancers, or {@code otherwise}
	 */
	private List<String> networkOrElse(List<String> parts, List<String> otherwise) {
		List<String> network = parts.stream()
			.filter((part) -> this.design.kind(part) == ComponentFamily.NETWORK)
			.toList();
		return network.isEmpty() ? otherwise : network;
	}

	private boolean isCandidate(String nodeId) {
		return this.design.node(nodeId)
			.filter((node) -> !node.path("type").stringValue().equals("kubernetes_cluster"))
			.isPresent() && !this.skip.contains(nodeId) && isEntryKind(this.design.kind(nodeId));
	}

	/**
	 * Whether a kind of part may be the entry point.
	 * @param family the kind
	 * @return {@code true} for a load balancer or a service
	 */
	private static boolean isEntryKind(ComponentFamily family) {
		return family == ComponentFamily.NETWORK || family == ComponentFamily.COMPUTE;
	}

}
