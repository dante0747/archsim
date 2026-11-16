package io.archsim.engine.parity;

import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the Java's findings word a count as the frozen engine did not: one failed request
 * is {@code 1 request failed}, {@code 1 client request failed}, where Python wrote
 * {@code 1 requests failed} (archsim-engine's README, the CLI's and the MCP server's). A
 * recorded text is read as the Java words it, so every other word of it is still compared
 * exactly.
 */
public final class WordingDepartures {

	/**
	 * A count of exactly one request, not the end of {@code 21} or {@code 1,001}, in the
	 * phrases whose verb is the same for one and many.
	 */
	private static final Pattern ONE_REQUESTS = Pattern
		.compile("(?<![0-9,.])1 (client )?requests (failed|exceeded|hit)\\b");

	private WordingDepartures() {
	}

	/**
	 * A text of the frozen engine's as the Java words it.
	 * @param recorded what Python wrote: a finding, a line the CLI printed
	 * @return the text, a count of one request in the singular
	 */
	public static String asJavaWordsIt(String recorded) {
		// a group that did not match ("client ") appends nothing
		return ONE_REQUESTS.matcher(recorded).replaceAll("1 $1request $2");
	}

	/**
	 * A report of the frozen engine's with its findings' details as the Java words them.
	 * @param recorded Python's report, which is not changed
	 * @return a copy, each finding's detail read by {@link #asJavaWordsIt}
	 */
	public static JsonNode findingsAsJavaWordsThem(JsonNode recorded) {
		JsonNode copy = recorded.deepCopy();
		for (JsonNode entry : copy.path("findings")) {
			JsonNode detail = entry.get("detail");
			if (entry instanceof ObjectNode finding && detail != null && detail.isString()) {
				finding.put("detail", asJavaWordsIt(detail.stringValue()));
			}
		}
		return copy;
	}

}
