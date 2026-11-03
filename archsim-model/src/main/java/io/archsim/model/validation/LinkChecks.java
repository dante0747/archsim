package io.archsim.model.validation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.Port;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;

/**
 * Checks the links: that each joins two existing, different nodes on ports that send and
 * receive what it carries, once; that the caller-side settings are on links a service
 * starts; and that each port has as many links as it allows. Records the valid links for
 * the checks that follow.
 */
final class LinkChecks {

	private final ValidationContext context;

	/** The two ends and ports of each valid link so far, to find a link made twice. */
	private final Set<List<String>> connections = new HashSet<>();

	LinkChecks(ValidationContext context) {
		this.context = context;
	}

	void check() {
		checkLinks();
		checkCallerSettings();
		checkPortCounts();
	}

	private void checkLinks() {
		for (EdgeSpec edge : this.context.declaredEdges()) {
			Optional<Issue> problem = linkProblem(edge);
			if (problem.isPresent()) {
				this.context.report(problem.get());
			}
			else if (joinsKnownTypes(edge)) {
				addValidEdge(edge);
			}
		}
	}

	/**
	 * Whether a link without problems joins two nodes of known types, rather than being
	 * skipped as a link to a node of an unknown type.
	 * @param edge a link between two existing nodes
	 * @return {@code true} when the registry knows both nodes' types
	 */
	private boolean joinsKnownTypes(EdgeSpec edge) {
		return hasKnownType(edge.source()) && hasKnownType(edge.target());
	}

	/**
	 * Records a sound link for the checks that follow, and its ends and ports, so that a
	 * later link joining the same ports is found.
	 * @param edge the sound link
	 */
	private void addValidEdge(EdgeSpec edge) {
		this.connections.add(connection(edge));
		this.context.addValidEdge(edge);
	}

	private boolean hasKnownType(String nodeId) {
		return this.context.isKnownType(this.context.requireNode(nodeId).type());
	}

	/**
	 * What is wrong with a link, if anything.
	 * @param edge the link to check
	 * @return the first problem found; empty for a sound link, or for a link between
	 * unknown types, which is skipped silently
	 */
	private Optional<Issue> linkProblem(EdgeSpec edge) {
		NodeSpec source = this.context.node(edge.source());
		NodeSpec target = this.context.node(edge.target());
		if (source == null || target == null) {
			return Optional.of(unknownNode(edge, (source != null) ? edge.target() : edge.source()));
		}
		if (edge.source().equals(edge.target())) {
			return Optional.of(selfLoop(edge, source));
		}
		Optional<ComponentSpec<?>> sourceSpec = this.context.spec(source);
		Optional<ComponentSpec<?>> targetSpec = this.context.spec(target);
		if (sourceSpec.isEmpty() || targetSpec.isEmpty()) {
			return Optional.empty();
		}
		return portProblem(edge, new Endpoint(source, sourceSpec.get()), new Endpoint(target, targetSpec.get()))
			.or(() -> duplicateProblem(edge, source, target));
	}

	private static Issue unknownNode(EdgeSpec edge, String missing) {
		return Issue
			.error("edge.unknown_node",
					"Link '%s' references node '%s', which does not exist.".formatted(edge.id(), missing))
			.atEdge(edge.id())
			.withHint("Delete the link or recreate the missing node.");
	}

	private static Issue selfLoop(EdgeSpec edge, NodeSpec node) {
		return Issue.error("edge.self_loop", "Link '%s' connects %s to itself.".formatted(edge.id(), node.name()))
			.atEdge(edge.id())
			.withHint("A component cannot call itself; delete the link.");
	}

