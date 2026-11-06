package io.archsim.engine.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What ArchSim's exports write as Compose labels ({@code archsim.<name>}) or Kubernetes
 * annotations ({@code archsim.dev/<name>}), read back: the technology a part states, and
 * the settings the exports print in words.
 */
final class ExportLabels {

	private static final Pattern AUTOSCALING = digits(
			"(?<least>\\d+(?:\\.\\d+)?)-(?<most>\\d+(?:\\.\\d+)?) replicas at (?<target>\\d+)% utilization");

	private static final Pattern STORAGE = digits("([0-9.]+) GiB per instance");

	private static final Pattern TOPOLOGY = digits(
			"shards (?<shards>\\d+), read replicas (?<replicas>\\d+), RF (?<factor>\\d+)");

	private static final Pattern MESSAGING = digits("(?<partitions>\\d+) partitions, (?<consumers>\\d+) consumers");

	private static final Pattern RATE_LIMIT = digits("([0-9.]+) req/s");

	private final Map<String, String> labels;

	/**
	 * The labels or annotations of a part.
	 * @param labels each one's text, by name
	 */
	ExportLabels(Map<String, String> labels) {
		this.labels = labels;
	}

	/**
	 * An ArchSim label or annotation.
	 * @param name its name, after {@code archsim.} or {@code archsim.dev/}
	 * @return its text; empty when there is none
	 */
	String label(String name) {
		String compose = this.labels.getOrDefault("archsim." + name, "");
		return compose.isEmpty() ? this.labels.getOrDefault("archsim.dev/" + name, "") : compose;
	}

	/**
	 * Whether an ArchSim export states the part's technology, or states it as generic; a
	 * part that is not an export's states neither.
	 * @return {@code true} when it does
	 */
	boolean statesTechnology() {
		return !label("technology").isEmpty();
	}

	/**
	 * The technology an ArchSim export states.
	 * @return its technology; empty for a part it states as generic, or does not state
	 */
	Optional<String> technology() {
		String technology = label("technology");
		return ("generic".equals(technology) || technology.isEmpty()) ? Optional.empty() : Optional.of(technology);
	}

	/**
	 * Reads the settings an export printed into a part's config.
	 * @param config the part's config, which gets them
	 * @param type the part's type
	 * @return what was read, in words
	 * @throws ImportRefusedException with Python's words for a number that is no number
	 */
	List<String> applyTo(ObjectNode config, String type) {
		List<String> read = new ArrayList<>();
		Matcher autoscaling = AUTOSCALING.matcher(label("autoscaling"));
		if (autoscaling.lookingAt()) {
			config.put("autoscaling", true);
			config.set("minReplicas", truncated(autoscaling.group("least")));
			config.set("maxReplicas", truncated(autoscaling.group("most")));
			config.put("targetUtilization", PythonValues.percent(integer(autoscaling.group("target"))));
			read.add("autoscaling");
		}
		if ("service".equals(type)) {
			applyService(config, read);
		}
		Matcher storage = STORAGE.matcher(label("storage"));
		if (storage.lookingAt()) {
			config.put("storageGib", PythonValues.toFloat(text(storage.group(1))));
			read.add("storage");
		}
		applyStore(config, read);
		Matcher rateLimit = RATE_LIMIT.matcher(label("rate-limit"));
		if (rateLimit.lookingAt()) {
			config.put("rateLimitRps", PythonValues.toFloat(text(rateLimit.group(1))));
			read.add("rate limit");
		}
		return read;
	}

	private void applyService(ObjectNode config, List<String> read) {
		if (!label("platform").isEmpty()) {
			config.put("platform", label("platform"));
			read.add("platform");
		}
		if (!label("runtime").isEmpty()) {
			config.put("technology", label("runtime"));
			read.add("runtime");
		}
	}

	private void applyStore(ObjectNode config, List<String> read) {
		Matcher topology = TOPOLOGY.matcher(label("topology"));
		if (topology.find()) {
			config.set("shards", integer(topology.group("shards")));
			config.set("readReplicas", integer(topology.group("replicas")));
			config.set("replicationFactor", integer(topology.group("factor")));
			read.add("topology");
		}
		Matcher messaging = MESSAGING.matcher(label("messaging"));
		if (messaging.lookingAt()) {
			config.set("partitions", integer(messaging.group("partitions")));
			config.set("consumers", integer(messaging.group("consumers")));
			read.add("partitions and consumers");
		}
	}

	/**
	 * {@code int(digits)}.
	 * @param digits the digits
	 * @return the whole number
	 */
	private static JsonNode integer(String digits) {
		return PythonValues.number(PythonValues.toInt(text(digits)));
	}

	/**
	 * {@code int(float(digits))}.
	 * @param digits the digits
	 * @return the whole number, a fraction truncated
	 */
	private static JsonNode truncated(String digits) {
		return PythonValues
			.number(PythonValues.toInt(JsonNodeFactory.instance.numberNode(PythonValues.toFloat(text(digits)))));
	}

	private static JsonNode text(String text) {
		return JsonNodeFactory.instance.stringNode(text);
	}

	/**
	 * A pattern whose {@code \d} matches any script's digits, as Python's does.
	 * @param pattern the pattern
	 * @return the pattern, compiled
	 */
	private static Pattern digits(String pattern) {
		return Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS);
	}

}
