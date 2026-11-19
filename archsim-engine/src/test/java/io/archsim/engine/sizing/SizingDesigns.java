package io.archsim.engine.sizing;

import java.util.Objects;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * Small designs the auto-sizing tests size: clients calling a service that takes 10 ms of
 * CPU a request, which calls the design's other parts.
 */
final class SizingDesigns {

	/** A constant time of 10 ms, as a setting's JSON. */
	static final String TEN_MS = "{\"kind\": \"constant\", \"value\": 10}";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String CLIENTS = "{\"id\": \"clients\", \"type\": \"traffic_source\"}";

	private static final AutoSizer SIZER = new AutoSizer(StandardComponents.registry(), PriceSnapshots.shipped());

	private SizingDesigns() {
	}

	/**
	 * A service with 10 ms of CPU per request and the given settings.
	 * @param config its other settings, as JSON
	 * @return the service's node, as JSON
	 */
	static String service(String config) {
		ObjectNode settings = (ObjectNode) JSON.readTree("{\"serviceTimeMs\": " + TEN_MS + "}");
		settings.setAll((ObjectNode) JSON.readTree(config));
		return "{\"id\": \"api\", \"type\": \"service\", \"config\": " + settings + "}";
	}

	/**
	 * Clients calling the service, which calls the other parts linked by {@code links}
	 * (each {@code api-<id>}).
	 * @param nodes the service and the other parts, as JSON
	 * @param provider the prices' provider
	 * @param rps the requests per second
	 * @param links the links from the service to the other parts
	 * @return the design
	 */
	static ObjectNode design(String nodes, String provider, int rps, String... links) {
		StringBuilder edges = new StringBuilder(
				"{\"id\": \"clients-api\", \"source\": \"clients\", \"target\": \"api\"}");
		for (String link : links) {
			String target = link.substring(link.indexOf('-') + 1);
			edges.append(", {\"id\": \"%s\", \"source\": \"api\", \"target\": \"%s\"}".formatted(link, target));
		}
		return (ObjectNode) JSON.readTree("""
				{"architecture": {"nodes": [%s, %s], "edges": [%s]},
				 "workload": {"rps": %d, "readRatio": 0.8, "durationS": 60},
				 "pricing": {"provider": "%s"}}""".formatted(CLIENTS, nodes, edges, rps, provider));
	}

	/**
	 * Clients calling the service, which calls no other part.
	 * @param config the service's other settings, as JSON
	 * @param provider the prices' provider
	 * @param rps the requests per second
	 * @return the design
	 */
	static ObjectNode ofService(String config, String provider, int rps) {
		return design(service(config), provider, rps);
	}

	/**
	 * A design put on a host machine of two cores, four threads, that its parts fill.
	 * @param design the design, which is changed
	 * @return the design
	 */
	static ObjectNode onABusyHost(ObjectNode design) {
		((ObjectNode) design.get("architecture")).putObject("host")
			.put("physicalCores", 2)
			.put("threadsPerCore", 2)
			.put("siblingSpeed", 0.6)
			.put("allCoreClock", 0.8);
		return design;
	}

	/**
	 * The auto size of one part of a design.
	 * @param id the part
	 * @param design the design
	 * @return its size
	 */
	static PartSize size(String id, ObjectNode design) {
		return Objects.requireNonNull(SIZER.sizes(DesignDocuments.parse(design)).get(id), id);
	}

}
