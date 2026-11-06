package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.imports.Manifests.Workload;

/**
 * The Services, HorizontalPodAutoscalers and Ingresses of Kubernetes manifests: an
 * {@code ExternalName} Service as a third party or managed service, any other as a name
 * for the workload it selects (a LoadBalancer one an entry point), an autoscaler as its
 * workload's autoscaling, an Ingress as the gateway in front of what it routes to.
 */
final class KubernetesServices {

	private final DesignBuilder design;

	private final ImportLog log;

	private final PartNames names;

	private final List<Workload> objects;

	private final List<String> entries = new ArrayList<>();

	/**
	 * Reads Services and Ingresses into a design.
	 * @param design the design
	 * @param log the report
	 * @param names the parts by name, which Services add names to and Ingresses route by
	 * @param objects the workloads, which a selector matches
	 */
	KubernetesServices(DesignBuilder design, ImportLog log, PartNames names, List<Workload> objects) {
		this.design = design;
		this.log = log;
		this.names = names;
		this.objects = List.copyOf(objects);
	}

	/**
	 * The parts clients may enter by.
	 * @return the workloads of LoadBalancer Services, and the Ingresses, in order
	 */
	List<String> entries() {
		return Collections.unmodifiableList(this.entries);
	}

	/**
	 * Reads a Service.
	 * @param service the Service
	 */
	void read(ObjectNode service) {
		JsonNode metadata = PythonValues.item(service, "metadata");
		JsonNode name = PythonValues.item(metadata, "name");
		ObjectNode spec = PythonMappings.dict(service.path("spec"), "get");
		if ("ExternalName".equals(spec.path("type").asString(null))) {
			external(PythonMappings.mapping(metadata), name);
			return;
		}
		Optional<String> target = selected(spec.path("selector"), name);
		if (target.isEmpty()) {
			this.log.ignored("Service " + PythonValues.str(name) + " (its selector matches no workload)");
			return;
		}
		this.names.nameIfUnnamed(PythonValues.key(name), target.get());
		if ("LoadBalancer".equals(spec.path("type").asString(null))) {
			this.entries.add(target.get());
		}
	}

	/**
	 * Reads an Ingress as a gateway (Nginx-style) in front of the Services it routes to.
	 * @param ingress the Ingress
	 */
	void readIngress(ObjectNode ingress) {
		JsonNode name = PythonValues.item(PythonValues.item(ingress, "metadata"), "name");
		String gatewayName = PythonValues.matchedText(name);
		String gateway = this.design.uniqueId(gatewayName);
		this.design.add(gateway, "load_balancer", gatewayName,
				JsonNodeFactory.instance.objectNode().put("technology", "nginx"));
		this.log.mapped("Ingress " + PythonValues.str(name) + " -> gateway (Nginx-style)");
		ObjectNode spec = PythonMappings.dict(ingress.path("spec"), "get");
		for (JsonNode rule : PythonValues.items(spec.path("rules"))) {
			JsonNode http = PythonMappings.dict(PythonMappings.mapping(rule).path("http"), "get").path("paths");
			for (JsonNode path : PythonValues.items(http)) {
				JsonNode backend = PythonMappings
					.dict(PythonMappings.dict(PythonMappings.mapping(path).path("backend"), "get").path("service"),
							"get")
					.path("name");
				Optional<String> target = PythonValues.truthy(backend) ? this.names.partId(PythonValues.key(backend))
						: Optional.empty();
				target.ifPresent((routed) -> this.design.link(gateway, routed));
			}
		}
		this.entries.add(gateway);
	}

	/**
	 * Reads an {@code ExternalName} Service as the part an export states, a managed
	 * service or a third-party API.
	 * @param metadata the Service's metadata
	 * @param name its name, as written
	 */
	private void external(ObjectNode metadata, JsonNode name) {
		ObjectNode annotations = PythonMappings.dict(metadata.path("annotations"), "get");
		String serviceName = PythonValues.str(name);
		ExternalPart part = ExternalPart.of(this.design.registry(), annotations, name);
		JsonNode stated = annotations.path("archsim.dev/node");
		String nodeId = this.design.uniqueId(PythonValues.truthy(stated) ? PythonValues.str(stated) : serviceName);
		if (annotations.has("archsim.dev/calls")) {
			this.names.stateCalls(nodeId, PythonValues.str(annotations.get("archsim.dev/calls")));
		}
		ObjectNode config = JsonNodeFactory.instance.objectNode();
		if (part.technology() != null) {
			config.put("technology", part.technology());
		}
		this.design.add(nodeId, part.type(), name, config);
		this.log.mapped("Service " + serviceName + " (ExternalName) -> " + part.described());
		this.names.name(PythonValues.key(name), nodeId);
	}

	/**
	 * The part a Service sends to: the workload whose pod labels its selector matches,
	 * else the workload its selector's {@code app.kubernetes.io/name} or {@code app}
	 * names (or, with no selector, the Service's own name).
	 * @param selector the Service's selector
	 * @param service the Service's name, as written
	 * @return the part; empty when nothing matches
	 */
	private Optional<String> selected(JsonNode selector, JsonNode service) {
		if (!selector.isObject() || selector.isEmpty()) {
			return this.names.partId(PythonValues.key(service));
		}
		Map<String, String> wanted = PythonMappings.texts(selector);
		for (Workload workload : this.objects) {
			Map<String, String> labels = podLabels(workload.object());
			if (wanted.entrySet().stream().allMatch((label) -> label.getValue().equals(labels.get(label.getKey())))) {
				return this.names.partId(workload.name());
			}
		}
		String named = wanted.getOrDefault("app.kubernetes.io/name", "");
		Optional<String> workloadName = named.isEmpty() ? Optional.ofNullable(wanted.get("app")) : Optional.of(named);
		return workloadName.filter(this::isWorkload).flatMap(this.names::partId);
	}

	private boolean isWorkload(String name) {
		return this.objects.stream().anyMatch((workload) -> workload.name().equals(name));
	}

	/**
	 * A workload's pod labels: its template's, then its selector's {@code matchLabels}.
	 * @param workload the workload
	 * @return the labels' text, by name
	 */
	private static Map<String, String> podLabels(ObjectNode workload) {
		ObjectNode spec = PythonMappings.dict(workload.path("spec"), "get");
		ObjectNode metadata = PythonMappings.dict(PythonMappings.dict(spec.path("template"), "get").path("metadata"),
				"get");
		ObjectNode labels = PythonMappings.dictOf(metadata.path("labels"));
		labels.setAll(PythonMappings.dictOf(PythonMappings.dict(spec.path("selector"), "get").path("matchLabels")));
		return PythonMappings.texts(labels);
	}

}
