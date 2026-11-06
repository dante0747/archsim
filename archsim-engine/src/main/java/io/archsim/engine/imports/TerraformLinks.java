package io.archsim.engine.imports;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.TerraformParts.Located;
import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.roundtrip.ExportedLink;

/**
 * The links of a design imported from a Terraform plan. Plans rarely say who calls whom,
 * so links are added where the code states them (a Lambda event source mapping); a load
 * balancer, which needs targets to run, routes to the services nothing else calls, and a
 * CDN's misses go to its origin (guesses); every other pair a service might use is a
 * suggestion to confirm.
 */
final class TerraformLinks {

	private final DesignBuilder design;

	private final ImportLog log;

	private final TerraformParts parts;

	/**
	 * Links a design's parts.
	 * @param parts the parts made, by their resources' addresses
	 */
	TerraformLinks(TerraformParts parts) {
		this.parts = parts;
		this.design = parts.design();
		this.log = parts.log();
	}

	/**
	 * Links each queue or stream to the function an event source mapping says consumes
	 * it.
	 * @param resources the plan's resources, in order
	 * @param references what each resource's configuration references
	 */
	void linkEventSources(List<TerraformResource> resources, TerraformReferences references) {
		for (TerraformResource resource : resources) {
			if (!resource.is("aws_lambda_event_source_mapping")) {
				continue;
			}
			JsonNode address = resource.address();
			Optional<String> source = referenced(references.referencedBy(address, "event_source_arn"));
			Optional<String> function = referenced(references.referencedBy(address, "function_name"));
			if (source.isPresent() && function.isPresent() && this.design.link(source.get(), function.get())) {
				this.log.mapped(PythonValues.str(address) + " -> link " + source.get() + " -> " + function.get()
						+ " (the function consumes the queue)");
			}
			else {
				this.log.ignored(PythonValues.str(address) + " (its queue or function is not in the plan)");
			}
		}
	}

	/**
	 * Adds the guessed links and the suggestions, and lists a plan with stores but no
	 * compute.
	 */
	void guessLinks() {
		Roles roles = Roles.of(this.design);
		Set<String> called = targets();
		List<String> free = roles.callers().stream().filter((caller) -> !called.contains(caller)).toList();
		routeBalancers(roles.balancers(), free);
		List<String> origins = (roles.balancers().size() == 1) ? roles.balancers()
				: ((free.size() == 1) ? free : List.of());
		for (String cdn : roles.cdns()) {
			if (this.design.callees(cdn, "miss").isEmpty() && !origins.isEmpty()
					&& this.design.link(cdn, new ExportedLink(origins.getFirst(), "miss", List.of("read"), Map.of()))) {
				this.log.guessed("link " + cdn + " -> " + origins.getFirst()
						+ " on its miss port (the CDN's origin; the plan does not say)");
			}
		}
		suggest(roles);
		if (roles.callers().isEmpty() && !roles.stores().isEmpty()) {
			this.log
				.guessed("no compute found (EC2/ECS/EKS workloads, functions): add the services that use these stores");
		}
	}

	/**
	 * The parts clients may enter by.
	 * @return the CDNs, then the load balancers, that nothing calls
	 */
	List<String> entries() {
		Roles roles = Roles.of(this.design);
		Set<String> linkedTo = targets();
		return Stream.concat(roles.cdns().stream(), roles.balancers().stream())
			.filter((entry) -> !linkedTo.contains(entry))
			.toList();
	}

	/**
	 * Routes each load balancer that calls nothing to the services nothing else calls.
	 * @param balancers the load balancers
	 * @param free the services nothing called before
	 */
	private void routeBalancers(List<String> balancers, List<String> free) {
		for (String balancer : balancers) {
			if (!this.design.callees(balancer, null).isEmpty()) {
				continue;
			}
			for (String target : free) {
				if (this.design.link(balancer, target)) {
					this.log.guessed("link " + balancer + " -> " + target + " (the plan does not say what " + balancer
							+ " routes to; " + target + " is a service nothing else calls)");
				}
			}
		}
	}

	/**
	 * Suggests a link from each service to each store, and from each load balancer to
	 * each service, that are not linked.
	 * @param roles the parts by role
	 */
	private void suggest(Roles roles) {
		Set<List<String>> linked = new HashSet<>();
		this.design.edges()
			.forEach((edge) -> linked
				.add(List.of(edge.path("source").stringValue(), edge.path("target").stringValue())));
		for (String caller : roles.callers()) {
			for (String store : roles.stores()) {
				if (!linked.contains(List.of(caller, store)) && !linked.contains(List.of(store, caller))) {
					this.log.suggested(caller, store, "Terraform does not say whether it calls it");
				}
			}
		}
		for (String balancer : roles.balancers()) {
			for (String caller : roles.callers()) {
				if (!linked.contains(List.of(balancer, caller))) {
					this.log.suggested(balancer, caller, "which services does the load balancer route to?");
				}
			}
		}
	}

