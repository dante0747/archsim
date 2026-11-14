package io.archsim.tools.prices;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/**
 * A query string as Python's {@code urllib.parse.urlencode} writes it: each name and
 * value as {@code quote_plus} quotes them, which leaves {@code ~} as it is and quotes
 * {@code *}, where {@link URLEncoder} does the opposite.
 */
final class Queries {

	private Queries() {
	}

	/**
	 * The query of these parameters.
	 * @param parameters the names and their values, in order
	 * @return the query, without its {@code ?}
	 */
	static String of(SequencedMap<String, String> parameters) {
		return parameters.entrySet()
			.stream()
			.map((parameter) -> quotePlus(parameter.getKey()) + "=" + quotePlus(parameter.getValue()))
			.collect(Collectors.joining("&"));
	}

	private static String quotePlus(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("*", "%2A").replace("%7E", "~");
	}

}
