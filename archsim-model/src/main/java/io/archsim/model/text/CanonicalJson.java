package io.archsim.model.text;

import tools.jackson.databind.JsonNode;

/**
 * JSON text exactly as Python's
 * {@code json.dumps(value, sort_keys=True, separators=(",", ":"))} writes it: keys sorted
 * by code point, no spaces, floats as their repr, and non-ASCII characters either as they
 * are ({@code ensure_ascii=False}) or escaped. Hashes of it (a price snapshot's
 * {@code sha256}, a report's digest) then match the Python engine's.
 */
public final class CanonicalJson {

	private static final PythonJson AS_IS = new PythonJson(PythonJson.Keys.SORTED, PythonJson.Characters.AS_IS);

	private static final PythonJson ASCII = new PythonJson(PythonJson.Keys.SORTED, PythonJson.Characters.ASCII);

	private CanonicalJson() {
	}

	/**
	 * The canonical JSON text of a value, non-ASCII characters as they are
	 * ({@code ensure_ascii=False}).
	 * @param value the JSON value
	 * @return its text, as Python's {@code json.dumps} writes it with these options
	 */
	public static String write(JsonNode value) {
		return AS_IS.text(value);
	}

	/**
	 * The canonical JSON text of a value in printable ASCII, Python's default
	 * ({@code ensure_ascii=True}): every other character is escaped with its four
	 * hexadecimal digits, a character beyond the Basic Multilingual Plane as its two
	 * surrogates.
	 * @param value the JSON value
	 * @return its text, as Python's {@code json.dumps} writes it with these options
	 */
	public static String writeAscii(JsonNode value) {
		return ASCII.text(value);
	}

}
