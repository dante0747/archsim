package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.roundtrip.RoundTrip;

/**
 * Kubernetes objects by what the import makes of them: workloads, Services, autoscalers,
 * Ingresses, and the parts an ArchSim export's ConfigMap carries; any other object is
 * listed as ignored.
 *
 * @param workloads the workloads, by name, the last object of a name winning in its place
 * @param services the Services that are not Knative Services
 * @param autoscalers the HorizontalPodAutoscalers
 * @param ingresses the Ingresses
 * @param parts the parts nothing deploys (clients, clusters) an export carries
 */
record Manifests(List<Workload> workloads, List<ObjectNode> services, List<ObjectNode> autoscalers,
		List<ObjectNode> ingresses, List<ObjectNode> parts) {

	/** The kinds of object that run pods, besides a Knative Service. */
	private static final Set<String> WORKLOAD_KINDS = Set.of("Deployment", "StatefulSet", "DaemonSet");

	/**
	 * Copies the lists, so the record does not change.
	 */
	Manifests {
		workloads = List.copyOf(workloads);
		services = List.copyOf(services);
		autoscalers = List.copyOf(autoscalers);
		ingresses = List.copyOf(ingresses);
		parts = List.copyOf(parts);
	}

	/**
	 * Objects by what the import makes of them.
	 * @param documents the objects, in order
	 * @param log the report, which lists what is ignored
	 * @return the objects, by kind, each kind in order
	 */
	static Manifests byKind(List<ObjectNode> documents, ImportLog log) {
		Map<String, Workload> workloads = new LinkedHashMap<>();
		List<ObjectNode> services = new ArrayList<>();
		List<ObjectNode> autoscalers = new ArrayList<>();
		List<ObjectNode> ingresses = new ArrayList<>();
		List<ObjectNode> parts = new ArrayList<>();
		for (ObjectNode document : documents) {
			JsonNode kind = document.path("kind");
			if (isWorkload(kind, document.has("apiVersion") ? PythonValues.str(document.get("apiVersion")) : "")) {
				JsonNode name = PythonValues.item(PythonValues.item(document, "metadata"), "name");
				workloads.put(PythonValues.key(name), new Workload(name, document));
			}
			else if (is(kind, "Service")) {
				services.add(document);
			}
			else if (is(kind, "HorizontalPodAutoscaler")) {
				autoscalers.add(document);
			}
			else if (is(kind, "Ingress")) {
				ingresses.add(document);
			}
			else if (is(kind, "ConfigMap") && PythonValues.contains(document.path("data"), "parts.json")) {
				parts.addAll(RoundTrip.parseParts(PythonValues.item(document.path("data"), "parts.json")));
			}
			else {
				log.ignored(ignored(document, kind));
			}
		}
		return new Manifests(List.copyOf(workloads.values()), services, autoscalers, ingresses, parts);
	}

	private static boolean isWorkload(JsonNode kind, String api) {
		if (is(kind, "Service")) {
			return api.startsWith("serving.knative.dev");
		}
		return kind.isString() && WORKLOAD_KINDS.contains(kind.stringValue());
	}

	private static boolean is(JsonNode kind, String expected) {
		return kind.isString() && kind.stringValue().equals(expected);
	}

	/**
	 * An object the import ignores, as the report lists it.
	 * @param document the object
	 * @param kind its kind
	 * @return {@code <kind> <name>}
	 */
	private static String ignored(ObjectNode document, JsonNode kind) {
		JsonNode name = PythonMappings.getMapping(document, "metadata").path("name");
		String named = name.isMissingNode() ? "" : PythonValues.str(name);
		return (PythonValues.str(kind) + " " + named).strip();
	}

	/**
	 * A workload: a Deployment, StatefulSet, DaemonSet or Knative Service.
	 *
	 * @param nameNode its name, as written
	 * @param object the object
	 */
	record Workload(JsonNode nameNode, ObjectNode object) {

		/** Its name, as the import keys it. */
		String name() {
			return PythonValues.str(this.nameNode);
		}

		/**
		 * Its first container.
		 * @return the container; an empty mapping when there is none
		 * @throws ImportShapeException when the first is not a mapping
		 */
		ObjectNode container() {
			ObjectNode spec = PythonMappings.dict(this.object.path("spec"), "get");
			JsonNode containers = PythonMappings
				.dict(PythonMappings.dict(spec.path("template"), "get").path("spec"), "get")
				.path("containers");
			return PythonValues.truthy(containers) ? PythonMappings.mapping(PythonValues.first(containers))
					: JsonNodeFactory.instance.objectNode();
		}

	}

}
