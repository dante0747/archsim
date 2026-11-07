package io.archsim.engine.calibration.mapping;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.yaml.InvalidYamlException;
import io.archsim.engine.yaml.YamlDocuments;
import io.archsim.engine.yaml.YamlKeyException;
import io.archsim.engine.yaml.YamlScalarException;
import io.archsim.engine.yaml.YamlValueException;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;

/**
 * A calibration mapping read from its YAML or JSON text, or from JSON, as the Python
 * engine's {@code load_mapping} reads it. Text is always the mapping itself, never a
 * path. Neither the text nor the values it holds are repeated in what is wrong with it.
 */
public final class MappingLoader {

	private MappingLoader() {
	}

	/**
	 * A mapping from its text.
	 * @param text the mapping, in YAML or JSON
	 * @return the mapping
	 * @throws MappingException when the text is not valid YAML or not a mapping
	 * @throws YamlValueException when the YAML is beyond the bounds, or holds a value
	 * Python refuses
	 * @throws YamlKeyException when it holds a scalar Python looks up in vain
	 * @throws YamlScalarException when it holds another scalar Python fails to read
	 */
	public static CalibrationMapping load(String text) {
		JsonNode data;
		try {
			data = YamlDocuments.one(text);
		}
		catch (InvalidYamlException ex) {
			throw new MappingException("the mapping is not valid YAML: " + ex.description());
		}
		return load(data);
	}

	/**
	 * A mapping from JSON.
	 * @param data the mapping
	 * @return the mapping
	 * @throws MappingException when the JSON is not an object or not a mapping
	 */
	public static CalibrationMapping load(JsonNode data) {
		if (!data.isObject()) {
			throw new MappingException("the mapping must be a YAML or JSON object");
		}
		try {
			return RecordCodec.read(data, CalibrationMapping.class);
		}
		catch (BindingException ex) {
			throw new MappingException("the mapping is not valid: " + ValidationMessages.of(ex));
		}
	}

}
