package io.archsim.model.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the editor shows about a component type beyond its specification: the palette text
 * and icon, what the model covers and leaves out, the presets, the links the editor
 * suggests, and each technology's traits and the documentation behind its defaults. It is
 * data, read from {@code io/archsim/model/catalog/<type>.json}.
 *
 * @param description what the type is, in a sentence or two for the palette
 * @param icon the name of the editor's icon for the type
 * @param modeled what the simulation of the type covers, one behaviour per line
 * @param notModeled what it leaves out, one behaviour per line
 * @param presets the technology presets, in the order the editor offers them
 * @param linkDefaults link settings the editor suggests for links drawn from the type
 * @param technologyTraits technology → the behaviours it brings, with their sources
 * @param technologySources field → technology → the documentation behind its default
 */
record Presentation(String description, String icon, List<String> modeled, List<String> notModeled,
		List<Preset> presets, ObjectNode linkDefaults, ObjectNode technologyTraits,
		Map<String, Map<String, String>> technologySources) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Checks that the resource gave every part, and copies the lists.
	 */
	Presentation {
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(icon, "icon");
		Objects.requireNonNull(presets, "presets");
		Objects.requireNonNull(linkDefaults, "linkDefaults");
		Objects.requireNonNull(technologyTraits, "technologyTraits");
		Objects.requireNonNull(technologySources, "technologySources");
		modeled = List.copyOf(modeled);
		notModeled = List.copyOf(notModeled);
		presets = List.copyOf(presets);
		Map<String, Map<String, String>> sources = new LinkedHashMap<>();
		technologySources
			.forEach((field, links) -> sources.put(field, Collections.unmodifiableMap(new LinkedHashMap<>(links))));
		technologySources = Collections.unmodifiableMap(sources);
	}

	/**
	 * Reads the presentation of a component type.
	 * @param type the component type's name, such as {@code cache}
	 * @return its presentation
	 * @throws IllegalStateException when the type has no presentation on the classpath
	 */
	static Presentation of(String type) {
		String resource = type + ".json";
		try (InputStream in = Presentation.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("component type '" + type + "' has no catalog entry: add "
						+ "io/archsim/model/catalog/" + resource);
			}
			return JSON.readValue(in, Presentation.class);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException(
					"the catalog entry of component type '" + type + "' is not valid: " + ex.getOriginalMessage(), ex);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot read the catalog entry of " + type, ex);
		}
	}

}
