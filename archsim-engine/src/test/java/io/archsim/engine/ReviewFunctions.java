package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.review.DesignReview;
import io.archsim.engine.review.Finding;
import io.archsim.engine.review.ReviewRule;
import io.archsim.engine.review.SeverityCounts;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenEntry;

/**
 * The design review: its findings, their counts by severity, and the rules' titles.
 */
final class ReviewFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.review.review_design", ReviewFunctions::reviewDesign),
			Map.entry("archsim_engine.review.count_by_severity", ReviewFunctions::countBySeverity),
			Map.entry("archsim_engine.review.REVIEW_RULES", (entry) -> titles()));

	private static final DesignReview REVIEW = new DesignReview(StandardComponents.registry());

	private ReviewFunctions() {
	}

	private static JsonNode reviewDesign(GoldenEntry entry) {
		ArrayNode output = GoldenJson.NODES.arrayNode();
		REVIEW.review(DesignDocuments.parse(entry.input("doc")))
			.forEach((finding) -> output.add(RecordCodec.write(finding)));
		return output;
	}

	private static JsonNode countBySeverity(GoldenEntry entry) {
		List<Finding> findings = new ArrayList<>();
		entry.input("findings").forEach((finding) -> findings.add(RecordCodec.read(finding, Finding.class)));
		return RecordCodec.write(SeverityCounts.of(findings));
	}

	private static JsonNode titles() {
		ObjectNode titles = GoldenJson.NODES.objectNode();
		ReviewRule.titles().forEach(titles::put);
		return titles;
	}

}
