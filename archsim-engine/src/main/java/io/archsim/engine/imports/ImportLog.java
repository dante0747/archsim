package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * The import report as an importer writes it, line by line: what it mapped, what it
 * guessed and what it ignored.
 */
final class ImportLog {

	private final String source;

	private final List<String> mapped = new ArrayList<>();

	private final List<String> guessed = new ArrayList<>();

	private final List<String> ignored = new ArrayList<>();

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
	 * The report so far.
	 * @return the report, with no suggestions or issues
	 */
	ImportReport report() {
		return new ImportReport(this.source, this.mapped, this.guessed, this.ignored, List.of(), List.of());
	}

}
