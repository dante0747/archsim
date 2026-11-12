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

	private static JsonNode gzipped(Path path) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
			return JSON.readTree(in);
		}
	}

}
