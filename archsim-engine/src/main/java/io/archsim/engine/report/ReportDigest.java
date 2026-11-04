package io.archsim.engine.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import tools.jackson.databind.JsonNode;

import io.archsim.model.text.CanonicalJson;
import io.archsim.model.text.PythonText;

/**
 * The digest that names a report's content: the SHA-256 of its canonical JSON in ASCII,
 * as the Python engine took it, so a report reads the same digest in either engine.
 */
public final class ReportDigest {

	private ReportDigest() {
	}

	/**
	 * The digest of a JSON value.
	 * @param value the value, such as a report's summary
	 * @return the SHA-256 of its canonical ASCII JSON, in lower-case hexadecimal
	 * @throws IllegalArgumentException when the value holds a number that is not finite,
	 * which JSON cannot write
	 */
	public static String of(JsonNode value) {
		requireFinite(value);
		byte[] text = CanonicalJson.writeAscii(value).getBytes(StandardCharsets.US_ASCII);
		return HexFormat.of().formatHex(sha256().digest(text));
	}

	private static void requireFinite(JsonNode value) {
		if (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) {
			throw new IllegalArgumentException(
					"Out of range float values are not JSON compliant: " + PythonText.repr(value.doubleValue()));
		}
		value.valueStream().forEach(ReportDigest::requireFinite);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("every Java platform has SHA-256", ex);
		}
	}

}
