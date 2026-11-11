package io.archsim.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.files.PythonFiles;

/**
 * The ready-made designs, as the Python server's {@code _templates} read them: each JSON
 * file of the templates' folder, by its name without {@code .json}, in the order of their
 * names, read again at each call.
 */
final class Templates {

	private static final String SUFFIX = ".json";

	private final @Nullable Path folder;

	/**
	 * The templates of a folder.
	 * @param folder the folder; {@code null} for none
	 */
	Templates(@Nullable Path folder) {
		this.folder = folder;
	}

	/**
	 * Every template.
	 * @return each template's document, by its id, in the order of their files' names
	 */
	Map<String, JsonNode> all() {
		Map<String, JsonNode> templates = new LinkedHashMap<>();
		// as Python's glob found none in a folder that is not there
		if (this.folder == null || !Files.isDirectory(this.folder)) {
			return templates;
		}
		try (Stream<Path> files = Files.list(this.folder)) {
			files.filter((file) -> file.getFileName().toString().endsWith(SUFFIX))
				.sorted(Comparator.comparing(Path::toString))
				.forEach((file) -> templates.put(id(file), read(file)));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return templates;
	}

	/**
	 * A template, as {@code get_template} gave it.
	 * @param id its id
	 * @return its document
	 * @throws ToolRefusal when no template has that id
	 */
	JsonNode named(String id) {
		Map<String, JsonNode> templates = all();
		JsonNode template = templates.get(id);
		if (template == null) {
			throw new ToolRefusal("unknown template '" + id + "'; templates: " + String.join(", ", templates.keySet()));
		}
		return template;
	}

	private static String id(Path file) {
		String name = file.getFileName().toString();
		return name.substring(0, name.length() - SUFFIX.length());
	}

	// as json.loads(path.read_text(encoding="utf-8")) read it
	private static JsonNode read(Path file) {
		return PythonFiles.readJson(file.toString());
	}

}