	/**
	 * What is wrong with a link's ports, if anything: a port the source cannot send from
	 * or the target cannot receive on, or request types a port does not take.
	 * @param edge the link to check
	 * @param source the node the link leaves, and its type
	 * @param target the node the link reaches, and its type
	 * @return the first problem found, or empty
	 */
	private static Optional<Issue> portProblem(EdgeSpec edge, Endpoint source, Endpoint target) {
		Optional<Port> sourcePort = source.spec().port(edge.sourcePort()).filter(Port::isOut);
		if (sourcePort.isEmpty()) {
			return Optional.of(invalidSourcePort(edge, source));
		}
		Optional<Port> targetPort = target.spec().port(edge.targetPort()).filter(Port::isIn);
		if (targetPort.isEmpty()) {
			return Optional.of(invalidTargetPort(edge, target));
		}
		return notEmitted(edge, source.node(), sourcePort.get())
			.or(() -> notAccepted(edge, target.node(), targetPort.get()));
	}

	private static Issue invalidSourcePort(EdgeSpec edge, Endpoint source) {
		ComponentSpec<?> spec = source.spec();
		String outs = names(spec.outPorts());
		return Issue
			.error("edge.invalid_source_port",
					"%s has no output port '%s' (outputs: %s).".formatted(source.node().name(), edge.sourcePort(),
							outs.isEmpty() ? "none" : outs))
			.atEdge(edge.id())
			.atNode(source.node().id())
			.withHint(spec.outPorts().isEmpty() ? "%s cannot send requests.".formatted(spec.displayName())
					: "Connect from one of: %s.".formatted(outs));
	}

	private static Issue invalidTargetPort(EdgeSpec edge, Endpoint target) {
		ComponentSpec<?> spec = target.spec();
		String name = target.node().name();
		boolean receives = !spec.inPorts().isEmpty();
		String message = receives
				? "%s has no input port '%s' (inputs: %s).".formatted(name, edge.targetPort(), names(spec.inPorts()))
				: "%s (%s) cannot receive requests.".formatted(name, spec.displayName());
		return Issue.error("edge.invalid_target_port", message)
			.atEdge(edge.id())
			.atNode(target.node().id())
			.withHint(receives ? null : "Traffic sources only send requests; connect them to an entry point instead.");
	}

	/**
	 * The request types a link carries that its source port does not send.
	 * @param edge the link to check
	 * @param source the node the link leaves
	 * @param port the port it leaves from
	 * @return an issue naming them, or empty when the port sends them all
	 */
	private static Optional<Issue> notEmitted(EdgeSpec edge, NodeSpec source, Port port) {
		Set<RequestType> extra = missing(edge.config().requestTypes(), port.requestTypes());
		if (extra.isEmpty()) {
			return Optional.empty();
		}
		String carried = IssueText.requestTypes(extra);
		String emits = IssueText.requestTypes(port.requestTypes());
		return Optional.of(Issue
			.error("edge.request_types",
					"Link '%s' carries %s but port '%s' of %s only emits %s.".formatted(edge.id(), carried, port.name(),
							source.name(), emits))
			.atEdge(edge.id())
			.withHint("Limit the link's request types to %s.".formatted(emits)));
	}

	/**
	 * The request types a link carries that its target port does not take.
	 * @param edge the link to check
	 * @param target the node the link reaches
	 * @param port the port it reaches
	 * @return an issue naming them, or empty when the port takes them all
	 */
	private static Optional<Issue> notAccepted(EdgeSpec edge, NodeSpec target, Port port) {
		Set<RequestType> extra = missing(edge.config().requestTypes(), port.requestTypes());
		if (extra.isEmpty()) {
			return Optional.empty();
		}
		String carried = IssueText.requestTypes(extra);
		String accepts = IssueText.requestTypes(port.requestTypes());
		return Optional.of(
				Issue
					.error("edge.request_types",
							"Link '%s' carries %s but %s only accepts %s on '%s'.".formatted(edge.id(), carried,
									target.name(), accepts, port.name()))
					.atEdge(edge.id()));
	}

