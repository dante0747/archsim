package io.archsim.benchmarks.machine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * The contention check's conditions ({@code contention-conditions.json}): the probe's, a
 * duty on its CPU and burners on others; the service's, burners on CPUs, at nice 19 where
 * a burner shares its CPU with the stack (it takes only what k6 or PostgreSQL leave
 * idle), else at normal priority.
 */
final class ContentionConditions {

	private static final JsonNode CONDITIONS = read();

	/** A service condition's burner at the lowest priority. */
	private static final String NICE = "nice 19";

	private ContentionConditions() {
	}

	/**
	 * The probe's conditions, in the order of a round. CPU 3 is the probe's hyperthread
	 * sibling on the laptop; 0-1, 4-5 and 6-7 are the other physical cores.
	 * @return the conditions
	 */
	static List<ProbeCondition> probe() {
		return CONDITIONS.get("probe")
			.valueStream()
			.map((condition) -> new ProbeCondition(condition.get("name").stringValue(),
					condition.get("duty").doubleValue(), burners(condition, JsonNode::doubleValue)))
			.toList();
	}

	/**
	 * The service's conditions: as benchmarked, then each with background work, then as
	 * benchmarked again.
	 * @return the conditions
	 */
	static List<ServiceCondition> service() {
		return CONDITIONS.get("service")
			.valueStream()
			.map((condition) -> new ServiceCondition(condition.get("name").stringValue(),
					burners(condition, (priority) -> NICE.equals(priority.stringValue()))))
			.toList();
	}

	/**
	 * A condition's name as a container's: lower case, each run of other characters a
	 * dash.
	 * @param name the condition's name
	 * @return the tag
	 */
	static String tag(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
	}

	// each burner's CPU and its setting, in the file's order
	private static <T> Map<Integer, T> burners(JsonNode condition, Function<JsonNode, T> setting) {
		Map<Integer, T> burners = new LinkedHashMap<>();
		condition.get("burners")
			.properties()
			.forEach((burner) -> burners.put(Integer.parseInt(burner.getKey()), setting.apply(burner.getValue())));
		return burners;
	}

	private static JsonNode read() {
		try (InputStream in = ContentionConditions.class.getResourceAsStream("contention-conditions.json")) {
			if (in == null) {
				throw new IllegalStateException("missing resource contention-conditions.json");
			}
			return PythonJsonReader.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A probe condition.
	 *
	 * @param name its name
	 * @param duty the probe's share of every 10 ms on its CPU
	 * @param burners each burner's CPU and duty
	 */
	record ProbeCondition(String name, double duty, Map<Integer, Double> burners) {

		// the burners copied, in their order
		ProbeCondition {
			burners = Collections.unmodifiableMap(new LinkedHashMap<>(burners));
		}

	}

	/**
	 * A service condition.
	 *
	 * @param name its name
	 * @param burners each burner's CPU and whether it runs at nice 19
	 */
	record ServiceCondition(String name, Map<Integer, Boolean> burners) {

		// the burners copied, in their order
		ServiceCondition {
			burners = Collections.unmodifiableMap(new LinkedHashMap<>(burners));
		}

	}

}
