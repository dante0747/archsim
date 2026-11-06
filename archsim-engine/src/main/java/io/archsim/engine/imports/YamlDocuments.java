package io.archsim.engine.imports;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * YAML from sources ArchSim does not control, read as the Python engine read it
 * (ADR-0043): PyYAML's types through SnakeYAML's safe constructor, at most 5 MB, each
 * document measured before it is built, its aliases followed, and refused past 200,000
 * values or 100 levels, or with a recursive alias.
 */
final class YamlDocuments {

	/** The largest text accepted, in UTF-8: the API's request body limit. */
	private static final int MAX_BYTES = 5 * 1024 * 1024;

	/**
	 * The nesting the parser itself allows, above {@link AliasBounds#MAX_DEPTH} so that
	 * the bounds refuse deep documents in their own words.
	 */
	private static final int PARSER_DEPTH_LIMIT = 1000;

	/** How SnakeYAML's parser says a document nests past its limit. */
	private static final String PARSER_TOO_DEEP = "Nesting Depth exceeded";

	private YamlDocuments() {
	}

	/**
	 * Every document in a text, empty ones as {@code null}.
	 * @param text the text
	 * @return the documents, in order
	 * @throws ImportRefusedException when the text is not YAML or is beyond the bounds
	 */
	static List<JsonNode> all(String text) {
		if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			throw new ImportRefusedException("the YAML is larger than 5 MB");
		}
		LoaderOptions options = options();
		PyYamlConstructor constructor = new PyYamlConstructor(options);
		Yaml yaml = new Yaml(constructor, new Representer(new DumperOptions()), new DumperOptions(), options,
				new PyYamlResolver());
		List<JsonNode> documents = new ArrayList<>();
		try {
			for (Node document : yaml.composeAll(new StringReader(text))) {
				AliasBounds.check(document);
				documents.add(YamlTrees.of(constructor.build(document)));
			}
		}
		catch (YAMLException ex) {
			throw refusal(ex);
		}
		return documents;
	}

	/**
	 * The one document in a text.
	 * @param text the text
	 * @return the document; a JSON null node for an empty text
	 * @throws ImportRefusedException when the text is not YAML, holds several documents
	 * or is beyond the bounds
	 */
	static JsonNode one(String text) {
		List<JsonNode> documents = all(text);
		if (documents.size() > 1) {
			throw new ImportRefusedException("not valid YAML: expected one YAML document, found several");
		}
		return documents.isEmpty() ? JsonNodeFactory.instance.nullNode() : documents.getFirst();
	}

	private static LoaderOptions options() {
		LoaderOptions options = new LoaderOptions();
		// the bounds are checked on the composed nodes, in the Python engine's words
		options.setMaxAliasesForCollections(Integer.MAX_VALUE);
		options.setCodePointLimit(Integer.MAX_VALUE);
		options.setNestingDepthLimit(PARSER_DEPTH_LIMIT);
		return options;
	}

	private static ImportRefusedException refusal(YAMLException ex) {
		String message = String.valueOf(ex.getMessage());
		if (message.startsWith(PARSER_TOO_DEEP)) {
			return new ImportRefusedException(AliasBounds.TOO_DEEP, ex);
		}
		return new ImportRefusedException("not valid YAML: " + message, ex);
	}

}
