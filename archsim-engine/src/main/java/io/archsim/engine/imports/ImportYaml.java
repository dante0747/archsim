package io.archsim.engine.imports;

import java.util.List;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.yaml.InvalidYamlException;
import io.archsim.engine.yaml.YamlDocuments;
import io.archsim.engine.yaml.YamlKeyException;
import io.archsim.engine.yaml.YamlScalarException;
import io.archsim.engine.yaml.YamlValueException;

/**
 * YAML read for an importer, what is wrong with it in the Python importers' words: text
 * that is not YAML refused as {@code not valid YAML: …}, a value Python refuses in
 * Python's words, and a scalar Python fails to read as text that is not the format, a key
 * looked up in vain as {@code missing 'key'}.
 */
final class ImportYaml {

	private ImportYaml() {
	}

	/**
	 * Every document in a text, empty ones as {@code null}.
	 * @param text the text
	 * @return the documents, in order
	 * @throws ImportRefusedException when the text is not YAML, is beyond the bounds or
	 * holds a value Python refuses
	 * @throws ImportShapeException when it holds a scalar Python fails to read
	 */
	static List<JsonNode> all(String text) {
		return read(() -> YamlDocuments.all(text));
	}

	/**
	 * The one document in a text.
	 * @param text the text
	 * @return the document; a JSON null node for an empty text
	 * @throws ImportRefusedException when the text is not YAML, holds several documents,
	 * is beyond the bounds or holds a value Python refuses
	 * @throws ImportShapeException when it holds a scalar Python fails to read
	 */
	static JsonNode one(String text) {
		return read(() -> YamlDocuments.one(text));
	}

	private static <T> T read(Supplier<T> reading) {
		try {
			return reading.get();
		}
		catch (InvalidYamlException ex) {
			throw new ImportRefusedException("not valid YAML: " + ex.getMessage(), ex);
		}
		catch (YamlValueException ex) {
			throw new ImportRefusedException(String.valueOf(ex.getMessage()), ex);
		}
		catch (YamlKeyException ex) {
			throw new ImportShapeException("missing " + ex.getMessage(), ex);
		}
		catch (YamlScalarException ex) {
			throw new ImportShapeException(String.valueOf(ex.getMessage()), ex);
		}
	}

}
