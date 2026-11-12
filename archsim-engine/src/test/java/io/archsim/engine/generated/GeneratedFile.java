package io.archsim.engine.generated;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.text.PythonJson;
import io.archsim.parity.Repository;

/**
 * The repository's files the engine generates, so that what reads them reads the engine's
 * own output: the web app's engine fixtures, each written as Python's
 * {@code json.dumps(value, indent=2)} wrote it. {@link GeneratedFileTests} holds the
 * committed files to them; {@code ./gradlew :archsim-engine:generate} writes them
 * ({@link #main()}).
 */
enum GeneratedFile {

	/** The component catalog, as {@code GET /api/v1/components} answers it. */
	CATALOG("apps/web/src/test/fixtures/catalog.json") {

		@Override
		JsonNode content() {
			return ComponentCatalog.of(StandardComponents.registry());
		}

	},

	/** The starter fixture's analysis, as {@code POST /api/v1/analyze} answers it. */
	STARTER_ANALYSIS("apps/web/src/test/fixtures/analysis-starter.json") {

		@Override
		JsonNode content() {
			JsonNode starter = JSON.readTree(Repository.root().resolve(STARTER).toFile());
			return new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped()).analyze(starter, null)
				.toJson();
		}

	};

	/**
	 * The design the web app's tests start from, which {@link #STARTER_ANALYSIS}
	 * analyzes.
	 */
	private static final String STARTER = "apps/web/src/test/fixtures/starter.json";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final System.Logger LOG = System.getLogger(GeneratedFile.class.getName());

	private static final int INDENT = 2;

	private final String path;

	GeneratedFile(String path) {
		this.path = path;
	}

	/**
	 * Writes every generated file into the repository whose root {@code archsim.repoRoot}
	 * names.
	 * @throws IOException when a file cannot be written
	 */
	static void main() throws IOException {
		for (GeneratedFile file : values()) {
			Files.writeString(file.target(), file.text());
			LOG.log(Level.INFO, "wrote {0}", file.path);
		}
	}

	/**
	 * Where the file is.
	 * @return its path in the repository
	 */
	Path target() {
		return Repository.root().resolve(this.path);
	}

	/**
	 * What the file holds.
	 * @return the engine's output, written as Python's {@code json.dumps} with an indent
	 * of 2, and a final newline
	 */
	String text() {
		return PythonJson.writeIndented(content(), INDENT) + "\n";
	}

	/**
	 * What the file holds, as JSON.
	 * @return the engine's output
	 */
	abstract JsonNode content();

}
