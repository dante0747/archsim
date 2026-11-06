package io.archsim.engine.imports;

import java.util.List;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an import mapped, what it guessed (marked assumed), what it ignored, the links it
 * suggests instead of guessing, and the issues to fix before a run.
 *
 * @param source what was imported, such as {@code docker-compose}
 * @param mapped what was read as it was written
 * @param guessed what was guessed, with why
 * @param ignored what was left out
 * @param suggestions links worth confirming, each {@code {source, target, reason}}
 * @param issues what validation reported of the design
 */
public record ImportReport(String source, List<String> mapped, List<String> guessed, List<String> ignored,
		List<ObjectNode> suggestions, List<ObjectNode> issues) {

	/**
	 * Copies the lists, so the record does not change.
	 */
	public ImportReport {
		mapped = List.copyOf(mapped);
		guessed = List.copyOf(guessed);
		ignored = List.copyOf(ignored);
		suggestions = suggestions.stream().map(ObjectNode::deepCopy).toList();
		issues = issues.stream().map(ObjectNode::deepCopy).toList();
	}

	/**
	 * This report with the issues validation found in the design.
	 * @param found the issues, each as JSON
	 * @return a copy of this report with those issues
	 */
	public ImportReport withIssues(List<ObjectNode> found) {
		return new ImportReport(this.source, this.mapped, this.guessed, this.ignored, this.suggestions, found);
	}

	/**
	 * The links worth confirming, as copies: a record does not change.
	 * @return copies of the suggestions, each {@code {source, target, reason}}
	 */
	@Override
	public List<ObjectNode> suggestions() {
		return this.suggestions.stream().map(ObjectNode::deepCopy).toList();
	}

	/**
	 * What validation reported, as copies: a record does not change.
	 * @return copies of the issues
	 */
	@Override
	public List<ObjectNode> issues() {
		return this.issues.stream().map(ObjectNode::deepCopy).toList();
	}

	/**
	 * The report as JSON.
	 * @return {@code {source, mapped, guessed, ignored, suggestions, issues}}
	 */
	public ObjectNode toJson() {
		ObjectNode report = JsonNodeFactory.instance.objectNode().put("source", this.source);
		texts(report.putArray("mapped"), this.mapped);
		texts(report.putArray("guessed"), this.guessed);
		texts(report.putArray("ignored"), this.ignored);
		ArrayNode suggested = report.putArray("suggestions");
		this.suggestions.forEach((suggestion) -> suggested.add(suggestion.deepCopy()));
		ArrayNode reported = report.putArray("issues");
		this.issues.forEach((issue) -> reported.add(issue.deepCopy()));
		return report;
	}

	private static void texts(ArrayNode array, List<String> texts) {
		texts.forEach(array::add);
	}

}
