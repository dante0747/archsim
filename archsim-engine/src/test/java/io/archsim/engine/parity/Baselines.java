package io.archsim.engine.parity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.parity.GoldenFile;

/**
 * The frozen engine's simulation baselines ({@code parity/simulation/}, ADR-0052): each
 * template file of the manifest, gzipped JSON of its cases, each case its runs by seed.
 */
final class Baselines {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private Baselines() {
	}

	/**
	 * The recorded cases, in the manifest's order.
	 * @param folder the baselines' folder
	 * @param only the templates to read; empty for all of them
	 * @return each template case and its runs
	 * @throws IOException when a file cannot be read
	 * @throws IllegalStateException when the folder holds no baselines: the frozen engine
	 * recorded them, and nothing records them again
	 * @throws IllegalArgumentException when a template asked for was not recorded
	 */
	static List<RecordedCase> read(Path folder, List<String> only) throws IOException {
		Path manifest = folder.resolve("manifest.json");
		if (!Files.isRegularFile(manifest)) {
			throw new IllegalStateException("no simulation baselines in " + folder
					+ ": the frozen engine's are in parity/simulation (git checkout HEAD -- parity/simulation)");
		}
		List<RecordedCase> cases = new ArrayList<>();
		Set<String> recorded = new LinkedHashSet<>();
		for (JsonNode file : JSON.readTree(manifest.toFile()).get("files")) {
			JsonNode data = gzipped(folder.resolve(file.get("path").stringValue()));
			String template = data.get("template").stringValue();
			recorded.add(template);
			if (only.isEmpty() || only.contains(template)) {
				data.get("cases")
					.properties()
					.forEach((entry) -> cases
						.add(new RecordedCase(template, entry.getKey(), entry.getValue().valueStream().toList())));
			}
		}
		List<String> unknown = only.stream().filter((template) -> !recorded.contains(template)).toList();
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("not in the baselines: " + String.join(", ", unknown));
		}
		return cases;
	}

	/**
	 * The template's document as the frozen engine ran it when it recorded the baselines:
	 * the one the template's golden file keeps, not today's {@code examples/} file, which
	 * may have moved on since (the SQL Server template's database has 16 vCPU from schema
	 * 1.11, the same 8 cores counted as hyperthreads, where the frozen engine simulated 8
	 * whole cores).
	 * @param template the template's id
	 * @return its document as recorded
	 */
	static JsonNode design(String template) {
		return GoldenFile.read("templates/" + template).at("/document");
	}

	private static JsonNode gzipped(Path path) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
			return JSON.readTree(in);
		}
	}

}
