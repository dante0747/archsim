package io.archsim.engine.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.roundtrip.RoundTrip;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.text.PythonJson;

/**
 * Kubernetes manifests of a design, one YAML document each: the workloads, Services and
 * autoscalers of the parts it deploys, an ExternalName Service for each part callers
 * reach by address, and a ConfigMap of the parts nothing deploys, for importing the
 * manifests back. Scaffolding to review, not production configuration.
 */
public final class KubernetesExport {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** What the ConfigMap of the parts nothing deploys says it is. */
	private static final String CONFIG_MAP_NOTE = "ArchSim parts nothing deploys (clients, clusters), for importing "
			+ "these manifests back; safe to delete";

	private final DeployParts parts;

	/**
	 * Writes designs made of a registry's types.
	 * @param registry the component types
	 */
	public KubernetesExport(ComponentRegistry registry) {
		this.parts = new DeployParts(registry);
	}

	/**
	 * The design as Kubernetes manifests.
	 * @param document the design, with the specs it is to be deployed with
	 * @param cpuNeeds the CPU each part whose CPU was auto-sized needs, by its id: its
	 * container requests that, below the cores it is limited to
	 * @return the manifests' text, comments first, ending in a newline
	 */
	public String write(ArchitectureDocument document, Map<String, Double> cpuNeeds) {
		String partOf = ExportText.slug(document.metadata().name());
		List<DeployPart> deployed = this.parts.of(document);
		ExportLinks links = ExportLinks.of(document.architecture().edges(), deployed);
		List<ObjectNode> manifests = new ArrayList<>();
		for (DeployPart part : deployed) {
			if (part.family() == ComponentFamily.TRAFFIC) {
				continue;
			}
			manifests.addAll(objects(new KubernetesPart(part, partOf), links, cpuNeeds.get(part.id())));
		}
		ArrayNode notDeployed = RoundTrip.parts(document);
		if (!notDeployed.isEmpty()) {
			manifests.add(configMap(partOf, notDeployed));
		}
		String body = manifests.stream()
			.map((manifest) -> "---\n" + YamlText.write(manifest))
			.collect(Collectors.joining("\n"));
		return YamlText.comments(comments(document, deployed)) + "\n" + body + "\n";
	}

	/**
	 * The objects of a part: an ExternalName Service for a part callers reach by address,
	 * an application's workload, or the workload its technology's image runs.
	 * @param part the part as Kubernetes objects
	 * @param links the links between the parts deployed
	 * @param cpuNeed the CPU it needs when it was auto-sized; {@code null} otherwise
	 * @return its objects, in order; none for a part whose technology has no image
	 */
	private static List<ObjectNode> objects(KubernetesPart part, ExportLinks links, @Nullable Double cpuNeed) {
		DeployPart deployed = part.deployed();
		String calls = RoundTrip.calls(links.from(deployed));
		if (deployed.isReachedByAddress()) {
			return List.of(externalName(part, calls));
		}
		if (deployed.family() == ComponentFamily.COMPUTE) {
			return KubernetesApplications.of(part, calls, links.targets(deployed), cpuNeed);
		}
		return deployed.deployment()
			.map((spec) -> KubernetesInfrastructure.of(part, spec, calls, links.targets(deployed)))
			.orElse(List.of());
	}

	/**
	 * A Service callers resolve a managed service or a third-party API by.
	 * @param part the part as Kubernetes objects
	 * @param calls its outgoing links, as an export writes them
	 * @return the Service, its external name a placeholder to replace
	 */
	private static ObjectNode externalName(KubernetesPart part, String calls) {
		DeployPart deployed = part.deployed();
		Map<String, JsonNode> facts = new LinkedHashMap<>();
		facts.put("archsim.dev/type", NODES.stringNode(deployed.node().type()));
		facts.put("archsim.dev/technology", NODES.stringNode(deployed.technologySetting()));
		String note = (deployed.family() == ComponentFamily.EXTERNAL)
				? "third-party API, replace externalName with its host"
				: deployed.deployment().flatMap(TechnologySpec::noteText).orElse("managed service");
		ObjectNode service = NODES.objectNode().put("apiVersion", "v1").put("kind", "Service");
		service.set("metadata", part.metadata(calls, facts, Map.of("archsim.dev/note", NODES.stringNode(note))));
		service.putObject("spec").put("type", "ExternalName").put("externalName", deployed.name() + ".example.com");
		return service;
	}

	private static ObjectNode configMap(String partOf, ArrayNode notDeployed) {
		ObjectNode metadata = NODES.objectNode().put("name", partOf + "-archsim");
		metadata.putObject("labels").put("app.kubernetes.io/part-of", partOf);
		metadata.putObject("annotations").put("archsim.dev/note", CONFIG_MAP_NOTE);
		ObjectNode configMap = NODES.objectNode().put("apiVersion", "v1").put("kind", "ConfigMap");
		configMap.set("metadata", metadata);
		configMap.putObject("data").put("parts.json", PythonJson.write(notDeployed));
		return configMap;
	}

	private static List<String> comments(ArchitectureDocument document, List<DeployPart> deployed) {
		List<String> lines = new ArrayList<>(ExportText.header(document.metadata().name(), "Kubernetes manifests"));
		lines.add("Apply into a namespace of your choice: kubectl apply -n <namespace> -f <file>.yaml");
		lines.add("Serverless services use Knative Serving; StatefulSets need a StorageClass.");
		lines.addAll(ExportText.tierLines(deployed));
		return lines;
	}

}
