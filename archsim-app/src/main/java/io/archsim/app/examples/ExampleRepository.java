package io.archsim.app.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.config.ArchsimProperties;

/**
 * The example designs: the {@code *.json} files of the examples folder, by file name,
 * read once. A file that is not a JSON object is skipped.
 */
@Repository
class ExampleRepository {

	private static final Logger LOGGER = LoggerFactory.getLogger(ExampleRepository.class);

	private static final String SUFFIX = ".json";

	private final Map<String, ObjectNode> examples;

	ExampleRepository(ArchsimProperties properties, JsonMapper json) {
		this.examples = Collections.unmodifiableMap(read(properties.examples(), json));
	}

	/**
	 * Every example, by id (its file name without {@code .json}), in file-name order.
	 * @return the examples
	 */
	Map<String, ObjectNode> all() {
		return this.examples;
	}

	/**
	 * One example.
	 * @param id the example's id
	 * @return its design, a copy, if there is one
	 */
	Optional<ObjectNode> find(String id) {
		return Optional.ofNullable(this.examples.get(id)).map(ObjectNode::deepCopy);
	}

	private static Map<String, ObjectNode> read(Path folder, JsonMapper json) {
		Map<String, ObjectNode> examples = new LinkedHashMap<>();
		for (Path file : designFiles(folder)) {
			String name = file.getFileName().toString();
			try {
				if (json.readTree(file) instanceof ObjectNode design) {
					examples.put(name.substring(0, name.length() - SUFFIX.length()), design);
				}
			}
			catch (JacksonException ex) {
				LOGGER.warn("Skipping the example {}: it is not valid JSON", file, ex);
			}
		}
		return examples;
	}

	private static List<Path> designFiles(Path folder) {
		if (!Files.isDirectory(folder)) {
			LOGGER.warn("No examples: {} is not a folder", folder);
			return List.of();
		}
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> designs = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
			designs.forEach(files::add);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot list the examples in " + folder, ex);
		}
		files.sort(null);
		return files;
	}

}
