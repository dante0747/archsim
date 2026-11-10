package io.archsim.engine.calibration.prometheus.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.model.text.PythonText;

/**
 * The target of a range query, as the Python engine's {@code HttpPrometheus} writes it:
 * the URL's path without its trailing slashes, then {@code /api/v1/query_range} and the
 * parameters as Python's {@code urlencode} writes them, the numbers in Python's spelling.
 */
final class QueryTargets {

	private static final String QUERY_PATH = "/api/v1/query_range?";

	/** The characters Python's {@code quote_plus} never escapes. */
	private static final String UNESCAPED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-~";

	private static final int LAST_ASCII = 0x7F;

	private static final int BYTE = 0xFF;

	private QueryTargets() {
	}

	/**
	 * The target of a query.
	 * @param url the server's URL, for a refusal
	 * @param path the URL's path
	 * @param query the PromQL query
	 * @param range the range of time it reads
	 * @return the target, its parameters included, as it is sent: a path, even one that
	 * starts with {@code //}, which names no host
	 * @throws PrometheusException when the path holds what Python's {@code http.client}
	 * would not send (a control character, a space, a character beyond ASCII), or what a
	 * URI's path may not hold
	 */
	static String of(String url, String path, String query, Range range) {
		String target = stripTrailingSlashes(path) + QUERY_PATH + "query=" + quotePlus(query) + "&start="
				+ quotePlus(PythonText.repr(range.start())) + "&end=" + quotePlus(PythonText.repr(range.end()))
				+ "&step=" + quotePlus(PythonText.repr(range.step()));
		if (target.chars().anyMatch((character) -> character <= ' ' || character == LAST_ASCII)) {
			throw cannotSend(url, "not a valid HTTP response (InvalidURL)");
		}
		if (target.chars().anyMatch((character) -> character > LAST_ASCII)) {
			throw cannotSend(url, "UnicodeEncodeError");
		}
		try {
			// read as a path after a host, so that a path starting with // is read as one
			new URI("http://host" + target);
			return target;
		}
		catch (URISyntaxException ex) {
			throw cannotSend(url, "not a valid HTTP response (InvalidURL)");
		}
	}

	/**
	 * Python's {@code quote_plus}: UTF-8, every byte but a few escaped, spaces as
	 * {@code +}.
	 * @param text the text
	 * @return the text escaped for a form
	 */
	private static String quotePlus(String text) {
		StringBuilder quoted = new StringBuilder();
		for (byte character : text.getBytes(StandardCharsets.UTF_8)) {
			if (character == ' ') {
				quoted.append('+');
			}
			else if (character > 0 && UNESCAPED.indexOf(character) >= 0) {
				quoted.append((char) character);
			}
			else {
				quoted.append('%').append(String.format(Locale.ROOT, "%02X", character & BYTE));
			}
		}
		return quoted.toString();
	}

	private static String stripTrailingSlashes(String path) {
		int end = path.length();
		while (end > 0 && path.charAt(end - 1) == '/') {
			end--;
		}
		return path.substring(0, end);
	}

	private static PrometheusException cannotSend(String url, String reason) {
		return new PrometheusException("cannot query " + url + ": " + reason);
	}

	/**
	 * The range of time a query reads.
	 *
	 * @param start its start, in seconds since the epoch
	 * @param end its end, in seconds since the epoch
	 * @param step the seconds between points
	 */
	record Range(double start, double end, double step) {

	}

}
