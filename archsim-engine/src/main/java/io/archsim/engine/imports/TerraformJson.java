package io.archsim.engine.imports;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.JsonDecodeException;
import io.archsim.engine.text.JsonTooDeepException;
import io.archsim.engine.text.PythonJsonReader;

/**
 * {@code terraform show -json} output read as Python's {@code json.loads} reads it
 * ({@link PythonJsonReader}): any value at the top, {@code NaN} and {@code Infinity} as
 * floats, a key written twice in its first place with its last value, and nothing after
 * the value. Text that is not JSON is refused as the Python importer refused it; where no
 * value starts or something follows it, in {@code json}'s words, else in the parser's.
 */
final class TerraformJson {

	private TerraformJson() {
	}

	/**
	 * A plan's or state's JSON.
	 * @param text the text
	 * @return its value
	 * @throws ImportRefusedException when the text is not JSON, or nests too deeply
	 */
	static JsonNode read(String text) {
		try {
			return PythonJsonReader.read(text);
		}
		catch (JsonTooDeepException ex) {
			throw new ImportRefusedException("the text nests too deeply to read as " + TerraformImport.SHAPE, ex);
		}
		catch (JsonDecodeException ex) {
			throw new ImportRefusedException(
					"not JSON: run `terraform show -json plan.out` (or state) first (" + ex.getMessage() + ")", ex);
		}
	}

}
