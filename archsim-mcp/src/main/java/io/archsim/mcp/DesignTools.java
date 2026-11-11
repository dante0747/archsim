package io.archsim.mcp;

import java.util.Arrays;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.export.DesignExporter;
import io.archsim.engine.export.ExportFormat;
import io.archsim.engine.imports.DesignImporter;
import io.archsim.engine.imports.ImportFormat;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.validation.DocumentValidator;

/**
 * The tools that work out a design without simulating it: {@code validate_design},
 * {@code analyze_design}, {@code export} and {@code import_design}, as the Python server
 * answered them.
 */
final class DesignTools {

	/**
	 * {@code validate_design}: whether the document can be run, and each issue.
	 * @param arguments {@code document}
	 * @return {@code {valid, issues}}
	 */
	JsonNode validate(Arguments arguments) {
		JsonNode document = arguments.document();
		try {
			return DocumentValidator.standard().validate(DesignDocuments.load(document)).toJson();
		}
		catch (DocumentInvalidException ex) {
			ObjectNode refused = JsonNodeFactory.instance.objectNode().put("valid", false);
			ArrayNode issues = refused.putArray("issues");
			ex.issues().stream().map(Issue::toJson).forEach(issues::add);
			return refused;
		}
	}

	/**
	 * {@code analyze_design}: the auto sizes, cost, review, technologies, inputs, instant
	 * estimate and confidence, without simulating.
	 * @param arguments {@code document}, {@code scenario_id}
	 * @return the analysis, as the Python server cut it down
	 * @throws ToolRefusal when the document cannot be used
	 */
	JsonNode analyze(Arguments arguments) {
		JsonNode analysis;
		try {
			analysis = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped())
				.analyze(arguments.document(), arguments.text("scenario_id"))
				.toJson();
		}
		catch (DocumentInvalidException ex) {
			throw ToolRefusal.unusable(ex);
		}
		ObjectNode sizes = JsonNodeFactory.instance.objectNode();
		analysis.at("/sizing/nodes").properties().forEach((node) -> {
			if (!node.getValue().path("skipped").asBoolean()) {
				ObjectNode sized = sizes.putObject(node.getKey());
				node.getValue().path("auto").properties().forEach((size) -> sized.set(size.getKey(), size.getValue()));
				sized.set("why", JsonPicks.value(node.getValue().path("why")));
			}
		});
		JsonNode cost = analysis.path("cost");
		return JsonNodeFactory.instance.objectNode()
			.set("peakRps", JsonPicks.value(analysis.path("peakRps")))
			.set("sizes", sizes)
			.set("monthlyCostUsd", RunSummaries.costOf(cost))
			.set("review",
					JsonPicks.pickEach(analysis.path("review"), "severity", "title", "detail", "suggestion", "nodeIds"))
			.set("technologies", JsonPicks.value(analysis.path("technologies")))
			.set("inputs", JsonPicks.value(analysis.path("provenanceCounts")))
			.set("estimate", RunSummaries.estimate(analysis.path("estimate")))
			.set("confidence", RunSummaries.confidence(analysis.path("confidence")));
	}

	/**
	 * {@code export}: docker-compose, Kubernetes manifests or a Mermaid diagram of the
	 * design.
	 * @param arguments {@code document}, {@code format}
	 * @return the text
	 * @throws ToolRefusal when the document cannot be used
	 */
	JsonNode export(Arguments arguments) {
		// the input schema allows only the formats there are
		String named = arguments.required("format");
		ExportFormat format = Arrays.stream(ExportFormat.values())
			.filter((candidate) -> candidate.json().equals(named))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("the input schema allows the format " + named));
		try {
			return JsonNodeFactory.instance
				.stringNode(new DesignExporter(StandardComponents.registry(), PriceSnapshots.shipped())
					.export(arguments.document(), format));
		}
		catch (DocumentInvalidException ex) {
			throw ToolRefusal.unusable(ex);
		}
	}

	/**
	 * {@code import_design}: a design from a docker-compose file, Kubernetes manifests or
	 * a Terraform plan, with the import's report.
	 * @param arguments {@code format}, {@code text}, {@code name}
	 * @return {@code {document, report}}
	 * @throws ToolRefusal when the text cannot be imported
	 */
	JsonNode importDesign(Arguments arguments) {
		try {
			return new DesignImporter(StandardComponents.registry(), PriceSnapshots.shipped())
				.importDesign(ImportFormat.named(arguments.required("format")), arguments.required("text"),
						arguments.text("name"))
				.toJson();
		}
		catch (DocumentInvalidException ex) {
			throw ToolRefusal.unusable(ex);
		}
	}

}