	/**
	 * A link that joins the same two ports as an earlier valid one.
	 * @param edge the link to check
	 * @param source the node the link leaves
	 * @param target the node the link reaches
	 * @return an issue when an earlier valid link joins the same ports, or empty
	 */
	private Optional<Issue> duplicateProblem(EdgeSpec edge, NodeSpec source, NodeSpec target) {
		if (!this.connections.contains(connection(edge))) {
			return Optional.empty();
		}
		return Optional.of(
				Issue
					.error("edge.duplicate",
							"%s is linked to %s more than once on the same ports.".formatted(source.name(),
									target.name()))
					.atEdge(edge.id())
					.withHint("Merge the links by selecting several request types on one link."));
	}

	/**
	 * Warns of caller-side settings on a link a service does not start: only services
	 * call their dependencies one by one, so only they use them.
	 */
	private void checkCallerSettings() {
		for (EdgeSpec edge : this.context.validEdges()) {
			List<String> flags = callerSettings(edge);
			NodeSpec source = this.context.requireNode(edge.source());
			if (!flags.isEmpty() && !source.type().equals(ServiceSpec.TYPE)) {
				this.context.report(Issue
					.warning("edge.caller_setting_ignored",
							"Link '%s' starts at %s, which is not a service, so %s %s ignored.".formatted(edge.name(),
									source.name(), String.join(" and ", flags), (flags.size() == 1) ? "is" : "are"))
					.atEdge(edge.id())
					.withHint("Only a service's links use these settings."));
			}
		}
	}

	/**
	 * What tells a link made twice: its two ends and ports.
	 * @param edge the link
	 * @return its source, source port, target and target port
	 */
	private static List<String> connection(EdgeSpec edge) {
		return List.of(edge.source(), edge.sourcePort(), edge.target(), edge.targetPort());
	}

	private static List<String> callerSettings(EdgeSpec edge) {
		List<String> flags = new ArrayList<>();
		if (edge.config().readsOnMiss()) {
			flags.add("'Reads only on a cache miss'");
		}
		if (edge.config().continueOnFailure()) {
			flags.add("'Carry on if this call fails'");
		}
		return flags;
	}

	private void checkPortCounts() {
		for (NodeSpec node : this.context.declaredNodes()) {
			List<Port> ports = this.context.spec(node).map(ComponentSpec::ports).orElse(List.of());
			for (Port port : ports) {
				checkPortCount(node, port);
			}
		}
	}

	private void checkPortCount(NodeSpec node, Port port) {
		List<EdgeSpec> pool = port.isOut() ? this.context.outEdges(node.id()) : this.context.inEdges(node.id());
		long count = pool.stream()
			.filter((edge) -> (port.isOut() ? edge.sourcePort() : edge.targetPort()).equals(port.name()))
			.count();
		if (count < port.minEdges()) {
			this.context.report(Issue
				.error("port.missing_link",
						"%s: '%s' needs at least %d link(s). %s".formatted(node.name(), port.label(), port.minEdges(),
								port.description()))
				.atNode(node.id())
				.withHint("Drag from the '%s' handle of %s to a target.".formatted(port.name(), node.name())));
		}
		if (port.maxEdges() != null && count > port.maxEdges()) {
			this.context.report(Issue
				.error("port.too_many_links",
						"%s: '%s' allows at most %d link(s) but has %d.".formatted(node.name(), port.label(),
								port.maxEdges(), count))
				.atNode(node.id())
				.withHint("Remove the extra links (put a load balancer in between to fan out)."));
		}
	}

	private static Set<RequestType> missing(List<RequestType> carried, List<RequestType> allowed) {
		Set<RequestType> extra = EnumSet.noneOf(RequestType.class);
		extra.addAll(carried);
		allowed.forEach(extra::remove);
		return extra;
	}

	private static String names(List<Port> ports) {
		return ports.stream().map(Port::name).collect(Collectors.joining(", "));
	}

	/**
	 * One end of a link: a node, and its component type.
	 *
	 * @param node the node
	 * @param spec its component type
	 */
	private record Endpoint(NodeSpec node, ComponentSpec<?> spec) {
	}

}
