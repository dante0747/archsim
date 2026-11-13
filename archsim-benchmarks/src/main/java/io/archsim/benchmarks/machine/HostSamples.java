package io.archsim.benchmarks.machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonStatistics;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.text.JsonDecodeException;
import io.archsim.engine.text.PythonJsonReader;

/**
 * Windows' own view of the machine while a check runs, one sample a second
 * ({@link HostCounters}), and its means over a window: each logical processor's busy
 * share as the hypervisor sees it (Docker's VM and Windows itself), the cores' actual
 * frequency and whether the firmware limited it (power or thermal).
 */
final class HostSamples {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String FREQUENCY = "processor information(_total)|actual frequency";

	private static final String LIMIT = "processor information(_total)|% performance limit";

	private static final String FLAGS = "processor information(_total)|performance limit flags";

	/** A sample's second counts once it lies wholly inside the window. */
	private static final double SAMPLE_MS = 1000.0;

	private static final double PERCENT = 100.0;

	private static final int DECIMALS = 2;

	private final List<JsonNode> samples = Collections.synchronizedList(new ArrayList<>());

	/**
	 * Takes a sample, as the counters' script printed it.
	 * @param line one line of JSON; anything else is left out
	 */
	void add(String line) {
		try {
			this.samples.add(PythonJsonReader.read(line));
		}
		catch (JsonDecodeException ex) {
			// a line that is not a sample: the script's own noise
		}
	}

	/**
	 * The means over the samples whose second lies inside a window.
	 * @param fromMs the window's start, host clock, in milliseconds
	 * @param toMs its end
	 * @return the samples' count, the frequency, the limit and its flags, each logical
	 * processor's busy share and their sum in processors; empty without a sample inside
	 */
	Optional<ObjectNode> window(double fromMs, double toMs) {
		List<JsonNode> inside = List.copyOf(this.samples)
			.stream()
			.filter((sample) -> sample.path("t").isNumber() && fromMs + SAMPLE_MS <= sample.get("t").doubleValue()
					&& sample.get("t").doubleValue() <= toMs)
			.toList();
		if (inside.isEmpty()) {
			return Optional.empty();
		}
		List<JsonNode> busy = processors(inside).stream()
			.filter((key) -> !key.contains("_total"))
			.map((key) -> mean(inside, key))
			.toList();
		ObjectNode window = NODES.objectNode().put("samples", inside.size());
		window.set("frequencyMHz", mean(inside, FREQUENCY));
		window.set("performanceLimitPct", mean(inside, LIMIT));
		ArrayNode flags = window.putArray("performanceLimitFlags");
		new TreeSet<>(inside.stream().map((sample) -> (int) sample.path(FLAGS).asDouble(0)).toList())
			.forEach(flags::add);
		window.putArray("logicalProcessorBusyPct").addAll(busy);
		double processors = PythonValues
			.sumOfFloats(busy.stream().map((share) -> share.isNumber() ? share.doubleValue() : 0.0).toList());
		window.put("busyLogicalProcessors", PythonRound.round(processors / PERCENT, DECIMALS));
		return Optional.of(window);
	}

	// the hypervisor's logical processors' counters, in the processors' order
	private static List<String> processors(List<JsonNode> inside) {
		TreeSet<String> keys = new TreeSet<>();
		inside.forEach((sample) -> sample.propertyNames()
			.stream()
			.filter((key) -> key.startsWith("hv lp ") || key.contains("(hv lp "))
			.forEach(keys::add));
		return keys.stream().sorted(Comparator.comparingInt(HostSamples::processor)).toList();
	}

	// the number a counter names its processor by: the digits before its counter's name
	private static int processor(String key) {
		String digits = key.split("\\|", -1)[0].replaceAll("\\D", "");
		return digits.isEmpty() ? 0 : Integer.parseInt(digits);
	}

	// a counter's mean over the samples that have it, or None
	private static JsonNode mean(List<JsonNode> inside, String key) {
		List<Double> values = inside.stream()
			.map((sample) -> sample.path(key))
			.filter(JsonNode::isNumber)
			.map(JsonNode::doubleValue)
			.toList();
		return values.isEmpty() ? PythonValues.none()
				: PythonValues.number(PythonRound.round(PythonStatistics.fmean(values), DECIMALS));
	}

}