	private Set<String> targets() {
		Set<String> targets = new HashSet<>();
		this.design.edges().forEach((edge) -> targets.add(edge.path("target").stringValue()));
		return targets;
	}

	/**
	 * The part a reference names: {@code aws_sqs_queue.orders} or
	 * {@code aws_sqs_queue.orders.arn}, not {@code aws_sqs_queue.orders_dlq}, which only
	 * starts the same way.
	 * @param references the references, in order
	 * @return the part of the first reference that names one
	 */
	private Optional<String> referenced(List<JsonNode> references) {
		for (JsonNode reference : references) {
			for (Located located : this.parts.located()) {
				JsonNode address = located.address();
				if (equal(reference, address) || startsWith(reference, address, '.')
						|| startsWith(reference, address, '[')) {
					return Optional.of(located.partId());
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * {@code reference == address}, of an address, which as a key is never a list or a
	 * mapping.
	 * @param reference the reference, as written
	 * @param address the address, as written
	 * @return whether Python counts them equal
	 */
	private static boolean equal(JsonNode reference, JsonNode address) {
		if (PythonValues.isNumber(reference) && PythonValues.isNumber(address)) {
			Optional<BigDecimal> first = exact(reference);
			Optional<BigDecimal> second = exact(address);
			if (first.isPresent() && second.isPresent()) {
				return first.get().compareTo(second.get()) == 0;
			}
			// a float that is not finite equals only the same infinity
			return reference.isFloatingPointNumber() && address.isFloatingPointNumber()
					&& reference.doubleValue() == address.doubleValue();
		}
		if (reference.isString() && address.isString()) {
			return reference.stringValue().equals(address.stringValue());
		}
		return reference.isNull() && address.isNull();
	}

	/**
	 * A number's exact value.
	 * @param number a number or a boolean
	 * @return its value; empty for a float that is not finite
	 */
	private static Optional<BigDecimal> exact(JsonNode number) {
		if (number.isBoolean() || number.isIntegralNumber()) {
			return Optional.of(new BigDecimal(PythonValues.toInt(number)));
		}
		double value = number.doubleValue();
		return Double.isFinite(value) ? Optional.of(new BigDecimal(value)) : Optional.empty();
	}

	/**
	 * {@code reference.startswith(address + separator)}.
	 * @param reference the reference, as written
	 * @param address the address, as written
	 * @param separator what follows the address
	 * @return whether the reference names something of the address's resource
	 * @throws ImportShapeException in Python's words when either is not text
	 */
	private static boolean startsWith(JsonNode reference, JsonNode address, char separator) {
		if (!reference.isString()) {
			throw new ImportShapeException(
					"'" + PythonValues.type(reference) + "' object has no attribute 'startswith'");
		}
		if (!address.isString()) {
			throw new ImportShapeException(
					"unsupported operand type(s) for +: '" + PythonValues.type(address) + "' and 'str'");
		}
		return reference.stringValue().startsWith(address.stringValue() + separator);
	}

	/**
	 * A design's parts by the role a link guess gives them: services that call (a
	 * Kubernetes cluster is where services run, not a caller), stores, load balancers and
	 * CDNs.
	 *
	 * @param callers the services
	 * @param stores the databases, caches and queues, CDNs aside
	 * @param balancers the load balancers and gateways
	 * @param cdns the CDNs
	 */
	private record Roles(List<String> callers, List<String> stores, List<String> balancers, List<String> cdns) {

		static Roles of(DesignBuilder design) {
			return new Roles(
					ids(design,
							(node) -> design.kind(id(node)) == ComponentFamily.COMPUTE
									&& !"kubernetes_cluster".equals(node.path("type").stringValue())),
					ids(design, (node) -> isStore(design.kind(id(node))) && !isCdn(node)),
					ids(design, (node) -> design.kind(id(node)) == ComponentFamily.NETWORK), ids(design, Roles::isCdn));
		}

		private static List<String> ids(DesignBuilder design, Predicate<ObjectNode> role) {
			return design.nodes().stream().filter(role).map(Roles::id).toList();
		}

		private static String id(ObjectNode node) {
			return node.path("id").stringValue();
		}

		private static boolean isStore(ComponentFamily family) {
			return family == ComponentFamily.DATABASE || family == ComponentFamily.CACHE
					|| family == ComponentFamily.MESSAGING;
		}

		private static boolean isCdn(ObjectNode node) {
			JsonNode technology = node.path("config").path("technology");
			return technology.isString() && "cdn".equals(technology.stringValue());
		}

	}

}
