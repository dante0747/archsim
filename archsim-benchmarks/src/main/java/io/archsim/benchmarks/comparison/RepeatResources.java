package io.archsim.benchmarks.comparison;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The CPU and memory of each container in a repeat's measured window, and a step's from
 * its repeats.
 *
 * <p>
 * Repeats measured since the window was sampled carry it. Older ones have only
 * {@code containerCpuShare}, CPU over the whole k6 command, whose container start and
 * stop add idle seconds that vary by step; those are scaled to the window by the
 * service's own CPU: busy cores = the service's CPU per request x the rate k6 sent x (the
 * container's share / the service container's share). That assumes each container idles
 * while k6 starts and stops; the Kafka broker does not, so its figure reads high.
 */
public final class RepeatResources {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The containers whose own CPU counter scales the older repeats. */
	private static final List<String> SERVICES = List.of("api", "producer");

	private static final List<String> KEYS = List.of("cores", "maxCores", "workingSetMiB", "anonMiB");

	private static final JsonNode WINDOW_S = PythonValues.integer(Protocol.DURATION_S);

	private static final JsonNode MS_PER_S = PythonValues.integer(1000);

	private static final int DECIMALS = 4;

	private RepeatResources() {
	}

	/**
	 * One repeat's CPU and memory per container.
	 * @param repeat the repeat
	 * @return what it sampled, or its CPU scaled from the whole k6 command; empty when it
	 * recorded neither
	 */
	public static Optional<JsonNode> of(JsonNode repeat) {
		JsonNode sampled = PythonValues.get(repeat, "resources");
		if (PythonText.truthy(sampled)) {
			return Optional.of(sampled);
		}
		JsonNode shares = PythonValues.get(repeat, "containerCpuShare");
		Optional<String> service = SERVICES.stream()
			.filter((name) -> shares.isObject() && PythonText.truthy(PythonValues.get(shares, name)))
			.findFirst();
		JsonNode perRequest = PythonValues.get(repeat, "serviceCpuMsPerRequest");
		JsonNode sent = PythonValues.get(repeat, "sent");
		if (service.isEmpty() || !PythonText.truthy(perRequest) || !PythonText.truthy(sent)) {
			return Optional.empty();
		}
		return Optional.of(scaled(shares, scale(perRequest, sent, shares.get(service.get()))));
	}

	// the service's CPU per request x the rate k6 sent / the service's share, in cores
	private static JsonNode scale(JsonNode perRequest, JsonNode sent, JsonNode serviceShare) {
		JsonNode perSecond = PythonValues.divide(PythonValues.multiply(perRequest, sent), WINDOW_S);
		return PythonValues.divide(PythonValues.divide(perSecond, MS_PER_S), serviceShare);
	}

	private static ObjectNode scaled(JsonNode shares, JsonNode scale) {
		ObjectNode scaled = NODES.objectNode().put("method", "scaled");
		ObjectNode containers = scaled.putObject("containers");
		shares.properties()
			.stream()
			.filter((share) -> !PythonValues.isNone(share.getValue()))
			.forEach((share) -> containers.putObject(share.getKey())
				.set("cores", PythonValues.round(PythonValues.multiply(share.getValue(), scale), DECIMALS)));
		return scaled;
	}

	/**
	 * A step's CPU and memory per container: the median of its repeats'.
	 * @param repeats the step's repeats
	 * @return how they were measured, the repeats that measured them, and each
	 * container's medians; empty when no repeat recorded any
	 */
	static Optional<ObjectNode> aggregate(List<? extends JsonNode> repeats) {
		List<JsonNode> measured = repeats.stream().map(RepeatResources::of).flatMap(Optional::stream).toList();
		if (measured.isEmpty()) {
			return Optional.empty();
		}
		Set<String> methods = new TreeSet<>();
		Set<String> names = new LinkedHashSet<>();
		measured.forEach((resources) -> {
			methods.add(resources.get("method").stringValue());
			names.addAll(resources.get("containers").propertyNames());
		});
		ObjectNode step = NODES.objectNode()
			.put("method", (methods.size() == 1) ? methods.iterator().next() : "mixed")
			.put("repeats", measured.size());
		ObjectNode containers = step.putObject("containers");
		names.forEach((name) -> medians(measured, name).ifPresent((kept) -> containers.set(name, kept)));
		return Optional.of(step);
	}

	private static Optional<ObjectNode> medians(List<JsonNode> measured, String container) {
		ObjectNode kept = NODES.objectNode();
		for (String key : KEYS) {
			JsonNode median = PythonValues.median(measured.stream()
				.map((resources) -> resources.get("containers").path(container))
				.map((readings) -> readings.isObject() ? PythonValues.get(readings, key) : PythonValues.none())
				.toList());
			if (!PythonValues.isNone(median)) {
				kept.set(key, PythonValues.round(median, DECIMALS));
			}
		}
		return kept.isEmpty() ? Optional.empty() : Optional.of(kept);
	}

}
