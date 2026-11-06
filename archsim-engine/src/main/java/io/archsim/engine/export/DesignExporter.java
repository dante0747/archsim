package io.archsim.engine.export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.sizing.PartSummary;
import io.archsim.engine.sizing.SizingSummary;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * A design exported as docker-compose, Kubernetes manifests or a Mermaid diagram, with
 * its empty CPU, memory and storage specs filled with its auto sizes first, as a run
 * would simulate it, so the exported resources match what was simulated.
 */
public final class DesignExporter {

	/** The spec of a part's CPU cores. */
	private static final String CPU = "cpuCores";

	/** The specs auto-sizing fills, by their JSON names. */
	private static final List<String> SPECS = List.of(CPU, "memoryGib", "storageGib");

	private final DesignAnalyzer analyzer;

	private final ComposeExport compose;

	private final KubernetesExport kubernetes;

	private final MermaidExport mermaid;

	/**
	 * Exports designs made of a registry's types.
	 * @param registry the component types
	 * @param prices the price snapshots auto-sizing picks instance types from
	 */
	public DesignExporter(ComponentRegistry registry, PriceSnapshots prices) {
		this.analyzer = new DesignAnalyzer(registry, prices);
		this.compose = new ComposeExport(registry);
		this.kubernetes = new KubernetesExport(registry);
		this.mermaid = new MermaidExport(registry);
	}

	/**
	 * A design as text in a format, auto-sized first.
	 * @param raw the design, as the request carries it
	 * @param format what to write it as
	 * @return the text
	 * @throws DocumentInvalidException when the design cannot be read or resolved
	 */
	public String export(JsonNode raw, ExportFormat format) {
		AutoSizedDesign sized = autoSize(DesignDocuments.toJson(DesignDocuments.load(raw)));
		ArchitectureDocument document = DesignDocuments.parse(sized.document());
		return switch (format) {
			case COMPOSE -> this.compose.write(document);
			case K8S -> this.kubernetes.write(document, sized.cpuNeeds());
			case MERMAID -> this.mermaid.write(document);
		};
	}

	/**
	 * A design with its empty specs filled with its auto sizes, as its analysis works
	 * them out, and the CPU each part whose CPU was auto-sized needs: its cores at the
	 * target utilization, a fraction of a core for a light part. A spec counts as set
	 * when it is any number, a boolean included, as the Python engine counted it.
	 * @param raw the design
	 * @return a copy of the design, auto-sized, with the needs
	 * @throws DocumentInvalidException when the design cannot be read or resolved
	 */
	public AutoSizedDesign autoSize(ObjectNode raw) {
		ObjectNode document = raw.deepCopy();
		SizingSummary sizing = this.analyzer.analyze(document, null).sizing();
		Map<String, Double> needs = new LinkedHashMap<>();
		if (!sizing.auto()) {
			return new AutoSizedDesign(document, needs);
		}
		for (JsonNode node : document.path("architecture").path("nodes")) {
			PartSummary part = sizing.nodes().get(node.path("id").stringValue());
			if (part != null && !isSkipped(part)) {
				fill((ObjectNode) node, part, needs);
			}
		}
		return new AutoSizedDesign(document, needs);
	}

	/**
	 * Fills a part's empty specs with its auto sizes, and notes its CPU need when its CPU
	 * is not set.
	 * @param node the part, given a config when it has none
	 * @param part its sizing summary
	 * @param needs the CPU needs, by part id, to which its need is added
	 */
	private static void fill(ObjectNode node, PartSummary part, Map<String, Double> needs) {
		ObjectNode config = node.has("config") ? (ObjectNode) node.get("config") : node.putObject("config");
		Map<String, Double> need = part.need();
		if (need != null && need.get(CPU) != null && !isSet(config.path(CPU))) {
			needs.put(node.path("id").stringValue(), need.get(CPU));
		}
		for (String spec : SPECS) {
			Double size = part.auto().get(spec);
			if (size != null && !isSet(config.path(spec))) {
				config.put(spec, size);
			}
		}
	}

	private static boolean isSkipped(PartSummary part) {
		String skipped = part.skipped();
		return skipped != null && !skipped.isEmpty();
	}

	private static boolean isSet(JsonNode spec) {
		return spec.isNumber() || spec.isBoolean();
	}

}
