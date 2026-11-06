package io.archsim.engine.export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.ComponentFamily;

/**
 * The Kubernetes objects of a part an image runs that is not an application: a data store
 * or a queue as a StatefulSet with its headless Service, a cache or a proxy as a
 * Deployment with its Service.
 */
final class KubernetesInfrastructure {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The port a proxy's Service listens on. */
	private static final int PROXY_PORT = 80;

	/** The storage a data store's volume claims when it sets none, in GiB. */
	private static final double DEFAULT_STORAGE_GIB = 10;

	private KubernetesInfrastructure() {
	}

	/**
	 * The objects of a part an image runs.
	 * @param part the part as Kubernetes objects
	 * @param spec how its technology is deployed
	 * @param calls its outgoing links, as an export writes them
	 * @param dependencies the parts it calls
	 * @return its objects, in order
	 */
	static List<ObjectNode> of(KubernetesPart part, TechnologySpec spec, String calls, List<DeployPart> dependencies) {
		DeployPart deployed = part.deployed();
		boolean proxy = deployed.family() == ComponentFamily.NETWORK;
		ObjectNode container = container(part, spec, proxy ? dependencies : List.of());
		Map<String, JsonNode> technology = Map.of("archsim.dev/technology",
				NODES.stringNode(deployed.technologySetting()));
		if (deployed.family() == ComponentFamily.DATABASE || deployed.family() == ComponentFamily.MESSAGING) {
			ObjectNode metadata = part.metadata(calls, technology, storeAnnotations(deployed, spec));
			return List.of(statefulSet(part, spec, metadata, container), part.headlessService(spec.port()));
		}
		ObjectNode deploymentSpec = NODES.objectNode().put("replicas", 1);
		deploymentSpec.putObject("selector").set("matchLabels", part.selector());
		deploymentSpec.set("template", part.podTemplate(container));
		ObjectNode deployment = NODES.objectNode().put("apiVersion", "apps/v1").put("kind", "Deployment");
		deployment.set("metadata", part.metadata(calls, technology));
		deployment.set("spec", deploymentSpec);
		ObjectNode service = proxy ? part.service(PROXY_PORT, spec.port(), "LoadBalancer")
				: part.service(spec.port(), spec.port(), null);
		return List.of(deployment, service);
	}

	private static ObjectNode container(KubernetesPart part, TechnologySpec spec, List<DeployPart> dependencies) {
		ObjectNode container = NODES.objectNode().put("name", part.deployed().name()).put("image", spec.image());
		container.putArray("ports").addObject().put("containerPort", spec.port());
		ArrayNode environment = part.environment(dependencies);
		if (!environment.isEmpty()) {
			container.set("env", environment);
		}
		if (!spec.command().isEmpty()) {
			ArrayNode arguments = container.putArray("args");
			spec.command().forEach(arguments::add);
		}
		PartSpecs.of(part.deployed().settings())
			.kubernetesResources(null)
			.ifPresent((resources) -> container.set("resources", resources));
		return container;
	}

	/**
	 * What a data store's or a queue's topology is, for a reader of the export.
	 * @param store the data store or queue
	 * @param spec how its technology is deployed
	 * @return its annotations, in order
	 */
	private static Map<String, JsonNode> storeAnnotations(DeployPart store, TechnologySpec spec) {
		Map<String, JsonNode> annotations = new LinkedHashMap<>();
		if (store.instances() > 1) {
			annotations.put("archsim.dev/topology",
					NODES.stringNode("shards " + YamlText.number(store.number("shards", 1)) + ", read replicas "
							+ YamlText.number(store.number("readReplicas", 0)) + ", RF "
							+ YamlText.number(store.number("replicationFactor", 1))
							+ ": use an operator for replication/sharding"));
		}
		if (store.family() == ComponentFamily.MESSAGING) {
			annotations.put("archsim.dev/messaging", NODES.stringNode(ExportText.messaging(store)));
		}
		spec.noteText().ifPresent((note) -> annotations.put("archsim.dev/note", NODES.stringNode(note)));
		return annotations;
	}

	private static ObjectNode statefulSet(KubernetesPart part, TechnologySpec spec, ObjectNode metadata,
			ObjectNode container) {
		DeployPart store = part.deployed();
		container.putArray("volumeMounts").addObject().put("name", "data").put("mountPath", spec.dataDirectory());
		ObjectNode setSpec = NODES.objectNode()
			.put("serviceName", store.name())
			.put("replicas", (store.family() == ComponentFamily.MESSAGING) ? Math.max(1, store.number("brokers", 1))
					: store.instances());
		setSpec.putObject("selector").set("matchLabels", part.selector());
		setSpec.set("template", part.podTemplate(container));
		ObjectNode claim = setSpec.putArray("volumeClaimTemplates").addObject();
		claim.putObject("metadata").put("name", "data");
		ObjectNode claimSpec = claim.putObject("spec");
		claimSpec.putArray("accessModes").add("ReadWriteOnce");
		claimSpec.putObject("resources")
			.putObject("requests")
			.put("storage", PartSpecs.of(store.settings()).kubernetesStorage(DEFAULT_STORAGE_GIB));
		ObjectNode statefulSet = NODES.objectNode().put("apiVersion", "apps/v1").put("kind", "StatefulSet");
		statefulSet.set("metadata", metadata);
		statefulSet.set("spec", setSpec);
		return statefulSet;
	}

}
