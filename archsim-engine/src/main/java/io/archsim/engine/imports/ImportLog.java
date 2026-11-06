package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The import report as an importer writes it, line by line: what it mapped, what it
 * guessed and what it ignored, and the links it suggests instead of guessing.
 */
final class ImportLog {

	private final String source;

	private final List<String> mapped = new ArrayList<>();

	private final List<String> guessed = new ArrayList<>();

	private final List<String> ignored = new ArrayList<>();

	private final List<ObjectNode> suggestions = new ArrayList<>();

	/**
	 * A log of an import.
	 * @param source what is imported, such as {@code docker-compose}
	 */
	ImportLog(String source) {
		this.source = source;
	}

	void mapped(String line) {
		this.mapped.add(line);
	}

	void guessed(String line) {
		this.guessed.add(line);
	}

	void ignored(String line) {
		this.ignored.add(line);
	}

	/**
	 * Suggests a link worth confirming.
	 * @param source the part that might call
	 * @param target the part it might call
	 * @param reason why it is a question
	 */
	void suggested(String source, String target, String reason) {
		this.suggestions.add(JsonNodeFactory.instance.objectNode()
			.put("source", source)
			.put("target", target)
			.put("reason", reason));
	}

	/**
	 * The report so far.
	 * @return the report, with no issues
	 */
	ImportReport report() {
		return new ImportReport(this.source, this.mapped, this.guessed, this.ignored, this.suggestions, List.of());
	}

}
