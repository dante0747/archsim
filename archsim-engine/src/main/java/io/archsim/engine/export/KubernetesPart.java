package io.archsim.engine.export;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One part as Kubernetes objects: what each of its objects shares, its name, labels and
 * annotations, its pods' selector, its Service, and its containers' environment.
 *
 * @param deployed the part
 * @param partOf the name of the design the part belongs to
 */
record KubernetesPart(DeployPart deployed, String partOf) {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The label every object of a part carries with its name. */
	private static final String NAME_LABEL = "app.kubernetes.io/name";

	/**
	 * An object's metadata with no links and no more annotations.
	 * @return the metadata
	 */
	ObjectNode metadata() {
		return metadata("", Map.of(), Map.of());
	}

	/**
	 * An object's metadata with the part's links and more annotations.
	 * @param calls the part's outgoing links, as an export writes them
	 * @param extra more annotations, in order
	 * @return the metadata
	 * @see #metadata(String, Map, Map)
	 */
	ObjectNode metadata(String calls, Map<String, JsonNode> extra) {
		return metadata(calls, extra, Map.of());
	}

	/**
	 * An object's metadata: its name and labels, and the part's id (and outgoing links on
	 * a workload) as annotations, so an import of the manifests gives the design back.
	 * @param calls the part's outgoing links, as an export writes them; empty to leave
	 * them out
	 * @param extra more annotations, in order, before the tier's; a value that is null or
	 * empty text is left out
	 * @param trailing more annotations, in order, after the tier's
	 * @return the metadata
	 */
	ObjectNode metadata(String calls, Map<String, JsonNode> extra, Map<String, JsonNode> trailing) {
		ObjectNode annotations = NODES.objectNode().put("archsim.dev/node", this.deployed.id());
		if (!calls.isEmpty()) {
			annotations.put("archsim.dev/calls", calls);
		}
		extra.forEach((name, value) -> {
			if (!value.isNull() && !value.isMissingNode() && !(value.isString() && value.stringValue().isEmpty())) {
				annotations.set(name, value);
			}
		});
		if (this.deployed.experimental()) {
			annotations.put("archsim/tier", "experimental");
		}
		annotations.setAll(trailing);
		ObjectNode metadata = NODES.objectNode().put("name", this.deployed.name());
		metadata.putObject("labels")
			.put(NAME_LABEL, this.deployed.name())
			.put("app.kubernetes.io/part-of", this.partOf);
		metadata.set("annotations", annotations);
		return metadata;
	}

	/**
	 * The labels that select the part's pods.
	 * @return {@code {app.kubernetes.io/name: name}}
	 */
	ObjectNode selector() {
		return NODES.objectNode().put(NAME_LABEL, this.deployed.name());
	}

	/**
	 * A workload's pods' spec: their labels and one container.
	 * @param container the container
	 * @return {@code {metadata: {labels}, spec: {containers: [container]}}}
	 */
	ObjectNode podTemplate(ObjectNode container) {
		ObjectNode template = NODES.objectNode();
		template.putObject("metadata").set("labels", selector());
		template.putObject("spec").putArray("containers").add(container);
		return template;
	}

	/**
	 * The Service in front of the part's pods.
	 * @param port the port it listens on
	 * @param targetPort the pods' port
	 * @param type its type, such as {@code LoadBalancer}; {@code null} for the default
	 * @return the Service
	 */
	ObjectNode service(int port, int targetPort, @Nullable String type) {
		ObjectNode spec = NODES.objectNode();
		if (type != null) {
			spec.put("type", type);
		}
		return service(spec, port, targetPort);
	}

	private ObjectNode service(ObjectNode spec, int port, int targetPort) {
		spec.set("selector", selector());
		spec.putArray("ports").addObject().put("name", "main").put("port", port).put("targetPort", targetPort);
		ObjectNode service = NODES.objectNode().put("apiVersion", "v1").put("kind", "Service");
		service.set("metadata", metadata());
		service.set("spec", spec);
		return service;
	}

	/**
	 * The headless Service that names a StatefulSet's pods.
	 * @param port the port they listen on
	 * @return the Service
	 */
	ObjectNode headlessService(int port) {
		return service(NODES.objectNode().put("clusterIP", "None"), port, port);
	}

	/**
	 * A container's environment: its technology's variables, then the URL of each part it
	 * calls.
	 * @param dependencies the parts it calls
	 * @return {@code [{name, value}]}; empty when there is none
	 */
	ArrayNode environment(List<DeployPart> dependencies) {
		ArrayNode environment = NODES.arrayNode();
		this.deployed.deployment()
			.ifPresent((spec) -> spec.environment()
				.forEach((name, value) -> environment.addObject().put("name", name).put("value", value)));
		for (DeployPart dependency : dependencies) {
			environment.addObject()
				.put("name", dependency.name().toUpperCase(Locale.ROOT).replace('-', '_') + "_URL")
				.put("value", dependency.url());
		}
		return environment;
	}

}
