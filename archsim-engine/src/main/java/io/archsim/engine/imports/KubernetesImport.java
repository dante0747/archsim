package io.archsim.engine.imports;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.ComponentRegistry;

/**
 * A design from Kubernetes manifests. Deployments, StatefulSets, DaemonSets and Knative
 * Services become parts by their image's full name (listed as guesses); replicas (one pod
 * when unset), autoscalers and resource requests become settings and specs; volume claims
 * become storage; an Ingress becomes the gateway in front of what it routes to;
 * {@code ExternalName} Services become third parties or managed services; a Kubernetes
 * cluster part holds the services, never linked. Links come from environment URLs naming
 * another workload's Service (guesses) or, for ArchSim's own exports, their
 * {@code archsim.dev/calls} annotation.
 */
public final class KubernetesImport {

	/** What the text must be, in the words of a refusal. */
	private static final String SHAPE = "Kubernetes manifests";

	private final ComponentRegistry registry;

	/**
	 * Imports designs made of a registry's types.
	 * @param registry the component types
	 */
	public KubernetesImport(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * A design from Kubernetes manifests, before validation, with the import report.
	 * @param text the manifests, one YAML document each
	 * @param name the design's name; {@code null} or empty for the manifests' part-of
	 * label
	 * @return the design and the report
	 * @throws ImportRefusedException when the text is not YAML, holds no Kubernetes
	 * object, or is not manifests the import can read
	 */
	public ImportedDesign read(String text, @Nullable String name) {
		try {
			return manifests(text, name);
		}
		catch (ImportShapeException ex) {
			throw new ImportRefusedException("this is not " + SHAPE + " the import can read (" + ex.getMessage() + ")",
					ex);
		}
	}

	private ImportedDesign manifests(String text, @Nullable String name) {
		List<ObjectNode> documents = objects(text);
		ImportLog log = new ImportLog(SHAPE);
		DesignBuilder design = new DesignBuilder(this.registry, title(documents, name), SHAPE);
		Manifests manifests = Manifests.byKind(documents, log);
		PartNames names = new PartNames();
		KubernetesWorkloads workloads = new KubernetesWorkloads(design, log, names);
		manifests.workloads().forEach(workloads::read);
		KubernetesServices services = new KubernetesServices(design, log, names, manifests.workloads());
		manifests.services().forEach(services::read);
		Autoscaling autoscaling = new Autoscaling(design, log, names, workloads.unsetReplicas());
		manifests.autoscalers().forEach(autoscaling::read);
		manifests.ingresses().forEach(services::readIngress);
		design.addParts(manifests.parts());
		new KubernetesLinks(design, log, names).link(manifests.workloads());
		design.linkParts(manifests.parts());
		mapParts(log, manifests.parts());
		if (!workloads.onKubernetes().isEmpty() && !ExportedParts.has(manifests.parts(), "kubernetes_cluster")) {
			addCluster(design, log);
		}
		new ImportFinish(design, log).finish();
		if (!ExportedParts.has(manifests.parts(), "traffic_source")) {
			new EntryPoint(design, log, workloads.tools()).addClients(services.entries());
		}
		return new ImportedDesign(design.document(), log.report());
	}

	/**
	 * The objects of manifests: their documents that are mappings.
	 * @param text the manifests
	 * @return the objects, in order
	 * @throws ImportRefusedException when the text is not YAML, or holds no object
	 */
	private static List<ObjectNode> objects(String text) {
		List<ObjectNode> documents = YamlDocuments.all(text)
			.stream()
			.filter(JsonNode::isObject)
			.map(ObjectNode.class::cast)
			.toList();
		if (documents.isEmpty()) {
			throw new ImportRefusedException("no Kubernetes objects found");
		}
		return documents;
	}

	/**
	 * The design's name: the one asked for, else the first part-of label of an object
	 * with metadata, as written.
	 * @param documents the objects
	 * @param name the name asked for
	 * @return the name
	 */
	private static JsonNode title(List<ObjectNode> documents, @Nullable String name) {
		JsonNodeFactory nodes = JsonNodeFactory.instance;
		if (name != null && !name.isEmpty()) {
			return nodes.stringNode(name);
		}
		for (ObjectNode document : documents) {
			if (PythonValues.truthy(document.path("metadata"))) {
				JsonNode partOf = PythonMappings.getMapping(PythonMappings.getMapping(document, "metadata"), "labels")
					.path("app.kubernetes.io/part-of");
				return PythonValues.truthy(partOf) ? partOf : nodes.stringNode("Imported from Kubernetes");
			}
		}
		return nodes.stringNode("Imported from Kubernetes");
	}

	/**
	 * Lists the parts nothing deploys that an export carries.
	 * @param log the report
	 * @param parts the parts
	 */
	private static void mapParts(ImportLog log, List<ObjectNode> parts) {
		if (!parts.isEmpty()) {
			log.mapped("ConfigMap parts.json: " + ExportedParts.ids(parts)
					+ " (parts ArchSim exported but nothing deploys)");
		}
	}

	/**
	 * Adds a cluster part for the services on Kubernetes (a guess).
	 * @param design the design
	 * @param log the report
	 */
	private static void addCluster(DesignBuilder design, ImportLog log) {
		String cluster = design.uniqueId("cluster");
		design.add(cluster, "kubernetes_cluster", "Kubernetes cluster",
				JsonNodeFactory.instance.objectNode().put("technology", "kubernetes"));
		log.guessed("a Kubernetes cluster part with the default node pool (the manifests do not describe nodes)");
	}

}
