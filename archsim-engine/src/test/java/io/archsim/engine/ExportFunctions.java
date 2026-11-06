package io.archsim.engine;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.export.AutoSizedDesign;
import io.archsim.engine.export.ComposeExport;
import io.archsim.engine.export.DesignExporter;
import io.archsim.engine.export.ExportFormat;
import io.archsim.engine.export.KubernetesExport;
import io.archsim.engine.export.MermaidExport;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.GoldenEntry;

/** Exporting a design as docker-compose, Kubernetes manifests or a Mermaid diagram. */
final class ExportFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.export.FORMATS", ExportFunctions::formats),
			Map.entry("archsim_engine.export._auto_sized", ExportFunctions::autoSized),
			Map.entry("archsim_engine.export.compose.to_docker_compose", ExportFunctions::toDockerCompose),
			Map.entry("archsim_engine.export.k8s.to_kubernetes", ExportFunctions::toKubernetes),
			Map.entry("archsim_engine.export.mermaid.to_mermaid", ExportFunctions::toMermaid),
			Map.entry("archsim_engine.export.export_document", ExportFunctions::exportDocument));

	private static final DesignExporter EXPORTER = new DesignExporter(StandardComponents.registry(),
			PriceSnapshots.shipped());

	private static final ComposeExport COMPOSE = new ComposeExport(StandardComponents.registry());

	private static final KubernetesExport KUBERNETES = new KubernetesExport(StandardComponents.registry());

	private static final MermaidExport MERMAID = new MermaidExport(StandardComponents.registry());

	private ExportFunctions() {
	}

	/** Each format's suggested file suffix and media type, as a pair. */
	private static JsonNode formats(GoldenEntry entry) {
		ObjectNode formats = GoldenJson.NODES.objectNode();
		Arrays.stream(ExportFormat.values())
			.forEach((format) -> formats.putArray(format.json()).add(format.suffix()).add(format.mediaType()));
		return formats;
	}

	/**
	 * {@code {document, needs}}: the design with its empty specs filled, and CPU needs.
	 */
	private static JsonNode autoSized(GoldenEntry entry) {
		AutoSizedDesign sized = EXPORTER.autoSize((ObjectNode) entry.input("document"));
		ObjectNode output = GoldenJson.NODES.objectNode();
		output.set("document", sized.document());
		ObjectNode needs = output.putObject("needs");
		sized.cpuNeeds().forEach(needs::put);
		return output;
	}

	private static JsonNode toDockerCompose(GoldenEntry entry) {
		return GoldenJson.text(COMPOSE.write(DesignDocuments.parse(entry.input("doc"))));
	}

	private static JsonNode toKubernetes(GoldenEntry entry) {
		Map<String, Double> needs = new LinkedHashMap<>();
		entry.input("needs").properties().forEach((need) -> needs.put(need.getKey(), need.getValue().doubleValue()));
		return GoldenJson.text(KUBERNETES.write(DesignDocuments.parse(entry.input("doc")), needs));
	}

	private static JsonNode toMermaid(GoldenEntry entry) {
		return GoldenJson.text(MERMAID.write(DesignDocuments.parse(entry.input("doc"))));
	}

	/**
	 * Every recorded export auto-sizes the design first, as every caller of the Python
	 * function does, and the Java always does.
	 */
	private static JsonNode exportDocument(GoldenEntry entry) {
		if (!entry.input("autoSize").booleanValue()) {
			throw new IllegalArgumentException("the Java export always auto-sizes the design");
		}
		String name = entry.input("fmt").stringValue();
		ExportFormat format = Arrays.stream(ExportFormat.values())
			.filter((candidate) -> candidate.json().equals(name))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unknown export format " + name));
		return GoldenJson.text(EXPORTER.export(entry.input("document"), format));
	}

}
