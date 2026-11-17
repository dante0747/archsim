package io.archsim.engine.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.CanonicalJson;
import io.archsim.model.text.PythonText;

/**
 * The digest that names a report's content: the SHA-256 of its canonical JSON in ASCII,
 * as the Python engine took it, so a report reads the same digest in either engine.
 */
public final class ReportDigest {

	/** The parts of a report its result's digest covers. */
	private static final List<String> RESULT = List.of("summary", "timeseries", "nodes", "edges", "window");

	private ReportDigest() {
	}

	/**
	 * The digest of a report's result: its summary, time series, parts, links and window,
	 * what the same design and seed give again within an engine version.
	 * @param report the report
	 * @return the digest of those parts alone
	 */
	public static String ofResult(JsonNode report) {
		ObjectNode result = JsonNodeFactory.instance.objectNode();
		RESULT.forEach((part) -> result.set(part, report.get(part)));
		return of(result);
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
