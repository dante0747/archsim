package io.archsim.engine.export;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.roundtrip.RoundTrip;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;

/**
 * A docker-compose file of a design: a service per part it deploys, with the images,
 * replicas, resources, links and storage its settings imply and labels for what compose
 * cannot say, and the parts callers reach by address listed apart. Scaffolding to review,
 * not production configuration.
 */
public final class ComposeExport {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final DeployParts parts;

	/**
	 * Writes designs made of a registry's types.
	 * @param registry the component types
	 */
	public ComposeExport(ComponentRegistry registry) {
		this.parts = new DeployParts(registry);
	}

	/**
	 * The design as a docker-compose file.
	 * @param document the design, with the specs it is to be deployed with
	 * @return the file's text, comments first, ending in a newline
	 */
	public String write(ArchitectureDocument document) {
		List<DeployPart> deployed = this.parts.of(document);
		ExportLinks links = ExportLinks.of(document.architecture().edges(), deployed);
		ObjectNode services = NODES.objectNode();
		ArrayNode external = NODES.arrayNode();
		HostPorts hostPorts = new HostPorts();
		for (DeployPart part : deployed) {
			if (part.family() == ComponentFamily.TRAFFIC) {
				continue;
			}
			String calls = RoundTrip.calls(links.from(part));
			if (part.isReachedByAddress()) {
				external.add(externalEntry(part, calls));
				continue;
			}
			List<DeployPart> targets = links.targets(part)
				.stream()
				.filter((target) -> !target.isReachedByAddress())
				.toList();
			services.set(part.name(), new ComposeService(part, calls, targets).toJson(hostPorts));
		}
		return YamlText.comments(comments(document, deployed)) + "\n"
				+ YamlText.write(body(document, services, external)) + "\n";
	}

	private static ObjectNode body(ArchitectureDocument document, ObjectNode services, ArrayNode external) {
		ObjectNode body = NODES.objectNode().put("name", ExportText.slug(document.metadata().name()));
		body.set("services", services);
		ObjectNode volumes = NODES.objectNode();
		for (JsonNode service : services) {
			service.path("volumes").forEach((volume) -> volumes.putObject(volume.stringValue().split(":", -1)[0]));
		}
		if (!volumes.isEmpty()) {
			body.set("volumes", volumes);
		}
		if (!external.isEmpty()) {
			body.set("x-external-dependencies", external);
		}
		ArrayNode notDeployed = RoundTrip.parts(document);
		if (!notDeployed.isEmpty()) {
			body.putObject("x-archsim").set("parts", notDeployed);
		}
		return body;
	}

	private static ObjectNode externalEntry(DeployPart part, String calls) {
		String note = (part.family() == ComponentFamily.EXTERNAL)
				? "third-party API, not deployed; point the callers at its URL"
				: part.deployment().flatMap(TechnologySpec::noteText).orElse("managed service");
		ObjectNode entry = NODES.objectNode()
			.put("id", part.id())
			.put("name", part.name())
			.put("label", part.node().name())
			.put("technology", part.listedTechnology())
			.put("note", note);
		if (!calls.isEmpty()) {
			entry.put("calls", calls);
		}
		return entry;
	}

	private static List<String> comments(ArchitectureDocument document, List<DeployPart> deployed) {
		List<String> lines = new ArrayList<>(
				ExportText.header(document.metadata().name(), "docker-compose scaffolding"));
		Workload workload = document.workload();
		deployed.stream()
			.filter((part) -> part.family() == ComponentFamily.TRAFFIC)
			.findFirst()
			.ifPresent((clients) -> lines
				.add("Modelled load: " + clients.node().name() + " send " + YamlText.number(workload.rps()) + " req/s ("
						+ ExportText.whole(ExportText.percent(workload.readRatio())) + "% reads)."));
		lines.add("App images (your-org/...) are placeholders for your own services.");
		lines.addAll(ExportText.tierLines(deployed));
		return lines;
	}

}
